package com.nfcdocumentreader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    /** Iterations of the quality search. Six halvings resolve the 45-92 range to about 1. */
    private static final int QUALITY_SEARCH_STEPS = 6;

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

        /**
         * Search for the lowest JPEG quality that still meets {@link #minPSNR}, instead of
         * encoding at {@link #initialQuality} and only reducing if the byte budget is exceeded.
         *
         * A fixed quality spends the same bits on a plain background as on a detailed face, so an
         * easy image is stored far larger than it needs to be while a hard one may be pushed below
         * what a face matcher can use. Measuring the result and stopping at the threshold makes the
         * <em>quality</em> the constant and lets the size fall where it falls.
         */
        public boolean useQualitySearch = true;

        /**
         * Peak signal-to-noise ratio, in dB, against the uncompressed bitmap. 38 dB is where JPEG
         * artefacts stop being visible on a face at these dimensions; the usual "visually
         * lossless" range quoted for photographs is 36-40.
         *
         * Below about 34 the eye and mouth detail a face matcher relies on starts to go, so that
         * is the floor worth defending. Mirrors ImageCompressor.swift.
         */
        public double minPSNR = 38.0;
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
            byte[] encoded;

            if (options.useQualitySearch) {
                int[] found = searchQuality(working, options, quality);
                quality = found[0];
                encoded = encode(working, quality);
            } else {
                encoded = encode(working, quality);
            }

            // The byte budget is a hard cap and outranks the quality floor: a payload that will
            // not fit through the backend is worse than one that is slightly soft.
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

    /**
     * Binary-searches for the lowest quality whose PSNR against the uncompressed bitmap still
     * clears {@code options.minPSNR}. Six encode/measure rounds on a 720px image cost a fraction of
     * the time the face detector has already spent on the same frame.
     *
     * @return a one-element array holding the chosen quality, so the caller re-encodes once rather
     *         than this method holding several megabytes of candidates alive
     */
    private static int[] searchQuality(Bitmap working, Options options, int maxQuality) {
        int[] reference = luminance(working);
        if (reference == null) return new int[] { maxQuality };

        // If even the top of the range cannot meet the floor, there is nothing to search for.
        double bestPsnr = measurePSNR(encode(working, maxQuality), working, reference);
        if (bestPsnr < options.minPSNR) return new int[] { maxQuality };

        int low = options.minQuality;
        int high = maxQuality;
        int best = maxQuality;

        for (int i = 0; i < QUALITY_SEARCH_STEPS && low < high; i++) {
            int mid = (low + high) / 2;
            if (mid <= low) break;
            double psnr = measurePSNR(encode(working, mid), working, reference);
            if (psnr >= options.minPSNR) {
                best = mid;         // good enough — try smaller
                high = mid;
            } else {
                low = mid;          // too lossy — back off
            }
        }
        return new int[] { best };
    }

    /**
     * PSNR on luminance. Chroma subsampling makes the colour planes a poor guide to how a JPEG
     * looks, and luminance is what carries the features a face matcher reads.
     */
    private static double measurePSNR(byte[] jpeg, Bitmap reference, int[] referenceLuma) {
        Bitmap decoded = null;
        try {
            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (decoded == null
                    || decoded.getWidth() != reference.getWidth()
                    || decoded.getHeight() != reference.getHeight()) {
                return Double.NEGATIVE_INFINITY;
            }
            int[] candidate = luminance(decoded);
            if (candidate == null || candidate.length != referenceLuma.length) {
                return Double.NEGATIVE_INFINITY;
            }
            double squaredError = 0;
            for (int i = 0; i < referenceLuma.length; i++) {
                double d = referenceLuma[i] - candidate[i];
                squaredError += d * d;
            }
            double mse = squaredError / referenceLuma.length;
            if (mse <= 0) return Double.POSITIVE_INFINITY;
            return 10 * Math.log10(255.0 * 255.0 / mse);
        } catch (Throwable t) {
            // A measurement failure must not fail the compression; fall back to accepting quality.
            return Double.NEGATIVE_INFINITY;
        } finally {
            if (decoded != null) decoded.recycle();
        }
    }

    /** BT.601 luma, the same weighting a JPEG encoder uses for its Y plane. */
    private static int[] luminance(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] luma = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            luma[i] = (299 * r + 587 * g + 114 * b) / 1000;
        }
        return luma;
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
