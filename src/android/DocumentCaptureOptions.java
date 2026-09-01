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
     * Bigger and less compressed than the liveness portrait: that one only has to survive a face
     * matcher, this one has to stay readable to a person and to OCR.
     */
    int maxImageDimension = 1600;
    int maxImageBytes = 500 * 1024;
    int jpegQuality = 90;

    ImageCompressor.Options imageOptions() {
        ImageCompressor.Options imageOptions = new ImageCompressor.Options();
        imageOptions.maxDimension = maxImageDimension;
        imageOptions.maxBytes = maxImageBytes;
        imageOptions.initialQuality = jpegQuality;
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
