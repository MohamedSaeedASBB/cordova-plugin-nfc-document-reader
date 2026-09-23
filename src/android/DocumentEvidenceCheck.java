package com.nfcdocumentreader;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a captured photograph actually shows the document, before it is kept.
 *
 * WHY
 * The capture screen photographed whatever was in front of the lens. A real payload from this
 * plugin carried a chip read of a genuine Algerian ID with, attached to it, a photograph of a
 * panda mug and a photograph of a keyboard. The back office received signed chip data and two
 * pictures of an office desk, and nothing in the flow noticed.
 *
 * WHAT DISCRIMINATES, MEASURED ON REAL CAPTURES
 * Two obvious signals turn out not to work. Text volume does not: recognising an ID card yielded
 * 8-9 lines, and the keyboard yielded 8. Face detection does not either: the card's MRZ side has
 * no detectable face at the framing people actually use — a card held in one hand fills perhaps a
 * third of the frame, so its portrait is a hundred pixels across — and a colleague sitting
 * opposite can supply a face that has nothing to do with the document.
 *
 * What does separate them is the document's own machine-readable structure, and the identifiers
 * the flow already knows:
 *
 *   - MRZ lines. Monospaced, 30/36/44 characters of A-Z 0-9 '<'. They survive OCR at the
 *     resolution these photographs are taken at, and nothing on a desk looks like them.
 *   - Identifiers from the MRZ scan and the chip: document number, personal number, surname. On
 *     the real card both sides carried at least one, in print large enough to read.
 *
 * The second is the stronger claim, and it is worth being precise about what it means. It does not
 * establish "this is an ID card". It establishes "this is the document we just read", which is the
 * question actually worth answering: it also rejects a photograph of a different genuine ID.
 *
 * WHAT THIS CANNOT DO
 * It cannot tell a card from a photograph of a card, or from a card displayed on a screen. Those
 * are presentation attacks on the document, and detecting them needs specialist capture — glare
 * and moiré analysis, hologram behaviour under a flash — that this plugin does not have. A
 * "confirmed" result means the right text was in the frame, not that the object was genuine.
 */
final class DocumentEvidenceCheck {

    private static final String TAG = "DocumentEvidence";

    /** Shortest MRZ line is TD1's 30; longest is TD3's 44. Allow for OCR over- and under-run. */
    private static final int MRZ_MIN_LENGTH = 28;
    private static final int MRZ_MAX_LENGTH = 48;
    /** An identifier shorter than this matches too much text by accident. */
    private static final int MIN_IDENTIFIER_LENGTH = 5;

    private DocumentEvidenceCheck() {
    }

    static class Result {
        /** "confirmed" when the document was recognised, "notConfirmed" otherwise. */
        String status = "notConfirmed";
        int textLines = 0;
        int mrzLines = 0;
        String mrzFormat;                       // set when the lines parse as a whole MRZ
        final List<String> matchedIdentifiers = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();

        boolean isConfirmed() {
            return "confirmed".equals(status);
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("status", status);
            json.put("textLines", textLines);
            json.put("mrzLines", mrzLines);
            json.put("mrzFormat", mrzFormat != null ? mrzFormat : JSONObject.NULL);
            // Which identifiers were found, never their values: the values are holder data and
            // already elsewhere in the payload.
            json.put("matchedIdentifiers", new JSONArray(matchedIdentifiers));
            json.put("reasons", new JSONArray(reasons));
            return json;
        }
    }

    /**
     * @param bitmap      the captured frame at full resolution, before compression
     * @param expected    identifiers already known from the MRZ scan or the chip, labelled
     *                    "name:value" so the result can say which one matched without repeating it
     * @param recognizer  ML Kit text recognition
     * @param mrzProcessor used to try to parse the recognised lines as a whole MRZ
     */
    static Result inspect(Bitmap bitmap, List<String> expected,
                          TextRecognizer recognizer, MrzOcrProcessor mrzProcessor) {
        Result result = new Result();
        if (bitmap == null || recognizer == null) {
            result.reasons.add("CHECK_NOT_RUN");
            return result;
        }

        Text text;
        try {
            text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
        } catch (Exception e) {
            // A failed check must not read as a failed document.
            Log.w(TAG, "Text recognition failed during the document check: "
                    + e.getClass().getSimpleName());
            result.reasons.add("TEXT_RECOGNITION_FAILED");
            return result;
        }

        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lines.add(line.getText());
            }
        }

        String mrzFormat = null;
        try {
            // A whole parsed MRZ is better evidence than loose MRZ-shaped lines, so try for it.
            MrzOcrProcessor.MrzParseResult parsed = mrzProcessor.processText(text);
            if (parsed != null && parsed.isSuccess()) mrzFormat = parsed.format;
        } catch (Exception ignored) {
            // Falls back to the line-shape count in decide().
        }

        return decide(lines, mrzFormat, expected);
    }

    /**
     * The decision itself, separated from how the text was obtained so it can be exercised against
     * real photographs without a device: recognition produces lines, this turns lines into a
     * verdict.
     */
    static Result decide(List<String> lines, String mrzFormat, List<String> expected) {
        Result result = new Result();
        result.mrzFormat = mrzFormat;

        StringBuilder all = new StringBuilder();
        for (String line : lines) {
            result.textLines++;
            if (looksLikeMrzLine(line)) result.mrzLines++;
            all.append(line).append('\n');
        }

        if (result.textLines == 0) {
            result.reasons.add("NO_TEXT_FOUND");
            return result;
        }

        String haystack = normalise(all.toString());
        if (expected != null) {
            for (String labelled : expected) {
                int colon = labelled.indexOf(':');
                if (colon < 1 || colon == labelled.length() - 1) continue;
                String label = labelled.substring(0, colon);
                String needle = normalise(labelled.substring(colon + 1));
                if (needle.length() < MIN_IDENTIFIER_LENGTH) continue;
                if (haystack.contains(needle)) result.matchedIdentifiers.add(label);
            }
        }

        if (!result.matchedIdentifiers.isEmpty() || result.mrzFormat != null || result.mrzLines > 0) {
            result.status = "confirmed";
        } else {
            // Deliberately not graded by how much text there is. A keyboard photographed at a desk
            // produced as many lines as the ID card did, so "plenty of text" is not evidence.
            result.reasons.add(expected == null || expected.isEmpty()
                    ? "NO_MRZ_FOUND"
                    : "NO_MRZ_OR_KNOWN_IDENTIFIER_FOUND");
        }
        return result;
    }

    /** Monospaced, filler-padded, and from the MRZ alphabet — a shape nothing on a desk has. */
    private static boolean looksLikeMrzLine(String line) {
        if (line == null) return false;
        String cleaned = line.replace(" ", "").toUpperCase();
        if (cleaned.length() < MRZ_MIN_LENGTH || cleaned.length() > MRZ_MAX_LENGTH) return false;
        if (cleaned.indexOf('<') < 0) return false;

        int allowed = 0;
        for (char c : cleaned.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '<') allowed++;
        }
        return allowed * 10 >= cleaned.length() * 9;      // at least 90% MRZ alphabet
    }

    /** Case, spacing and punctuation carry no meaning for this comparison. */
    private static String normalise(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : value.toUpperCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
