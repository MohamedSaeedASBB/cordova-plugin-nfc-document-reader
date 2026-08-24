package com.nfcdocumentreader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pairs the chip portrait (DG2) with the liveness portrait and screens the pair before a
 * biometric 1:1 match is attempted.
 *
 * SCOPE — READ THIS BEFORE CHANGING ANYTHING HERE:
 * ML Kit ships face *detection* and face mesh. It has no face recognition or embedding API, so
 * there is no ML Kit call that answers "are these two images the same person". Nothing in this
 * class attempts to answer that question, and `screening.passed` must never be presented to a
 * user or an approver as an identity match.
 *
 * What this class does do, all of which ML Kit genuinely supports:
 *   1. Confirms the chip portrait contains exactly one detectable, reasonably frontal face.
 *   2. Confirms the same of the liveness portrait.
 *   3. Emits per-image quality signals a matcher needs to interpret its own score.
 *   4. Produces the aligned, compressed pair for whichever matcher does the real comparison.
 *
 * The authoritative decision comes from {@code match}, which is populated by the configured
 * matcher (see FaceMatchMode) — by default deferred to the back office.
 */
public class FaceComparison {

    private static final String TAG = "FaceComparison";

    /** ML Kit detection on a single still; generous but bounded so a bad image cannot hang a read. */
    private static final long DETECT_TIMEOUT_SECONDS = 10L;

    /** A face smaller than this fraction of the image is too small to match reliably. */
    private static final float MIN_FACE_AREA_RATIO = 0.03f;
    /** Beyond these angles a portrait is too off-axis for a dependable 1:1 comparison. */
    private static final float MAX_ABS_YAW = 22f;
    private static final float MAX_ABS_PITCH = 22f;
    private static final float MAX_ABS_ROLL = 22f;

    /** Per-image detection outcome plus the quality signals a matcher needs. */
    public static class FaceAnalysis {
        public boolean detected;
        public int faceCount;
        public float areaRatio;
        public float yaw;
        public float pitch;
        public float roll;
        public int imageWidth;
        public int imageHeight;
        public Rect box;

        public boolean isFrontal() {
            return Math.abs(yaw) <= MAX_ABS_YAW
                    && Math.abs(pitch) <= MAX_ABS_PITCH
                    && Math.abs(roll) <= MAX_ABS_ROLL;
        }

        public boolean isLargeEnough() {
            return areaRatio >= MIN_FACE_AREA_RATIO;
        }

        JSONObject toJSON() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("faceDetected", detected);
            json.put("faceCount", faceCount);
            if (detected) {
                json.put("faceAreaRatio", round(areaRatio));
                json.put("yaw", round(yaw));
                json.put("pitch", round(pitch));
                json.put("roll", round(roll));
                json.put("frontal", isFrontal());
                json.put("largeEnough", isLargeEnough());
            }
            json.put("imageWidth", imageWidth);
            json.put("imageHeight", imageHeight);
            return json;
        }
    }

    /**
     * Detection results plus the decoded pair, so the matcher does not repeat the work.
     * The caller owns {@link #livenessPortrait} and must recycle it.
     */
    public static class Outcome {
        public JSONObject json;
        public Rect documentFaceBox;
        public Bitmap livenessPortrait;
        public Rect livenessFaceBox;
    }

    /**
     * Runs detection over both portraits and assembles the comparison block.
     *
     * @param documentPortrait  the DG2 portrait decoded from the chip
     * @param livenessFaceBase64 the compressed liveness crop produced by LivenessCameraActivity
     * @param imageOptions      compression settings for the document crop that goes to the matcher
     */
    public static Outcome compare(Bitmap documentPortrait,
                                  String livenessFaceBase64,
                                  ImageCompressor.Options imageOptions) throws JSONException {
        JSONObject comparison = new JSONObject();
        Outcome outcome = new Outcome();
        outcome.json = comparison;

        FaceDetector detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                // Stills, not a video stream: ACCURATE is the right trade here.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(0.10f)
                .build());

        try {
            FaceAnalysis documentAnalysis = analyse(detector, documentPortrait);

            Bitmap livenessPortrait = decodeBase64(livenessFaceBase64);
            FaceAnalysis livenessAnalysis = analyse(detector, livenessPortrait);

            outcome.documentFaceBox = documentAnalysis.box;
            outcome.livenessPortrait = livenessPortrait;
            outcome.livenessFaceBox = livenessAnalysis.box;

            comparison.put("documentPortrait", documentAnalysis.toJSON());
            comparison.put("livenessPortrait", livenessAnalysis.toJSON());

            // ---- Screening: a pre-flight gate, NOT an identity decision ----
            List<String> reasons = new ArrayList<>();
            if (!documentAnalysis.detected) {
                reasons.add("NO_FACE_IN_DOCUMENT_PORTRAIT");
            } else {
                if (documentAnalysis.faceCount > 1) reasons.add("MULTIPLE_FACES_IN_DOCUMENT_PORTRAIT");
                if (!documentAnalysis.isLargeEnough()) reasons.add("DOCUMENT_FACE_TOO_SMALL");
                if (!documentAnalysis.isFrontal()) reasons.add("DOCUMENT_FACE_NOT_FRONTAL");
            }
            if (!livenessAnalysis.detected) {
                reasons.add("NO_FACE_IN_LIVENESS_PORTRAIT");
            } else {
                if (livenessAnalysis.faceCount > 1) reasons.add("MULTIPLE_FACES_IN_LIVENESS_PORTRAIT");
                if (!livenessAnalysis.isLargeEnough()) reasons.add("LIVENESS_FACE_TOO_SMALL");
                if (!livenessAnalysis.isFrontal()) reasons.add("LIVENESS_FACE_NOT_FRONTAL");
            }

            JSONObject screening = new JSONObject();
            screening.put("passed", reasons.isEmpty());
            screening.put("reasons", new JSONArray(reasons));
            // Spelled out in the payload so no downstream consumer can mistake this for a match.
            screening.put("note", "Both images contain one usable frontal face. This is a quality "
                    + "gate only and is NOT a biometric identity match.");
            comparison.put("screening", screening);

            // ---- The two detected faces, exactly as the matcher consumed them ----
            // Both sides are returned, not just the document side: a reviewer deciding a
            // borderline score needs to see the same pair the score came from, and the metrics
            // above describe images nobody can look at otherwise.
            if (documentAnalysis.detected) {
                ImageCompressor.Result documentCrop =
                        ImageCompressor.compress(documentPortrait, documentAnalysis.box, imageOptions);
                comparison.put("documentFaceImageBase64", documentCrop.toBase64());
                comparison.put("documentFaceImageBytes", documentCrop.jpeg.length);
                comparison.put("documentFaceImageWidth", documentCrop.width);
                comparison.put("documentFaceImageHeight", documentCrop.height);
            }
            if (livenessAnalysis.detected) {
                ImageCompressor.Result livenessCrop =
                        ImageCompressor.compress(livenessPortrait, livenessAnalysis.box, imageOptions);
                comparison.put("livenessFaceImageBase64", livenessCrop.toBase64());
                comparison.put("livenessFaceImageBytes", livenessCrop.jpeg.length);
                comparison.put("livenessFaceImageWidth", livenessCrop.width);
                comparison.put("livenessFaceImageHeight", livenessCrop.height);
            }

            return outcome;
        } finally {
            detector.close();
        }
    }

    // ==================== Detection ====================

    private static FaceAnalysis analyse(FaceDetector detector, Bitmap bitmap) {
        FaceAnalysis analysis = new FaceAnalysis();
        if (bitmap == null) {
            return analysis;
        }

        analysis.imageWidth = bitmap.getWidth();
        analysis.imageHeight = bitmap.getHeight();

        try {
            // Called from a background thread (Cordova's pool), so awaiting is safe here.
            List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)),
                    DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            analysis.faceCount = faces != null ? faces.size() : 0;
            if (faces == null || faces.isEmpty()) {
                return analysis;
            }

            Face largest = null;
            int largestArea = -1;
            for (Face face : faces) {
                Rect box = face.getBoundingBox();
                int area = box.width() * box.height();
                if (area > largestArea) {
                    largestArea = area;
                    largest = face;
                }
            }

            Rect box = largest.getBoundingBox();
            analysis.detected = true;
            analysis.box = new Rect(box);
            analysis.areaRatio = (box.width() * (float) box.height())
                    / ((float) analysis.imageWidth * analysis.imageHeight);
            analysis.yaw = largest.getHeadEulerAngleY();
            analysis.pitch = largest.getHeadEulerAngleX();
            analysis.roll = largest.getHeadEulerAngleZ();
        } catch (Exception e) {
            // Detection failure is reported as "no face", never as a pass.
            Log.w(TAG, "Face detection on still image failed: " + e.getMessage());
        }

        return analysis;
    }

    private static Bitmap decodeBase64(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.w(TAG, "Could not decode liveness portrait: " + e.getMessage());
            return null;
        }
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
