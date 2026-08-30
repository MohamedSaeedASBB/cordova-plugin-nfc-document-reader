package com.nfcdocumentreader;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * On-device 1:1 face verification: chip portrait (DG2) against the liveness portrait.
 *
 * WHY A MODEL FILE IS NEEDED
 * ML Kit provides face detection only — it has no recognition or embedding API, and iOS exposes
 * no public face-recognition API either. On-device verification therefore runs a face-embedding
 * model through TensorFlow Lite: each face is mapped to a vector, and the two vectors are
 * compared by cosine similarity against a decision threshold.
 *
 * The model is NOT bundled with this plugin. The bank supplies it, because the choice of model
 * is a biometric-governance decision, as is the threshold the backend applies to the scores it
 * produces: that threshold fixes the false-accept and false-reject rates of an identity check,
 * and those rates have to be measured on a representative population and signed off.
 *
 * The defaults here target a MobileFaceNet-family model: 112x112 input, 192-dimension embedding,
 * (x - 127.5) / 128 preprocessing. A different model family needs matching inputSize,
 * embeddingSize and — if it was trained with different normalisation — a change to the two
 * PIXEL_ constants below.
 *
 * Wiring a model in:
 *   1. Place the .tflite file in the app's assets (tools/install-face-model.sh does this).
 *   2. Optionally pass {@code faceMatch: { modelAsset, inputSize, embeddingSize }} to readNFC.
 *   3. Derive the backend's threshold before deciding on scores in production: run pairs through
 *      tools/face-match-calibration over a labelled set representative of the customer population
 *      and the capture conditions (chip portraits are often low-resolution and years old), sweep
 *      the cosine-similarity threshold, and pick the operating point that meets the bank's
 *      false-accept target. Record the resulting FAR/FRR — those numbers, not the threshold
 *      alone, are what a reviewer needs.
 *
 * This class has no threshold and no way to accept one. It measures the similarity between two
 * faces and reports it; the backend decides what the number means. The decision boundary belongs
 * where it can be changed, audited and calibrated without shipping an app release, and where a
 * handset cannot be persuaded to lower it. tools/face-match-calibration produces the FAR/FRR that
 * justifies whatever value the backend holds.
 *
 * Until a model is installed, the match is reported as deferred ("MODEL_NOT_INSTALLED")
 * rather than silently passing — an un-provisioned matcher is not a failed one. A model the
 * caller asked for by name but that is absent from the bundle is an error ("MODEL_NOT_FOUND").
 * FaceMatcher.swift mirrors this class.
 */
public class FaceMatcher {

    private static final String TAG = "FaceMatcher";

    /** Standard preprocessing for MobileFaceNet/FaceNet-family models. */
    private static final float PIXEL_MEAN = 127.5f;
    private static final float PIXEL_SCALE = 128.0f;

    /** Default asset name, auto-installed by plugin.xml. See src/models/README.md. */
    public static final String DEFAULT_MODEL_ASSET = "mobilefacenet.tflite";


    public static class Config {
        /** Path of the .tflite model within the app's assets. Null disables on-device matching. */
        public String modelAsset = DEFAULT_MODEL_ASSET;
        /**
         * True when the caller passed {@code faceMatch.modelAsset} itself, false when
         * {@link #modelAsset} is still the plugin default. A missing file means different things
         * in the two cases — see {@link FaceMatcher#match}.
         */
        public boolean modelAssetExplicit = false;
        /** Square input edge the model expects (112 for MobileFaceNet, 160 for FaceNet). */
        public int inputSize = 112;
        /** Embedding length the model emits (192 for MobileFaceNet, 128/512 for FaceNet). */
        public int embeddingSize = 192;
        /** Crop padding applied around the detected face before embedding. */
        public float cropPadding = 0.25f;
    }

    public static class MatchResult {
        /** "review" when a score was produced, otherwise "deferred" or "error". */
        public String status;
        /** Cosine similarity in [-1, 1], or null when no comparison ran. */
        public Double similarity;
        public String reason;
    }

    private final Config config;
    private final Context context;

    public FaceMatcher(Context context, Config config) {
        this.context = context;
        this.config = config;
    }

    /** A model is all that is needed: the comparison produces a score, never a verdict. */
    public boolean isConfigured() {
        return config != null
                && config.modelAsset != null
                && !config.modelAsset.isEmpty();
    }

    /**
     * Compares two already-detected faces.
     *
     * @param documentPortrait DG2 portrait from the chip
     * @param documentFaceBox  face bounds within {@code documentPortrait}
     * @param livenessPortrait captured liveness portrait
     * @param livenessFaceBox  face bounds within {@code livenessPortrait}
     */
    public MatchResult match(Bitmap documentPortrait, Rect documentFaceBox,
                             Bitmap livenessPortrait, Rect livenessFaceBox) {
        MatchResult result = new MatchResult();

        if (!isConfigured()) {
            result.status = "deferred";
            result.reason = "NO_MODEL_CONFIGURED";
            return result;
        }
        if (documentPortrait == null || livenessPortrait == null) {
            result.status = "error";
            result.reason = "MISSING_PORTRAIT";
            return result;
        }
        String resolvedModelPath = resolveAssetPath(config.modelAsset);
        if (resolvedModelPath == null) {
            // Two different situations, and reporting both as "error" sends people debugging
            // inference that never ran:
            //
            //   default asset absent  - the bank has not installed a model yet. Nothing is
            //                           broken; on-device matching simply is not provisioned,
            //                           which is exactly what "deferred" means. Installing the
            //                           model is a governance step (src/models/README.md), so a
            //                           build without it is an expected state, not a fault.
            //   explicit asset absent - the app asked for a specific model and it is not in the
            //                           bundle. That is a real misconfiguration.
            //
            // Neither is ever reported as a pass.
            if (!config.modelAssetExplicit) {
                Log.i(TAG, "No face match model installed. Searched: "
                        + java.util.Arrays.toString(candidatePaths(config.modelAsset))
                        + "; reporting the match as deferred."
                        + " See src/models/README.md to install one.");
                result.status = "deferred";
                result.reason = "MODEL_NOT_INSTALLED";
                return result;
            }
            Log.w(TAG, "Configured face match model not found. Searched: "
                    + java.util.Arrays.toString(candidatePaths(config.modelAsset))
                    + " — see src/models/README.md");
            result.status = "error";
            result.reason = "MODEL_NOT_FOUND";
            return result;
        }

        Interpreter interpreter = null;
        try {
            interpreter = new Interpreter(loadModel(resolvedModelPath));

            float[] documentEmbedding = embed(interpreter, documentPortrait, documentFaceBox);
            float[] livenessEmbedding = embed(interpreter, livenessPortrait, livenessFaceBox);

            double similarity = cosineSimilarity(documentEmbedding, livenessEmbedding);
            result.similarity = round(similarity);
            // The device measures; it does not decide. There is no threshold here to compare
            // against, so the score is returned as-is for the backend to judge.
            result.status = "review";

            // Score only — never log the embeddings or the images themselves.
            Log.d(TAG, "On-device face match: similarity=" + result.similarity);
            return result;
        } catch (EmbeddingLengthException e) {
            // Almost always a config error rather than a broken model: the .tflite emits a
            // different vector length than embeddingSize claims.
            Log.e(TAG, "Face match model output does not match configuration: " + e.getMessage());
            result.status = "error";
            result.reason = "EMBEDDING_LENGTH_MISMATCH";
            return result;
        } catch (Exception e) {
            // A matcher failure is never reported as a pass. Log the exception type as well as
            // the message — a bare getMessage() is often null and says nothing.
            Log.e(TAG, "On-device face match failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            result.status = "error";
            result.reason = "MATCHER_FAILED";
            return result;
        } finally {
            if (interpreter != null) {
                interpreter.close();
            }
        }
    }

    // ==================== Inference ====================

    private float[] embed(Interpreter interpreter, Bitmap portrait, Rect faceBox) {
        Bitmap face = cropAndResize(portrait, faceBox, config.inputSize);
        try {
            ByteBuffer input = toNormalisedBuffer(face, config.inputSize);
            float[][] output = new float[1][config.embeddingSize];
            interpreter.run(input, output);
            return l2Normalise(output[0]);
        } finally {
            if (face != portrait) {
                face.recycle();
            }
        }
    }

    private Bitmap cropAndResize(Bitmap source, Rect faceBox, int inputSize) {
        Bitmap cropped = source;
        if (faceBox != null) {
            int padX = (int) (faceBox.width() * config.cropPadding);
            int padY = (int) (faceBox.height() * config.cropPadding);
            int left = Math.max(0, faceBox.left - padX);
            int top = Math.max(0, faceBox.top - padY);
            int right = Math.min(source.getWidth(), faceBox.right + padX);
            int bottom = Math.min(source.getHeight(), faceBox.bottom + padY);
            if (right - left > 0 && bottom - top > 0) {
                cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
            }
        }

        Bitmap resized = Bitmap.createScaledBitmap(cropped, inputSize, inputSize, true);
        if (cropped != source && cropped != resized) {
            cropped.recycle();
        }
        return resized;
    }

    private static ByteBuffer toNormalisedBuffer(Bitmap face, int inputSize) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[inputSize * inputSize];
        face.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);

        for (int pixel : pixels) {
            buffer.putFloat((((pixel >> 16) & 0xFF) - PIXEL_MEAN) / PIXEL_SCALE);
            buffer.putFloat((((pixel >> 8) & 0xFF) - PIXEL_MEAN) / PIXEL_SCALE);
            buffer.putFloat(((pixel & 0xFF) - PIXEL_MEAN) / PIXEL_SCALE);
        }

        buffer.rewind();
        return buffer;
    }

    /**
     * Where a host build may stage the model. A bare name is the documented location, but Cordova
     * also stages web assets under www/, and a wrong resource-file target silently puts the file
     * nowhere at all — which is exactly how this shipped once, reporting MODEL_NOT_INSTALLED with
     * the model sitting in the repository. Trying the plausible locations and logging what was
     * tried makes the next such mistake self-evident instead of invisible.
     */
    private String[] candidatePaths(String assetPath) {
        String name = assetPath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return new String[] { assetPath, "www/" + name, "public/" + name, name };
    }

    /** First asset path that actually opens, or null. */
    private String resolveAssetPath(String assetPath) {
        for (String candidate : candidatePaths(assetPath)) {
            try (InputStream in = context.getAssets().open(candidate)) {
                if (!candidate.equals(assetPath)) {
                    Log.i(TAG, "Face match model found at " + candidate
                            + " rather than " + assetPath);
                }
                return candidate;
            } catch (Exception ignored) {
                // Try the next location.
            }
        }
        return null;
    }

    /**
     * Loads the model from assets.
     *
     * Prefers memory-mapping, which keeps the model out of the Java heap. That only works for
     * uncompressed assets, and aapt compresses unknown extensions by default — so rather than
     * requiring the app to add {@code aaptOptions { noCompress "tflite" }} to its Gradle build,
     * we fall back to reading the stream into a direct buffer. Same result, more heap traffic.
     */
    private ByteBuffer loadModel(String assetPath) throws Exception {
        try {
            AssetFileDescriptor descriptor = context.getAssets().openFd(assetPath);
            try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
                FileChannel channel = stream.getChannel();
                MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY,
                        descriptor.getStartOffset(), descriptor.getDeclaredLength());
                Log.d(TAG, "Face match model memory-mapped from assets: " + assetPath);
                return mapped;
            } finally {
                descriptor.close();
            }
        } catch (IOException mapFailed) {
            Log.d(TAG, "Asset is compressed, falling back to a buffered read: " + assetPath);
            return readAssetIntoBuffer(assetPath);
        }
    }

    private ByteBuffer readAssetIntoBuffer(String assetPath) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open(assetPath)) {
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
        }

        byte[] bytes = out.toByteArray();
        // TFLite requires a direct, native-order buffer.
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.order(ByteOrder.nativeOrder());
        buffer.put(bytes);
        buffer.rewind();
        return buffer;
    }

    // ==================== Vector maths ====================

    private static float[] l2Normalise(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return vector;
        }
        float[] normalised = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalised[i] = (float) (vector[i] / norm);
        }
        return normalised;
    }

    /** Raised when the model's actual output length disagrees with {@code embeddingSize}. */
    private static class EmbeddingLengthException extends RuntimeException {
        EmbeddingLengthException(String message) {
            super(message);
        }
    }

    /** Both vectors are L2-normalised, so this is a plain dot product. */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new EmbeddingLengthException(
                    "Embedding length mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
