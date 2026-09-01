package com.nfcdocumentreader;

import android.util.Log;

import org.jmrtd.lds.icao.MRZInfo;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compares the MRZ printed on the document against the MRZ the chip carries in DG1.
 *
 * WHAT THIS CATCHES, AND WHAT IT DOES NOT
 * A document whose printed data has been altered while its chip is genuine — or a genuine chip
 * moved into a forged card — shows up here as a disagreement between the two. Passive
 * authentication proves the chip's data is what the issuing state signed; this proves the plastic
 * in the customer's hand says the same thing.
 *
 * Three fields cannot disagree by construction: the document number, date of birth and date of
 * expiry are what the BAC/PACE key is derived from, so a chip that opened at all already agreed
 * with the scanned values. They are still reported, as evidence rather than as a test.
 *
 * The fields that can genuinely differ are the names, nationality, issuing state, gender and
 * document code. Those are also the ones an OCR misread can corrupt, which is why a mismatch is
 * reported as a finding for a human rather than as proof of fraud: a smudged character on a worn
 * card produces the same signal as a tampered one.
 *
 * The scanned lines are parsed with jmrtd's own MRZInfo — the same parser that read the chip's
 * DG1 — so the two sides are compared as like for like rather than through two different readings
 * of the same layout.
 */
final class MrzChipComparison {

    private static final String TAG = "MrzChipComparison";

    private MrzChipComparison() {
    }

    /**
     * @param rawMrzLines the MRZ as scanned, lines separated by " | " or newlines
     * @param chip        the chip payload already assembled from DG1
     */
    static JSONObject compare(String rawMrzLines, JSONObject chip) {
        JSONObject comparison = new JSONObject();
        try {
            comparison.put("status", "notCompared");
            comparison.put("mismatches", new JSONArray());
            comparison.put("fieldsCompared", new JSONArray());

            if (rawMrzLines == null || rawMrzLines.trim().isEmpty() || chip == null) {
                comparison.put("reason", "NO_SCANNED_MRZ");
                return comparison;
            }

            String joined = rawMrzLines.replace("|", "").replace(" ", "")
                    .replace("\n", "").replace("\r", "").trim().toUpperCase();
            MRZInfo scanned;
            try {
                scanned = new MRZInfo(joined);
            } catch (Exception notParseable) {
                // A partial or misread scan is not a mismatch: there is nothing to compare.
                Log.i(TAG, "Scanned MRZ could not be parsed for comparison: "
                        + notParseable.getClass().getSimpleName());
                comparison.put("reason", "SCANNED_MRZ_NOT_PARSEABLE");
                return comparison;
            }

            JSONArray mismatches = new JSONArray();
            JSONArray compared = new JSONArray();

            compareField(compared, mismatches, "documentNumber",
                    scanned.getDocumentNumber(), chip.optString("documentNumber"));
            compareField(compared, mismatches, "dateOfBirth",
                    scanned.getDateOfBirth(), chip.optString("dateOfBirth"));
            compareField(compared, mismatches, "dateOfExpiry",
                    scanned.getDateOfExpiry(), chip.optString("dateOfExpiry"));
            compareField(compared, mismatches, "primaryIdentifier",
                    scanned.getPrimaryIdentifier(), chip.optString("primaryIdentifier"));
            compareField(compared, mismatches, "secondaryIdentifier",
                    scanned.getSecondaryIdentifier(), chip.optString("secondaryIdentifier"));
            compareField(compared, mismatches, "nationality",
                    scanned.getNationality(), chip.optString("nationality"));
            compareField(compared, mismatches, "issuingState",
                    scanned.getIssuingState(), chip.optString("issuingState"));
            compareField(compared, mismatches, "documentType",
                    scanned.getDocumentCode(), chip.optString("documentType"));

            comparison.put("fieldsCompared", compared);
            comparison.put("mismatches", mismatches);
            comparison.put("status", mismatches.length() == 0 ? "matched" : "mismatch");
            // Says plainly why three of these could never have disagreed, so nobody reads the
            // match as stronger evidence than it is.
            comparison.put("note", "documentNumber, dateOfBirth and dateOfExpiry derive the chip "
                    + "access key, so a successful read already agreed with them. A mismatch "
                    + "elsewhere may equally be an OCR misread of worn print.");

            Log.i(TAG, "Printed MRZ vs chip: " + comparison.optString("status")
                    + " (" + mismatches.length() + " mismatched of " + compared.length() + ")");
        } catch (Exception e) {
            Log.w(TAG, "MRZ comparison failed: " + e.getClass().getSimpleName());
        }
        return comparison;
    }

    private static void compareField(JSONArray compared, JSONArray mismatches,
                                     String field, String scanned, String chip) {
        String a = normalise(scanned);
        String b = normalise(chip);
        if (a.isEmpty() || b.isEmpty()) return;     // absent on one side is not a disagreement

        compared.put(field);
        if (a.equals(b)) return;
        try {
            JSONObject mismatch = new JSONObject();
            mismatch.put("field", field);
            mismatch.put("printed", scanned);
            mismatch.put("chip", chip);
            mismatches.put(mismatch);
        } catch (Exception ignored) {}
    }

    /** MRZ filler characters, spacing and case carry no meaning; a difference in them is not one. */
    private static String normalise(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : value.toUpperCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
