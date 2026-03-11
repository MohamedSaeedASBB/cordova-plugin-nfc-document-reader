package com.nfcdocumentreader;

import android.util.Log;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Processes ML Kit text recognition results to extract MRZ data.
 * Supports TD1 (3 lines x 30 chars), TD2 (2 lines x 36 chars),
 * and TD3 (2 lines x 44 chars).
 * Ported from Kotlin MrzOcrProcessor.kt.
 */
public class MrzOcrProcessor {

    private static final String TAG = "MrzOcrProcessor";
    private static final Pattern MRZ_LINE_PATTERN = Pattern.compile("[A-Z0-9<]{28,44}");

    private static final Map<Character, Character> OCR_CORRECTIONS = new HashMap<>();
    static {
        OCR_CORRECTIONS.put('O', '0');
        OCR_CORRECTIONS.put('I', '1');
        OCR_CORRECTIONS.put('S', '5');
        OCR_CORRECTIONS.put('B', '8');
        OCR_CORRECTIONS.put('G', '6');
        OCR_CORRECTIONS.put('D', '0');
    }

    public static class MrzParseResult {
        public final String documentNumber;
        public final String dateOfBirth;
        public final String dateOfExpiry;
        public final List<String> rawLines;
        public final String format;
        public final String error;

        public MrzParseResult(String documentNumber, String dateOfBirth, String dateOfExpiry,
                              List<String> rawLines, String format, String error) {
            this.documentNumber = documentNumber;
            this.dateOfBirth = dateOfBirth;
            this.dateOfExpiry = dateOfExpiry;
            this.rawLines = rawLines;
            this.format = format;
            this.error = error;
        }

        public boolean isSuccess() {
            return documentNumber != null && dateOfBirth != null && dateOfExpiry != null;
        }
    }

    /**
     * Process ML Kit text recognition result and extract MRZ data.
     */
    public MrzParseResult processText(Text text) {
        List<String> allLines = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String cleaned = cleanMrzLine(line.getText());
                if (cleaned.length() >= 28 && MRZ_LINE_PATTERN.matcher(cleaned).matches()) {
                    allLines.add(cleaned);
                }
            }
        }

        Log.d(TAG, "Found " + allLines.size() + " potential MRZ lines: " + allLines);

        if (allLines.isEmpty()) {
            return new MrzParseResult(null, null, null, allLines, "", "No MRZ lines detected");
        }

        // Try TD3 (passport - 2 lines x 44 chars)
        List<String> td3Lines = filterByLength(allLines, 42, 46);
        if (td3Lines.size() >= 2) {
            MrzParseResult result = parseTD3(
                padRight(td3Lines.get(0).substring(0, Math.min(44, td3Lines.get(0).length())), 44, '<'),
                padRight(td3Lines.get(1).substring(0, Math.min(44, td3Lines.get(1).length())), 44, '<')
            );
            if (result.isSuccess()) return result;
        }

        // Try TD1 (ID card - 3 lines x 30 chars)
        List<String> td1Lines = filterByLength(allLines, 28, 32);
        if (td1Lines.size() >= 3) {
            MrzParseResult result = parseTD1(
                padRight(td1Lines.get(0).substring(0, Math.min(30, td1Lines.get(0).length())), 30, '<'),
                padRight(td1Lines.get(1).substring(0, Math.min(30, td1Lines.get(1).length())), 30, '<'),
                padRight(td1Lines.get(2).substring(0, Math.min(30, td1Lines.get(2).length())), 30, '<')
            );
            if (result.isSuccess()) return result;
        }

        // Try TD2 (2 lines x 36 chars)
        List<String> td2Lines = filterByLength(allLines, 34, 38);
        if (td2Lines.size() >= 2) {
            MrzParseResult result = parseTD2(
                padRight(td2Lines.get(0).substring(0, Math.min(36, td2Lines.get(0).length())), 36, '<'),
                padRight(td2Lines.get(1).substring(0, Math.min(36, td2Lines.get(1).length())), 36, '<')
            );
            if (result.isSuccess()) return result;
        }

        return new MrzParseResult(null, null, null, allLines, "", "Could not parse MRZ from detected lines");
    }

    private MrzParseResult parseTD3(String line1, String line2) {
        try {
            String docNumber = line2.substring(0, 9).replace("<", "").trim();
            String dob = correctNumericField(line2.substring(13, 19));
            String expiry = correctNumericField(line2.substring(21, 27));

            Log.d(TAG, "TD3 parsed - DocNum: " + docNumber + ", DOB: " + dob + ", Expiry: " + expiry);

            List<String> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);
            return new MrzParseResult(docNumber, dob, expiry, lines, "TD3", null);
        } catch (Exception e) {
            List<String> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);
            return new MrzParseResult(null, null, null, lines, "TD3", "TD3 parse error: " + e.getMessage());
        }
    }

    private MrzParseResult parseTD1(String line1, String line2, String line3) {
        try {
            // ICAO 9303 Part 5 - TD1: positions 5-13 = doc number (9 chars), position 14 = check digit
            // If position 14 is '<', the document number overflows into optional data (positions 15+)
            String docNumberBase = line1.substring(5, 14); // 9 chars
            char pos14 = line1.charAt(14);
            String docNumber;

            if (pos14 == '<') {
                // Extended document number — continues in optional data (positions 15-29)
                // Format: continuation chars + check digit + filler '<'
                String optionalData = line1.length() > 15 ? line1.substring(15) : "";
                int fillerIdx = optionalData.indexOf('<');
                if (fillerIdx > 1) {
                    // Everything before first '<' = continuation + check digit
                    // Last char is check digit, rest is continuation
                    String contAndCheck = optionalData.substring(0, fillerIdx);
                    String continuation = contAndCheck.substring(0, contAndCheck.length() - 1);
                    docNumber = (docNumberBase + continuation).replace("<", "").trim();
                } else {
                    // No meaningful continuation found
                    docNumber = docNumberBase.replace("<", "").trim();
                }
                Log.d(TAG, "TD1 extended doc number detected: " + docNumber);
            } else {
                // Standard case — 9-char document number
                docNumber = docNumberBase.replace("<", "").trim();
            }

            String dob = correctNumericField(line2.substring(0, 6));
            String expiry = correctNumericField(line2.substring(8, 14));

            Log.d(TAG, "TD1 parsed - DocNum: " + docNumber + ", DOB: " + dob + ", Expiry: " + expiry +
                ", raw line1: " + line1);

            List<String> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);
            lines.add(line3);
            return new MrzParseResult(docNumber, dob, expiry, lines, "TD1", null);
        } catch (Exception e) {
            List<String> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);
            lines.add(line3);
            return new MrzParseResult(null, null, null, lines, "TD1", "TD1 parse error: " + e.getMessage());
        }
    }

    private MrzParseResult parseTD2(String line1, String line2) {
        try {
            String docNumber = line2.substring(0, 9).replace("<", "").trim();
            String dob = correctNumericField(line2.substring(13, 19));
            String expiry = correctNumericField(line2.substring(21, 27));

            Log.d(TAG, "TD2 parsed - DocNum: " + docNumber + ", DOB: " + dob + ", Expiry: " + expiry);

            List<String> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);
            return new MrzParseResult(docNumber, dob, expiry, lines, "TD2", null);
        } catch (Exception e) {
            List<String> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);
            return new MrzParseResult(null, null, null, lines, "TD2", "TD2 parse error: " + e.getMessage());
        }
    }

    private String cleanMrzLine(String raw) {
        return raw.toUpperCase()
            .replace(" ", "")
            .replace("\u00AB", "<")  // «
            .replace("\u2039", "<")  // ‹
            .replace("(", "<")
            .replace(")", "")
            .replace("[", "<")
            .replace("]", "")
            .replaceAll("[^A-Z0-9<]", "");
    }

    private String correctNumericField(String field) {
        StringBuilder corrected = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char ch = field.charAt(i);
            if (Character.isDigit(ch) || ch == '<') {
                corrected.append(ch);
            } else if (Character.isLetter(ch)) {
                Character replacement = OCR_CORRECTIONS.get(ch);
                corrected.append(replacement != null ? replacement : '0');
            } else {
                corrected.append('0');
            }
        }
        String result = corrected.toString();
        // Ensure result keeps original length
        while (result.length() < field.length()) result += "0";
        if (result.length() > field.length()) result = result.substring(0, field.length());
        return result;
    }

    private List<String> filterByLength(List<String> lines, int minLen, int maxLen) {
        List<String> filtered = new ArrayList<>();
        for (String line : lines) {
            if (line.length() >= minLen && line.length() <= maxLen) {
                filtered.add(line);
            }
        }
        return filtered;
    }

    private static String padRight(String s, int length, char padChar) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < length) {
            sb.append(padChar);
        }
        return sb.toString();
    }
}
