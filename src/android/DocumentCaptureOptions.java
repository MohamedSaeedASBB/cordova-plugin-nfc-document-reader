package com.nfcdocumentreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What to photograph, and how. Built by the plugin from the JS options rather than by the camera
 * activity, so "an ID card has two sides and a passport has one" is stated in one place.
 */
class DocumentCaptureOptions {

    /** One page to photograph. */
    static class Step {
        final String key;      // "front" | "back" | "document"
        final String label;
        final String hint;

        Step(String key, String label, String hint) {
            this.key = key;
            this.label = label;
            this.hint = hint;
        }
    }

    String captureType = "document";     // "document" | "proofOfAddress"
    String documentType;                 // "id" | "passport", null for proof of address
    String title = "Capture document";
    List<Step> steps = new ArrayList<>();
    boolean runOcr = false;

    /**
     * Defaults for photographing an identity document, where the picture is a record rather than
     * a source of data — the chip already carries the fields, signed. A card fills the frame and
     * its print is large relative to it, so 1200px stays legible to a person.
     *
     * Two levers, and maxBytes is the one that guarantees the ceiling: the long-edge cap decides
     * the pixel count, the starting quality trims from there, and the encoder then steps quality
     * down until the result fits maxBytes whatever the subject turns out to be.
     *
     * captureProofOfAddress raises these, because there the picture IS the data: a dense bill has
     * small print, and whatever the backend OCRs is limited by what is sent, not by the camera.
     */
    int maxImageDimension = 1200;
    int maxImageBytes = 250 * 1024;
    int jpegQuality = 80;

    /** Proof of address: the page has to survive OCR, so it is allowed more room. */
    static final int PROOF_MAX_DIMENSION = 1800;
    static final int PROOF_MAX_BYTES = 600 * 1024;
    static final int PROOF_JPEG_QUALITY = 88;

    ImageCompressor.Options imageOptions() {
        ImageCompressor.Options imageOptions = new ImageCompressor.Options();
        imageOptions.maxDimension = maxImageDimension;
        imageOptions.maxBytes = maxImageBytes;
        imageOptions.initialQuality = jpegQuality;
        // Explicit, though this path passes no face box: face-cropping a utility bill would be an
        // absurd failure, and it should not depend on an argument being null somewhere else.
        imageOptions.cropToFace = false;
        return imageOptions;
    }

    static DocumentCaptureOptions fromJson(String json) {
        DocumentCaptureOptions options = new DocumentCaptureOptions();
        if (json == null) return options;
        try {
            JSONObject root = new JSONObject(json);
            options.captureType = root.optString("captureType", options.captureType);
            options.title = root.optString("title", options.title);
            options.runOcr = root.optBoolean("ocr", false);
            options.maxImageDimension = root.optInt("maxImageDimension", options.maxImageDimension);
            options.maxImageBytes = root.optInt("maxImageBytes", options.maxImageBytes);
            options.jpegQuality = root.optInt("jpegQuality", options.jpegQuality);
            if (root.has("documentType") && !root.isNull("documentType")) {
                options.documentType = root.optString("documentType");
            }

            JSONArray steps = root.optJSONArray("steps");
            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    options.steps.add(new Step(
                            step.optString("key", "page" + (i + 1)),
                            step.optString("label", "Document"),
                            step.optString("hint", "Place the document flat and fill the frame")));
                }
            }
        } catch (Exception ignored) {
            // Fall through to the defaults below; a malformed options object must not leave the
            // activity with no steps to run.
        }
        if (options.steps.isEmpty()) {
            options.steps.add(new Step("document", "Document",
                    "Place the document flat and fill the frame"));
        }
        return options;
    }

    /** The two sides of an ID card, or the single data page of a passport. */
    static JSONArray stepsForDocumentType(String documentType) {
        JSONArray steps = new JSONArray();
        try {
            if ("passport".equalsIgnoreCase(documentType)) {
                steps.put(step("front", "Passport photo page",
                        "Open the passport at the photo page and fill the frame"));
            } else {
                steps.put(step("front", "Front of the card",
                        "Place the front of the card flat and fill the frame"));
                steps.put(step("back", "Back of the card",
                        "Now turn the card over and photograph the back"));
            }
        } catch (Exception ignored) {}
        return steps;
    }

    private static JSONObject step(String key, String label, String hint) throws Exception {
        JSONObject step = new JSONObject();
        step.put("key", key);
        step.put("label", label);
        step.put("hint", hint);
        return step;
    }
}
