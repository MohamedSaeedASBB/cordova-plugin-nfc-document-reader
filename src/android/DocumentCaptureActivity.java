package com.nfcdocumentreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photographs a physical document, one step at a time.
 *
 * Distinct from MrzCameraActivity, which reads the machine-readable zone to derive chip keys and
 * throws the frame away. This captures the picture itself: an ID card needs both sides, a passport
 * needs only its data page, and a proof of address is a single page of something with no fixed
 * shape at all. The steps are supplied by the caller, so all three are the same activity.
 *
 * Each step is shot, reviewed, and either kept or retaken before moving on. Review matters more
 * here than in the MRZ flow: nothing downstream can tell the operator that the photo they took is
 * too blurry to read, so the person holding the phone has to see it while they can still redo it.
 */
public class DocumentCaptureActivity extends AppCompatActivity {

    private static final String TAG = "DocumentCapture";
    private static final int CAMERA_PERMISSION_REQUEST = 20;

    public static final String EXTRA_OPTIONS = "captureOptionsJson";

    /** Handed back through a static rather than the Intent: these images exceed the Binder limit. */
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

    private PreviewView cameraPreview;
    private ImageView reviewImage;
    private TextView titleText, stepText, hintText;
    private Button captureButton, retakeButton, useButton, cancelButton;
    private ImageButton closeButton;
    private LinearLayout reviewButtons;
    private View captureGuideFrame;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;

    private DocumentCaptureOptions options;
    private final List<JSONObject> captured = new ArrayList<>();
    private int stepIndex = 0;
    private Bitmap pendingBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier(
                "activity_document_capture", "layout", getPackageName()));

        cameraPreview = findViewById(getResId("cameraPreview"));
        reviewImage = findViewById(getResId("reviewImage"));
        titleText = findViewById(getResId("titleText"));
        stepText = findViewById(getResId("stepText"));
        hintText = findViewById(getResId("hintText"));
        captureButton = findViewById(getResId("captureButton"));
        retakeButton = findViewById(getResId("retakeButton"));
        useButton = findViewById(getResId("useButton"));
        cancelButton = findViewById(getResId("cancelButton"));
        closeButton = findViewById(getResId("closeButton"));
        reviewButtons = findViewById(getResId("reviewButtons"));
        captureGuideFrame = findViewById(getResId("captureGuideFrame"));

        SystemBarInsets.apply(findViewById(android.R.id.content),
                findViewById(getResId("topBar")), findViewById(getResId("bottomPanel")));

        options = DocumentCaptureOptions.fromJson(getIntent().getStringExtra(EXTRA_OPTIONS));
        titleText.setText(options.title);
        setGuideFrameBorder();

        cameraExecutor = Executors.newSingleThreadExecutor();
        if (options.runOcr) {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }

        captureButton.setOnClickListener(v -> takePhoto());
        retakeButton.setOnClickListener(v -> showCamera());
        useButton.setOnClickListener(v -> keepPhoto());
        cancelButton.setOnClickListener(v -> cancel());
        closeButton.setOnClickListener(v -> cancel());

        showStep();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is required to photograph the document.",
                    Toast.LENGTH_LONG).show();
            cancel();
        }
    }

    // ==================== Camera ====================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                // Documents are read, not glanced at: quality over latency.
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);
            } catch (Exception e) {
                Log.e(TAG, "Camera could not be started: " + e.getMessage(), e);
                Toast.makeText(this, "The camera could not be started.", Toast.LENGTH_LONG).show();
                cancel();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        captureButton.setEnabled(false);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap = null;
                try {
                    bitmap = toBitmap(image);
                } catch (Exception e) {
                    Log.e(TAG, "Could not decode the captured frame: " + e.getMessage(), e);
                } finally {
                    image.close();
                }

                final Bitmap shot = bitmap;
                runOnUiThread(() -> {
                    captureButton.setEnabled(true);
                    if (shot == null) {
                        Toast.makeText(DocumentCaptureActivity.this,
                                "That photo could not be read. Please try again.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingBitmap = shot;
                    showReview(shot);
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                Log.e(TAG, "Capture failed: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    captureButton.setEnabled(true);
                    Toast.makeText(DocumentCaptureActivity.this,
                            "The photo could not be taken. Please try again.",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** JPEG bytes out of CameraX, rotated upright — the sensor orientation is not the page's. */
    private Bitmap toBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) return null;

        int rotation = image.getImageInfo().getRotationDegrees();
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    // ==================== Steps ====================

    private void showStep() {
        DocumentCaptureOptions.Step step = options.steps.get(stepIndex);
        hintText.setText(step.hint);
        stepText.setText(options.steps.size() > 1
                ? "Step " + (stepIndex + 1) + " of " + options.steps.size() + " — " + step.label
                : step.label);
        showCamera();
    }

    private void showCamera() {
        if (pendingBitmap != null) {
            pendingBitmap.recycle();
            pendingBitmap = null;
        }
        reviewImage.setVisibility(View.GONE);
        reviewImage.setImageDrawable(null);
        cameraPreview.setVisibility(View.VISIBLE);
        captureGuideFrame.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.VISIBLE);
        reviewButtons.setVisibility(View.GONE);
    }

    private void showReview(Bitmap bitmap) {
        reviewImage.setImageBitmap(bitmap);
        reviewImage.setVisibility(View.VISIBLE);
        cameraPreview.setVisibility(View.INVISIBLE);
        captureGuideFrame.setVisibility(View.GONE);
        captureButton.setVisibility(View.GONE);
        reviewButtons.setVisibility(View.VISIBLE);
    }

    private void keepPhoto() {
        if (pendingBitmap == null) return;
        useButton.setEnabled(false);

        final Bitmap bitmap = pendingBitmap;
        pendingBitmap = null;
        final DocumentCaptureOptions.Step step = options.steps.get(stepIndex);

        cameraExecutor.execute(() -> {
            JSONObject entry = new JSONObject();
            try {
                ImageCompressor.Result compressed =
                        ImageCompressor.compress(bitmap, null, options.imageOptions());
                entry.put("key", step.key);
                entry.put("label", step.label);
                entry.put("imageBase64", compressed.toBase64());
                entry.put("imageMimeType", "image/jpeg");
                entry.put("imageBytes", compressed.jpeg.length);
                entry.put("imageWidth", compressed.width);
                entry.put("imageHeight", compressed.height);
                entry.put("jpegQuality", compressed.quality);
                if (options.runOcr) {
                    entry.put("ocr", runOcr(bitmap));
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not prepare the captured image: " + e.getMessage(), e);
            } finally {
                bitmap.recycle();
            }

            runOnUiThread(() -> {
                useButton.setEnabled(true);
                if (!entry.has("imageBase64")) {
                    Toast.makeText(this, "That photo could not be saved. Please retake it.",
                            Toast.LENGTH_SHORT).show();
                    showCamera();
                    return;
                }
                captured.add(entry);
                stepIndex++;
                if (stepIndex < options.steps.size()) {
                    showStep();
                } else {
                    finishWithResult();
                }
            });
        });
    }

    /**
     * Recognised text for the page, blocking on this background thread by design — the user is
     * already waiting on the "Use photo" tap.
     *
     * LATIN SCRIPT ONLY on Android. ML Kit Text Recognition v2 ships separate models for Latin,
     * Chinese, Devanagari, Japanese and Korean, and there is no Arabic model — so on a bilingual
     * document the Latin half is recognised and the Arabic half is not returned at all. iOS is the
     * other way around: Apple's Vision framework does recognise Arabic (ar-SA), though only at its
     * accurate recognition level.
     *
     * The result says which engine ran and which scripts it covers rather than leaving a caller to
     * infer it from missing text, because "no Arabic in the output" and "no Arabic on the page"
     * look identical downstream. A backend that needs the Arabic can OCR the returned image itself.
     *
     * Raw lines only, no field extraction. Turning a utility bill into named fields is
     * issuer-specific guesswork, and the plugin has no way to tell a customer's address from their
     * landlord's.
     */
    private JSONObject runOcr(Bitmap bitmap) {
        JSONObject ocr = new JSONObject();
        try {
            Text result = Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0)));
            JSONArray lines = new JSONArray();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    lines.put(line.getText());
                }
            }
            ocr.put("text", result.getText());
            ocr.put("lines", lines);
            ocr.put("lineCount", lines.length());
            ocr.put("engine", "mlkit-text-recognition-v2");
            ocr.put("scripts", new JSONArray().put("Latin"));
            ocr.put("arabicSupported", false);
        } catch (Exception e) {
            Log.w(TAG, "Text recognition failed: " + e.getClass().getSimpleName());
            try {
                ocr.put("text", "");
                ocr.put("lines", new JSONArray());
                ocr.put("lineCount", 0);
                ocr.put("engine", "mlkit-text-recognition-v2");
                ocr.put("scripts", new JSONArray().put("Latin"));
                ocr.put("arabicSupported", false);
                ocr.put("error", "OCR_FAILED");
            } catch (Exception ignored) {}
        }
        return ocr;
    }

    private void finishWithResult() {
        try {
            JSONObject result = new JSONObject();
            result.put("captureType", options.captureType);
            if (options.documentType != null) result.put("documentType", options.documentType);

            JSONArray images = new JSONArray();
            JSONObject byKey = new JSONObject();
            for (JSONObject entry : captured) {
                images.put(entry);
                byKey.put(entry.optString("key"), entry);
            }
            result.put("images", images);
            // Also keyed, so a caller can read result.sides.front without walking the array.
            result.put("sides", byKey);
            result.put("capturedAt", System.currentTimeMillis());
            publishResult(result);
            setResult(Activity.RESULT_OK, new Intent());
        } catch (Exception e) {
            Log.e(TAG, "Could not assemble the capture result: " + e.getMessage(), e);
            clearResult();
            setResult(Activity.RESULT_CANCELED, new Intent());
        }
        finish();
    }

    private void cancel() {
        clearResult();
        setResult(Activity.RESULT_CANCELED, new Intent());
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (textRecognizer != null) textRecognizer.close();
        if (pendingBitmap != null) {
            pendingBitmap.recycle();
            pendingBitmap = null;
        }
    }

    private void setGuideFrameBorder() {
        GradientDrawable border = new GradientDrawable();
        border.setStroke(3, Color.WHITE);
        border.setCornerRadius(12);
        border.setColor(Color.TRANSPARENT);
        captureGuideFrame.setBackground(border);
    }

    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }
}
