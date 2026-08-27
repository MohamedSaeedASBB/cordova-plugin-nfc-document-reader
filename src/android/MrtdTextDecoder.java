package com.nfcdocumentreader;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recovers DG11/DG12 text that UTF-8 decoding destroyed.
 *
 * WHY THIS IS NEEDED
 * ICAO 9303 says DG11/DG12 strings are UTF-8, and both jmrtd and NFCPassportReader decode them
 * that way. Some issuers do not comply: an Algerian ID observed in testing stores the Arabic
 * fields in a single-byte Arabic code page, so every Arabic letter is one byte that is not valid
 * UTF-8. Decoding it as UTF-8 turns each letter into U+FFFD, and by the time the value is a
 * String the original bytes are gone — the holder's name in Arabic arrives as a row of boxes.
 *
 * The bytes themselves are fine. In the payload that prompted this, the data group hashes matched
 * the issuer's signature, so DG11 and DG12 were byte-for-byte as signed; only the decoding was
 * wrong. Passive authentication captures those raw bytes already, so the text can simply be
 * decoded again, properly.
 *
 * HOW IT DECIDES
 * Only fields that actually failed are touched: a value is recovered only when the string jmrtd
 * produced contains U+FFFD. Conformant documents therefore go through completely unchanged.
 *
 * The encoding is chosen once for the whole document, not per field. Round-tripping test data
 * showed why: windows-1256 and ISO-8859-6 both decode the other's bytes into plausible Arabic, so
 * a short field like a first name scores the same either way and the tie goes to whichever was
 * tried first — recovering one field correctly and its neighbour wrongly. Scoring every damaged
 * field together and applying one winner uses the most evidence available and cannot produce a
 * document whose fields disagree about their own encoding.
 *
 * The winner is reported in the payload as {@code textEncoding} rather than applied silently,
 * because picking a code page remains a guess about the issuer's intent: the two candidates agree
 * on the core Arabic letters and differ elsewhere, so a recovered name should be checked against
 * the physical document before it is trusted as a customer record.
 */
final class MrtdTextDecoder {

    private static final String TAG = "MrtdTextDecoder";

    /** Tried in order. UTF-8 first: a conformant document must never reach the fallbacks. */
    private static final String[] CANDIDATE_CHARSETS = { "UTF-8", "windows-1256", "ISO-8859-6" };

    // DG11
    private static final int TAG_FULL_NAME         = 0x5F0E;
    private static final int TAG_OTHER_NAMES       = 0x5F0F;
    private static final int TAG_PLACE_OF_BIRTH    = 0x5F11;
    private static final int TAG_PERMANENT_ADDRESS = 0x5F42;
    private static final int TAG_TELEPHONE         = 0x5F12;
    private static final int TAG_PERSONAL_SUMMARY  = 0x5F15;
    // DG12
    private static final int TAG_ISSUING_AUTHORITY = 0x5F19;
    private static final int TAG_ENDORSEMENTS      = 0x5F1B;

    private MrtdTextDecoder() {
    }

    /** True when UTF-8 decoding replaced characters it could not read. */
    static boolean isDamaged(String value) {
        return value != null && value.indexOf('�') >= 0;
    }

    /**
     * Repairs {@code data}'s DG11/DG12 text fields in place when they came back damaged.
     *
     * @param rawDgBytes data group number to the exact bytes read from the chip
     * @return the encoding used for recovery, or null if nothing needed repairing
     */
    static String recover(DocumentData data, Map<Integer, byte[]> rawDgBytes) {
        if (data == null || rawDgBytes == null) return null;

        boolean dg11Damaged = isDamaged(data.fullNameOfHolder)
                || isDamaged(data.placeOfBirth)
                || isDamaged(data.permanentAddress)
                || isDamaged(data.personalSummary)
                || isDamaged(data.telephone)
                || anyDamaged(data.otherNames);
        boolean dg12Damaged = isDamaged(data.issuingAuthority)
                || isDamaged(data.endorsementsAndObservations);

        if (!dg11Damaged && !dg12Damaged) return null;

        Map<Integer, List<byte[]>> dg11 = dg11Damaged && rawDgBytes.containsKey(11)
                ? parseTlv(rawDgBytes.get(11)) : new LinkedHashMap<Integer, List<byte[]>>();
        Map<Integer, List<byte[]>> dg12 = dg12Damaged && rawDgBytes.containsKey(12)
                ? parseTlv(rawDgBytes.get(12)) : new LinkedHashMap<Integer, List<byte[]>>();

        // One encoding for the whole document, scored across every damaged field at once.
        List<byte[]> evidence = new ArrayList<>();
        for (int tag : new int[] { TAG_FULL_NAME, TAG_OTHER_NAMES, TAG_PLACE_OF_BIRTH,
                                   TAG_PERMANENT_ADDRESS, TAG_PERSONAL_SUMMARY, TAG_TELEPHONE }) {
            List<byte[]> values = dg11.get(tag);
            if (values != null) evidence.addAll(values);
        }
        for (int tag : new int[] { TAG_ISSUING_AUTHORITY, TAG_ENDORSEMENTS }) {
            List<byte[]> values = dg12.get(tag);
            if (values != null) evidence.addAll(values);
        }

        String encodingUsed = chooseCharset(evidence);
        if (encodingUsed == null) return null;

        if (!dg11.isEmpty()) {
            String name = decodeWith(dg11, TAG_FULL_NAME, encodingUsed);
            String place = decodeWith(dg11, TAG_PLACE_OF_BIRTH, encodingUsed);
            String address = decodeWith(dg11, TAG_PERMANENT_ADDRESS, encodingUsed);
            String summary = decodeWith(dg11, TAG_PERSONAL_SUMMARY, encodingUsed);
            String phone = decodeWith(dg11, TAG_TELEPHONE, encodingUsed);

            if (name != null) data.fullNameOfHolder = name;
            if (summary != null) data.personalSummary = summary;
            if (phone != null) data.telephone = phone;
            // The raw value keeps DG11's '<' separators, so split them back into the components
            // the rest of the payload exposes — otherwise a recovered field would arrive in a
            // different shape from an undamaged one.
            if (place != null) {
                data.placeOfBirthLines = splitComponents(place);
                data.placeOfBirth = join(data.placeOfBirthLines);
            }
            if (address != null) {
                data.permanentAddressLines = splitComponents(address);
                data.permanentAddress = join(data.permanentAddressLines);
            }

            List<byte[]> otherNameValues = dg11.get(TAG_OTHER_NAMES);
            if (otherNameValues != null && !otherNameValues.isEmpty()) {
                List<String> recovered = new ArrayList<>();
                for (byte[] value : otherNameValues) {
                    String decoded = decodeStrictly(value, encodingUsed);
                    if (decoded != null) recovered.add(decoded);
                }
                if (!recovered.isEmpty()) data.otherNames = recovered;
            }
        }

        if (!dg12.isEmpty()) {
            String authority = decodeWith(dg12, TAG_ISSUING_AUTHORITY, encodingUsed);
            String endorsements = decodeWith(dg12, TAG_ENDORSEMENTS, encodingUsed);
            if (authority != null) data.issuingAuthority = authority;
            if (endorsements != null) data.endorsementsAndObservations = endorsements;
        }

        {
            // Field names only — never the recovered values, which are holder data.
            Log.i(TAG, "Recovered DG11/DG12 text using " + encodingUsed
                    + " after UTF-8 decoding failed. Verify against the physical document.");
        }
        return encodingUsed;
    }

    private static boolean anyDamaged(List<String> values) {
        if (values == null) return false;
        for (String value : values) {
            if (isDamaged(value)) return true;
        }
        return false;
    }

    /** DG11 separates a field's components with '<'; runs of them are one separator. */
    static List<String> splitComponents(String value) {
        List<String> parts = new ArrayList<>();
        if (value == null) return parts;
        for (String part : value.split("<+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    private static String join(List<String> parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(part);
        }
        return joined.toString();
    }

    private static String decodeWith(Map<Integer, List<byte[]>> fields, int tag, String charsetName) {
        List<byte[]> values = fields.get(tag);
        if (values == null || values.isEmpty()) return null;
        return decodeStrictly(values.get(0), charsetName);
    }

    /**
     * Picks the single encoding that best explains every damaged field in the document. A
     * candidate that cannot decode some field at all is disqualified rather than scored: a code
     * page that chokes on part of the document is not the one the issuer used.
     */
    static String chooseCharset(List<byte[]> values) {
        if (values == null || values.isEmpty()) return null;

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String charsetName : CANDIDATE_CHARSETS) {
            int total = 0;
            boolean usable = true;
            for (byte[] value : values) {
                String text = decodeStrictly(value, charsetName);
                if (text == null) { usable = false; break; }
                total += score(text);
            }
            if (!usable) continue;
            if ("UTF-8".equals(charsetName)) return charsetName;   // conformant after all
            if (total > bestScore) {
                bestScore = total;
                best = charsetName;
            }
        }
        return best;
    }

    // ==================== Decoding ====================

    static final class Decoded {
        final String text;
        final String charsetName;

        Decoded(String text, String charsetName) {
            this.text = text;
            this.charsetName = charsetName;
        }
    }

    /**
     * Decodes one field. UTF-8 strictly first; only if that is rejected does it try the Arabic
     * code pages, choosing whichever produces the most Arabic letters and no control characters.
     */
    static Decoded decode(byte[] value) {
        if (value == null) return null;

        Decoded best = null;
        int bestScore = Integer.MIN_VALUE;

        for (String charsetName : CANDIDATE_CHARSETS) {
            String text = decodeStrictly(value, charsetName);
            if (text == null) continue;                 // charset rejected these bytes
            if ("UTF-8".equals(charsetName)) {
                return new Decoded(text, charsetName);  // conformant: nothing else to consider
            }
            int score = score(text);
            if (score > bestScore) {
                bestScore = score;
                best = new Decoded(text, charsetName);
            }
        }
        return best;
    }

    /** Returns null rather than substituting replacement characters, so failure is detectable. */
    private static String decodeStrictly(byte[] value, String charsetName) {
        try {
            CharsetDecoder decoder = Charset.forName(charsetName).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(value));
            return decoded.toString();
        } catch (CharacterCodingException rejected) {
            return null;
        } catch (Exception unsupported) {
            Log.w(TAG, "Charset unavailable on this device: " + charsetName);
            return null;
        }
    }

    /** Arabic letters count for, control characters against. */
    private static int score(String text) {
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) score += 2;          // Arabic block
            else if (c == '�') score -= 5;
            else if (Character.isISOControl(c)) score -= 3;
            else if (c >= 0x20 && c < 0x7F) score += 1;          // printable ASCII, e.g. "<<"
        }
        return score;
    }

    // ==================== BER-TLV ====================

    /** Flattens a data group into tag -> values, descending into constructed tags. */
    static Map<Integer, List<byte[]>> parseTlv(byte[] data) {
        Map<Integer, List<byte[]>> out = new LinkedHashMap<>();
        if (data != null) {
            try {
                walk(data, 0, data.length, out, 0);
            } catch (Exception malformed) {
                // A truncated or unexpected structure yields whatever was read before it; the
                // caller keeps jmrtd's value for anything missing.
                Log.w(TAG, "Stopped parsing data group TLV: " + malformed.getClass().getSimpleName());
            }
        }
        return out;
    }

    private static void walk(byte[] data, int start, int end,
                             Map<Integer, List<byte[]>> out, int depth) {
        if (depth > 8) return;                                   // structural loop guard
        int index = start;
        while (index < end) {
            int first = data[index] & 0xFF;
            if (first == 0x00 || first == 0xFF) { index++; continue; }   // padding

            int tag = first;
            index++;
            if ((first & 0x1F) == 0x1F) {                        // multi-byte tag, e.g. 0x5F0E
                do {
                    if (index >= end) return;
                    tag = (tag << 8) | (data[index] & 0xFF);
                } while ((data[index++] & 0x80) != 0);
            }

            if (index >= end) return;
            int lengthByte = data[index++] & 0xFF;
            int length;
            if (lengthByte < 0x80) {
                length = lengthByte;
            } else {
                int lengthBytes = lengthByte & 0x7F;
                if (lengthBytes == 0 || lengthBytes > 4 || index + lengthBytes > end) return;
                length = 0;
                for (int i = 0; i < lengthBytes; i++) {
                    length = (length << 8) | (data[index++] & 0xFF);
                }
            }
            if (length < 0 || index + length > end) return;

            boolean constructed = (first & 0x20) != 0;
            if (constructed) {
                walk(data, index, index + length, out, depth + 1);
            } else {
                byte[] value = new byte[length];
                System.arraycopy(data, index, value, 0, length);
                List<byte[]> values = out.get(tag);
                if (values == null) {
                    values = new ArrayList<>();
                    out.put(tag, values);
                }
                values.add(value);
            }
            index += length;
        }
    }
}
