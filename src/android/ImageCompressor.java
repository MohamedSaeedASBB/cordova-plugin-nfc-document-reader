package com.nfcdocumentreader;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Downscales and JPEG-compresses captured selfie frames to a byte budget before they leave
 * the native layer.
 *
 * Liveness frames are raw camera frames (1280x720 ARGB is ~3.5 MB); handing that to the
 * WebView as base64 and then to the back office is wasteful and slow. We crop to the face,
 * cap the long edge, then step JPEG quality down until the payload fits the budget.
 *
 * ImageCompressor.swift mirrors this behaviour — keep the two in sync.
 */
public class ImageCompressor {

    private static final String TAG = "ImageCompressor";

    /** Quality step used when walking down towards the byte budget. */
    private static final int QUALITY_STEP = 8;

    public static class Options {
        /** Longest edge of the output image, in pixels. */
        public int maxDimension = 720;
        /** Hard budget for the encoded JPEG, in bytes. */
        public int maxBytes = 200 * 1024;
        public int initialQuality = 85;
        public int minQuality = 45;
        /** Crop to the face box, expanded by this fraction of the box on each side. */
        public boolean cropToFace = true;
        public float faceCropPadding = 0.55f;
    }

    public static class Result {
        public byte[] jpeg;
        public int width;
        public int height;
        public int quality;

        /** Base64 without line wrapping, matching the faceImageBase64 field from the NFC chip. */
        public String toBase64() {
            return Base64.encodeToString(jpeg, Base64.NO_WRAP);
        }
    }

    /**
     * @param source  the captured frame, already rotated upright
     * @param faceBox face bounds in {@code source} pixel coordinates, or null for no crop
     */
    public static Result compress(Bitmap source, Rect faceBox, Options options) {
        Bitmap working = source;
        Bitmap cropped = null;
        Bitmap scaled = null;

        try {
            if (options.cropToFace && faceBox != null) {
                cropped = cropToFace(source, faceBox, options.faceCropPadding);
                if (cropped != null) {
                    working = cropped;
                }
            }

            scaled = scaleToMaxDimension(working, options.maxDimension);
            if (scaled != null) {
                working = scaled;
            }

            Result result = new Result();
            result.width = working.getWidth();
            result.height = working.getHeight();

            int quality = clamp(options.initialQuality, options.minQuality, 100);
            byte[] encoded = encode(working, quality);

            while (encoded.length > options.maxBytes && quality > options.minQuality) {
                quality = Math.max(options.minQuality, quality - QUALITY_STEP);
                encoded = encode(working, quality);
            }

            result.jpeg = encoded;
            result.quality = quality;

            // Size only — never log image bytes or base64: this is biometric PII.
            Log.d(TAG, "Compressed selfie: " + result.width + "x" + result.height
                    + " q=" + quality + " bytes=" + encoded.length
                    + (encoded.length > options.maxBytes ? " (over budget at min quality)" : ""));

            return result;
        } finally {
            // Recycle only the intermediates we created — `source` belongs to the caller.
            // Both are safe to drop here: the JPEG bytes are already encoded.
            if (scaled != null && scaled != source) {
                scaled.recycle();
            }
            if (cropped != null && cropped != source && cropped != scaled) {
                cropped.recycle();
            }
        }
    }

    // ==================== Steps ====================

    /**
     * Expands the ML Kit face box outwards so the crop keeps hair, chin and some background —
     * face matchers do better with that context than with a box cropped tight to the features.
     */
    private static Bitmap cropToFace(Bitmap source, Rect faceBox, float padding) {
        int padX = (int) (faceBox.width() * padding);
        int padY = (int) (faceBox.height() * padding);

        int left = Math.max(0, faceBox.left - padX);
        int top = Math.max(0, faceBox.top - padY);
        int right = Math.min(source.getWidth(), faceBox.right + padX);
        int bottom = Math.min(source.getHeight(), faceBox.bottom + padY);

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Face box outside frame bounds — skipping crop");
            return null;
        }

        return Bitmap.createBitmap(source, left, top, width, height);
    }

    private static Bitmap scaleToMaxDimension(Bitmap source, int maxDimension) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (maxDimension <= 0 || longEdge <= maxDimension) {
            return null;
        }

        float scale = (float) maxDimension / longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static byte[] encode(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
