package com.nfcdocumentreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera activity for scanning MRZ lines from identity documents.
 * Uses CameraX for preview and ML Kit for text recognition.
 */
public class MrzCameraActivity extends AppCompatActivity {

    private static final String TAG = "MrzCameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    private PreviewView cameraPreview;
    private View mrzGuideFrame;
    private TextView statusText;
    private TextView resultText;
    private LinearLayout resultContainer;
    private Button confirmButton;
    private Button rescanButton;
    private Button cancelButton;
    private ImageButton closeButton;

    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;
    private MrzOcrProcessor mrzProcessor;
    private volatile boolean mrzDetected = false;

    private MrzOcrProcessor.MrzParseResult detectedResult;

    /**
     * How the scanner paces itself, and how sure it has to be before accepting a read.
     *
     * It used to accept the first frame that parsed, having OCR'd every frame the camera produced.
     * A single frame is a weak basis for a decision the rest of the flow depends on: an Algerian
     * ID scanned that way returned a name whose "<<" separator had been read as the letter K,
     * which surfaced later as a printed-versus-chip mismatch on a document that was genuine.
     *
     * So a read now has to repeat. requiredMatches frames must agree on the complete MRZ — every
     * line, not just the three fields the chip key needs, since a misread anywhere is a misread —
     * and frames are sampled at most every frameIntervalMs, which also lets autofocus settle
     * between looks instead of recognising the same blur over and over.
     */
    private long frameIntervalMs = 250;
    private int requiredMatches = 2;
    private volatile long lastProcessedAt = 0;
    private String candidateKey;
    private int candidateMatches = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(getResources().getIdentifier("activity_mrz_camera", "layout", getPackageName()));

        // Find views
        cameraPreview = findViewById(getResId("cameraPreview"));
        mrzGuideFrame = findViewById(getResId("mrzGuideFrame"));
        statusText = findViewById(getResId("statusText"));
        resultText = findViewById(getResId("resultText"));
        resultContainer = findViewById(getResId("resultContainer"));
        confirmButton = findViewById(getResId("confirmButton"));
        rescanButton = findViewById(getResId("rescanButton"));
        cancelButton = findViewById(getResId("cancelButton"));
        closeButton = findViewById(getResId("closeButton"));

        // The preview is deliberately full-screen, so the overlay chrome has to dodge the status
        // bar and the navigation bar itself.
        SystemBarInsets.apply(findViewById(android.R.id.content),
                findViewById(getResId("topBar")),
                findViewById(getResId("bottomPanel")));

        cameraExecutor = Executors.newSingleThreadExecutor();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        mrzProcessor = new MrzOcrProcessor();

        // Draw MRZ guide frame border
        setGuideFrameBorder(Color.WHITE);

        frameIntervalMs = getIntent().getLongExtra("frameIntervalMs", frameIntervalMs);
        requiredMatches = Math.max(1, getIntent().getIntExtra("requiredMatches", requiredMatches));

        // Set initial status based on document type
        String documentType = getIntent().getStringExtra("documentType");
        if ("passport".equals(documentType)) {
            statusText.setText("Point camera at the MRZ lines on your passport data page");
        } else {
            statusText.setText("Point camera at the MRZ lines on the back of your ID card");
        }

        // Close button (top-right X)
        closeButton.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Cancel button (bottom)
        cancelButton.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Confirm button — return MRZ data
        confirmButton.setOnClickListener(v -> {
            if (detectedResult != null && detectedResult.isSuccess()) {
                Intent data = new Intent();
                data.putExtra("documentNumber", detectedResult.documentNumber);
                data.putExtra("dateOfBirth", detectedResult.dateOfBirth);
                data.putExtra("dateOfExpiry", detectedResult.dateOfExpiry);
                data.putExtra("format", detectedResult.format);
                data.putExtra("rawMrzLines", detectedResult.rawLines.toArray(new String[0]));
                setResult(Activity.RESULT_OK, data);
                finish();
            }
        });

        // Re-scan button — reset and scan again
        rescanButton.setOnClickListener(v -> resetScan());

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    // ==================== MRZ Guide Frame ====================

    private void setGuideFrameBorder(int color) {
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.RECTANGLE);
        border.setStroke(3, color);
        border.setCornerRadius(12f);
        border.setColor(Color.TRANSPARENT);
        mrzGuideFrame.setBackground(border);
    }

    // ==================== Camera ====================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to scan MRZ", Toast.LENGTH_LONG).show();
                Intent data = new Intent();
                data.putExtra("error", "Camera permission denied");
                setResult(Activity.RESULT_CANCELED, data);
                finish();
            }
        }
    }

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
                    long now = System.currentTimeMillis();
                    if (mrzDetected || now - lastProcessedAt < frameIntervalMs) {
                        // Dropped without recognising it: the camera delivers frames far faster
                        // than a document changes, and OCR on every one spends battery and heat
                        // taking repeated looks at the same blur.
                        imageProxy.close();
                        return;
                    }
                    lastProcessedAt = now;
                    processImage(imageProxy);
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,
                    CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ==================== OCR Processing ====================

    @SuppressWarnings("UnsafeOptInUsageError")
    private void processImage(ImageProxy imageProxy) {
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(
            mediaImage, imageProxy.getImageInfo().getRotationDegrees());

        textRecognizer.process(inputImage)
            .addOnSuccessListener(text -> {
                MrzOcrProcessor.MrzParseResult result = mrzProcessor.processText(text);
                if (result.isSuccess() && !mrzDetected) {
                    if (!agreesWithCandidate(result)) {
                        final int seen = candidateMatches;
                        runOnUiThread(() -> statusText.setText(
                                "Hold steady — confirming (" + seen + "/" + requiredMatches + ")"));
                        return;
                    }
                    mrzDetected = true;
                    detectedResult = result;
                    runOnUiThread(() -> showResult(result));
                } else if (!result.rawLines.isEmpty()) {
                    runOnUiThread(() ->
                        statusText.setText("Detecting... (" + result.rawLines.size() + " lines found)"));
                }
            })
            .addOnFailureListener(e -> Log.e(TAG, "OCR error: " + e.getMessage()))
            .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Counts how many consecutive reads agree. The whole MRZ is the key, not just the fields the
     * chip needs: the name line carries no check digit, so a misread there is invisible until
     * something downstream compares the print against the chip.
     */
    private boolean agreesWithCandidate(MrzOcrProcessor.MrzParseResult result) {
        StringBuilder key = new StringBuilder();
        for (String line : result.rawLines) {
            key.append(line).append('\n');
        }
        String candidate = key.toString();

        if (candidate.equals(candidateKey)) {
            candidateMatches++;
        } else {
            candidateKey = candidate;
            candidateMatches = 1;
        }
        return candidateMatches >= requiredMatches;
    }

    // ==================== UI State ====================

    private void showResult(MrzOcrProcessor.MrzParseResult result) {
        // Update guide frame to green
        setGuideFrameBorder(Color.parseColor("#4CAF50"));

        // Update status
        statusText.setText("MRZ detected (" + result.format + ")");

        // Show result card
        resultContainer.setVisibility(View.VISIBLE);
        resultText.setText(
            "Document: " + result.documentNumber +
            "\nDate of Birth: " + result.dateOfBirth +
            "\nDate of Expiry: " + result.dateOfExpiry
        );

        // Show confirm and re-scan buttons
        confirmButton.setVisibility(View.VISIBLE);
        rescanButton.setVisibility(View.VISIBLE);
    }

    private void resetScan() {
        mrzDetected = false;
        detectedResult = null;
        candidateKey = null;
        candidateMatches = 0;

        // Reset guide frame to white
        setGuideFrameBorder(Color.WHITE);

        // Reset status
        String documentType = getIntent().getStringExtra("documentType");
        if ("passport".equals(documentType)) {
            statusText.setText("Point camera at the MRZ lines on your passport data page");
        } else {
            statusText.setText("Point camera at the MRZ lines on the back of your ID card");
        }

        // Hide result card and buttons
        resultContainer.setVisibility(View.GONE);
        confirmButton.setVisibility(View.GONE);
        rescanButton.setVisibility(View.GONE);
    }

    // ==================== Lifecycle ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
