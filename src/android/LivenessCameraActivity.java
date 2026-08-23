package com.nfcdocumentreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Front-camera liveness check: CameraX preview plus ML Kit face detection, driving the
 * challenge-response sequence in {@link LivenessDetector}.
 *
 * The portrait handed back is captured from the same analysed frame stream that satisfied the
 * challenges — not from a separate still capture afterwards — and is compressed by
 * {@link ImageCompressor} before it crosses into the WebView.
 */
public class LivenessCameraActivity extends AppCompatActivity {

    private static final String TAG = "LivenessCamera";
    private static final int REQUEST_CAMERA_PERMISSION = 1002;

    public static final String EXTRA_OPTIONS = "optionsJson";

    /**
     * Result channel.
     *
     * The result carries base64 JPEGs, which routinely exceed the ~1 MB Binder transaction
     * limit and would throw TransactionTooLargeException as Intent extras. It is held in
     * memory (not written to disk) and cleared on read, so the biometric payload has the
     * shortest lifetime we can give it.
     */
    private static JSONObject pendingResult;

    private static synchronized void publishResult(JSONObject result) {
        pendingResult = result;
    }

    static synchronized JSONObject consumeResult() {
        JSONObject result = pendingResult;
        pendingResult = null;
        return result;
    }

    static synchronized void clearResult() {
        pendingResult = null;
    }

    // ==================== Views ====================

    private PreviewView cameraPreview;
    private android.view.View faceGuideOval;
    private TextView stepText;
    private TextView promptText;
    private TextView hintText;
    private Button cancelButton;
    private ImageButton closeButton;

    // ==================== Session ====================

    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private LivenessDetector detector;
    private LivenessOptions options;
    private volatile boolean finished = false;

    /** Best neutral frame so far, already rotated upright, plus its face box. */
    private Bitmap portraitFrame;
    private Rect portraitFaceBox;

    /** Optional per-challenge evidence frames. */
    private final List<CapturedFrame> challengeFrames = new ArrayList<>();

    private static class CapturedFrame {
        final LivenessDetector.Challenge challenge;
        final Bitmap bitmap;
        final Rect faceBox;

        CapturedFrame(LivenessDetector.Challenge challenge, Bitmap bitmap, Rect faceBox) {
            this.challenge = challenge;
            this.bitmap = bitmap;
            this.faceBox = faceBox;
        }
    }

    // ==================== Lifecycle ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(getResources().getIdentifier("activity_liveness_camera", "layout", getPackageName()));

        cameraPreview = findViewById(getResId("cameraPreview"));
        faceGuideOval = findViewById(getResId("faceGuideOval"));
        stepText = findViewById(getResId("stepText"));
        promptText = findViewById(getResId("promptText"));
        hintText = findViewById(getResId("hintText"));
        cancelButton = findViewById(getResId("cancelButton"));
        closeButton = findViewById(getResId("closeButton"));

        options = LivenessOptions.fromJson(getIntent().getStringExtra(EXTRA_OPTIONS));

        LivenessDetector.Config config = new LivenessDetector.Config();
        config.challenges = options.challenges;
        config.overallTimeoutMs = options.overallTimeoutMs;
        config.perChallengeTimeoutMs = options.perChallengeTimeoutMs;
        config.faceSearchTimeoutMs = options.faceSearchTimeoutMs;
        detector = new LivenessDetector(config);

        cameraExecutor = Executors.newSingleThreadExecutor();
        faceDetector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                // FAST keeps the frame rate high enough to catch a blink; ACCURATE is for
                // stills and would drop the transition frames this check depends on.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(0.15f)
                // Tracking ids let us detect the face being swapped mid-session.
                .enableTracking()
                .build());

        setGuideOvalBorder(Color.WHITE);
        updatePrompt(LivenessDetector.State.WAITING_FOR_FACE, null, "findFace");

        closeButton.setOnClickListener(v -> cancel("Liveness check cancelled"));
        cancelButton.setOnClickListener(v -> cancel("Liveness check cancelled"));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required for the liveness check",
                        Toast.LENGTH_LONG).show();
                cancel("Camera permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (faceDetector != null) {
            faceDetector.close();
        }
        releaseFrames();
    }

    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    // ==================== Camera ====================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (finished) {
                        imageProxy.close();
                        return;
                    }
                    processFrame(imageProxy);
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed: " + e.getMessage());
                fail("CAMERA_UNAVAILABLE", "Could not start the front camera.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ==================== Frame processing ====================

    @SuppressWarnings("UnsafeOptInUsageError")
    private void processFrame(ImageProxy imageProxy) {
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees);

        // ML Kit reports face coordinates in the *rotated* image space, so normalise against
        // the rotated dimensions and rotate any captured bitmap by the same amount.
        boolean swapAxes = rotationDegrees == 90 || rotationDegrees == 270;
        int frameWidth = swapAxes ? imageProxy.getHeight() : imageProxy.getWidth();
        int frameHeight = swapAxes ? imageProxy.getWidth() : imageProxy.getHeight();

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    if (finished) return;

                    Face face = largestFace(faces);
                    LivenessDetector.Observation obs =
                            toObservation(faces.size(), face, frameWidth, frameHeight);
                    LivenessDetector.Update update = detector.onFrame(obs);

                    // The ImageProxy is still open here (it is closed in the completion
                    // listener below), so the verified frame is available to capture.
                    handleUpdate(update, imageProxy, rotationDegrees, face);
                })
                .addOnFailureListener(e -> Log.e(TAG, "Face detection error: " + e.getMessage()))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private static Face largestFace(List<Face> faces) {
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
        return largest;
    }

    private static LivenessDetector.Observation toObservation(int faceCount, @Nullable Face face,
                                                              int frameWidth, int frameHeight) {
        LivenessDetector.Observation obs = new LivenessDetector.Observation();
        // Monotonic clock: wall-clock adjustments must not corrupt challenge timings.
        obs.timestampMs = SystemClock.elapsedRealtime();
        obs.faceCount = faceCount;

        if (face == null || frameWidth <= 0 || frameHeight <= 0) {
            return obs;
        }

        Rect box = face.getBoundingBox();
        float frameArea = (float) frameWidth * frameHeight;
        obs.areaRatio = (box.width() * (float) box.height()) / frameArea;
        obs.centerOffsetX = (box.exactCenterX() - frameWidth / 2f) / frameWidth;
        obs.centerOffsetY = (box.exactCenterY() - frameHeight / 2f) / frameHeight;
        obs.yaw = face.getHeadEulerAngleY();
        obs.pitch = face.getHeadEulerAngleX();
        obs.roll = face.getHeadEulerAngleZ();
        obs.leftEyeOpen = face.getLeftEyeOpenProbability();
        obs.rightEyeOpen = face.getRightEyeOpenProbability();
        obs.smiling = face.getSmilingProbability();
        obs.trackingId = face.getTrackingId();
        return obs;
    }

    private void handleUpdate(LivenessDetector.Update update, ImageProxy imageProxy,
                              int rotationDegrees, @Nullable Face face) {
        if (update.capturePortrait && face != null) {
            Bitmap frame = toUprightBitmap(imageProxy, rotationDegrees);
            if (frame != null) {
                if (portraitFrame != null) {
                    portraitFrame.recycle();
                }
                portraitFrame = frame;
                portraitFaceBox = new Rect(face.getBoundingBox());
            }
        }

        if (update.captureChallengeFrame != null && options.includeChallengeFrames && face != null) {
            Bitmap frame = toUprightBitmap(imageProxy, rotationDegrees);
            if (frame != null) {
                challengeFrames.add(new CapturedFrame(update.captureChallengeFrame, frame,
                        new Rect(face.getBoundingBox())));
            }
        }

        updatePrompt(update.state, update.challenge, update.promptKey);

        if (!update.finished) {
            return;
        }

        finished = true;
        if (update.passed) {
            setGuideOvalBorder(Color.parseColor("#4CAF50"));
            finishWithSuccess();
        } else {
            setGuideOvalBorder(Color.parseColor("#E53935"));
            fail(update.failureCode, failureMessage(update.failureCode));
        }
    }

    /**
     * Converts the analysed frame to an upright bitmap.
     *
     * Deliberately NOT mirrored: the preview is mirrored for the user's benefit, but the stored
     * portrait keeps the camera's true orientation so it matches the document portrait the back
     * office compares it against.
     */
    @Nullable
    private Bitmap toUprightBitmap(ImageProxy imageProxy, int rotationDegrees) {
        try {
            Bitmap raw = imageProxy.toBitmap();
            if (rotationDegrees == 0) {
                return raw;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            if (rotated != raw) {
                raw.recycle();
            }
            return rotated;
        } catch (Exception e) {
            // Non-fatal: skip this frame and let a later one supply the portrait.
            Log.w(TAG, "Frame conversion failed: " + e.getMessage());
            return null;
        }
    }

    // ==================== UI ====================

    private void setGuideOvalBorder(int color) {
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.OVAL);
        border.setStroke(6, color);
        border.setColor(Color.TRANSPARENT);
        faceGuideOval.setBackground(border);
    }

    private void updatePrompt(LivenessDetector.State state,
                              @Nullable LivenessDetector.Challenge challenge,
                              String promptKey) {
        final String prompt = options.prompt(promptKey);
        final String step = stepLabel(state, challenge);
        final boolean framed = state == LivenessDetector.State.CHALLENGE
                || state == LivenessDetector.State.CAPTURING;

        runOnUiThread(() -> {
            promptText.setText(prompt);
            stepText.setText(step);
            hintText.setText(options.prompt("hint"));
            if (!finished) {
                setGuideOvalBorder(framed ? Color.parseColor("#64B5F6") : Color.WHITE);
            }
        });
    }

    private String stepLabel(LivenessDetector.State state,
                             @Nullable LivenessDetector.Challenge challenge) {
        if (state != LivenessDetector.State.CHALLENGE || challenge == null) {
            return "";
        }
        List<LivenessDetector.Challenge> order = detector.getChallengeOrder();
        int index = order.indexOf(challenge) + 1;
        return "Step " + index + " of " + order.size();
    }

    // ==================== Completion ====================

    private void finishWithSuccess() {
        // Compression and base64 encoding are done off the main thread; a few hundred
        // kilobytes of JPEG encoding is enough to drop frames if run on the UI thread.
        cameraExecutor.execute(() -> {
            JSONObject result;
            try {
                result = buildResult();
            } catch (JSONException e) {
                Log.e(TAG, "Error building liveness result: " + e.getMessage());
                runOnUiThread(() -> fail("RESULT_ENCODING_FAILED",
                        "The liveness check completed but its result could not be encoded."));
                return;
            }

            publishResult(result);
            releaseFrames();

            runOnUiThread(() -> {
                setResult(Activity.RESULT_OK, new Intent());
                finish();
            });
        });
    }

    private JSONObject buildResult() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("passed", true);
        result.put("capturedAt", isoTimestamp());

        ImageCompressor.Options imageOptions = options.imageOptions();

        if (portraitFrame != null) {
            ImageCompressor.Result portrait =
                    ImageCompressor.compress(portraitFrame, portraitFaceBox, imageOptions);
            result.put("faceImageBase64", portrait.toBase64());
            result.put("faceImageMimeType", "image/jpeg");
            result.put("faceImageWidth", portrait.width);
            result.put("faceImageHeight", portrait.height);
            result.put("faceImageBytes", portrait.jpeg.length);
            result.put("faceImageJpegQuality", portrait.quality);

            if (options.includeFullFrame) {
                ImageCompressor.Options fullFrameOptions = options.imageOptions();
                fullFrameOptions.cropToFace = false;
                ImageCompressor.Result fullFrame =
                        ImageCompressor.compress(portraitFrame, null, fullFrameOptions);
                result.put("fullFrameImageBase64", fullFrame.toBase64());
                result.put("fullFrameImageBytes", fullFrame.jpeg.length);
            }
        } else {
            result.put("faceImageBase64", JSONObject.NULL);
        }

        // ---- Per-challenge outcomes, for back-office scoring ----
        JSONArray challenges = new JSONArray();
        for (LivenessDetector.ChallengeResult r : detector.getResults()) {
            JSONObject entry = new JSONObject();
            entry.put("type", challengeName(r.type));
            entry.put("passed", r.passed);
            entry.put("durationMs", r.durationMs);
            challenges.put(entry);
        }
        result.put("challenges", challenges);

        if (options.includeChallengeFrames && !challengeFrames.isEmpty()) {
            JSONArray frames = new JSONArray();
            for (CapturedFrame captured : challengeFrames) {
                ImageCompressor.Result compressed =
                        ImageCompressor.compress(captured.bitmap, captured.faceBox, imageOptions);
                JSONObject entry = new JSONObject();
                entry.put("challenge", challengeName(captured.challenge));
                entry.put("imageBase64", compressed.toBase64());
                entry.put("imageBytes", compressed.jpeg.length);
                frames.put(entry);
            }
            result.put("challengeFrames", frames);
        }

        // ---- Session signals: no image data, safe to log or forward for scoring ----
        JSONObject signals = new JSONObject();
        signals.put("framesAnalysed", detector.getFramesAnalysed());
        signals.put("durationMs", detector.getElapsedMs(SystemClock.elapsedRealtime()));
        signals.put("multiFaceFrames", detector.getMultiFaceFrames());
        signals.put("trackingIdChanges", detector.getTrackingIdChanges());
        result.put("signals", signals);

        JSONObject sdk = new JSONObject();
        sdk.put("provider", "mlkit");
        sdk.put("feature", "face-detection");
        sdk.put("platform", "android");
        // ML Kit face detection provides no presentation-attack detection. This flag tells the
        // back office not to treat `passed` as proof of a live human.
        sdk.put("presentationAttackDetection", false);
        result.put("sdk", sdk);

        return result;
    }

    private void fail(String code, String message) {
        finished = true;
        clearResult();
        releaseFrames();

        Intent data = new Intent();
        data.putExtra("errorCode", code != null ? code : "UNKNOWN");
        data.putExtra("error", message);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
    }

    private void cancel(String message) {
        fail("CANCELLED", message);
    }

    private void releaseFrames() {
        if (portraitFrame != null) {
            portraitFrame.recycle();
            portraitFrame = null;
        }
        portraitFaceBox = null;
        for (CapturedFrame captured : challengeFrames) {
            captured.bitmap.recycle();
        }
        challengeFrames.clear();
    }

    // ==================== Helpers ====================

    private String failureMessage(String code) {
        if (code == null) {
            return "The liveness check could not be completed. Please try again.";
        }
        switch (code) {
            case "NO_FACE_TIMEOUT":
                return "We could not see your face. Please try again in better lighting.";
            case "CHALLENGE_TIMEOUT":
                return "The on-screen instruction was not completed in time. Please try again.";
            case "MULTIPLE_FACES":
                return "More than one face was visible. Please make sure you are alone in frame.";
            case "FACE_CHANGED":
                return "The face in front of the camera changed during the check. Please try again.";
            case "OVERALL_TIMEOUT":
                return "The liveness check took too long. Please try again.";
            case "NO_USABLE_PORTRAIT":
                return "We could not capture a clear photo. Please try again in better lighting.";
            default:
                return "The liveness check could not be completed. Please try again.";
        }
    }

    static String challengeName(LivenessDetector.Challenge challenge) {
        switch (challenge) {
            case BLINK: return "blink";
            case SMILE: return "smile";
            case TURN_LEFT: return "turnLeft";
            case TURN_RIGHT: return "turnRight";
            default: return "unknown";
        }
    }

    private static String isoTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
