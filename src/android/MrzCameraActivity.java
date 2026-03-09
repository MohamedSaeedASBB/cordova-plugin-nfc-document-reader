package com.nfcdocumentreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
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
    private TextView statusText;
    private TextView resultText;
    private LinearLayout resultContainer;
    private Button confirmButton;
    private Button cancelButton;

    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;
    private MrzOcrProcessor mrzProcessor;
    private boolean mrzDetected = false;

    private MrzOcrProcessor.MrzParseResult detectedResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Find views from layout
        setContentView(getResources().getIdentifier("activity_mrz_camera", "layout", getPackageName()));

        cameraPreview = findViewById(getResources().getIdentifier("cameraPreview", "id", getPackageName()));
        statusText = findViewById(getResources().getIdentifier("statusText", "id", getPackageName()));
        resultText = findViewById(getResources().getIdentifier("resultText", "id", getPackageName()));
        resultContainer = findViewById(getResources().getIdentifier("resultContainer", "id", getPackageName()));
        confirmButton = findViewById(getResources().getIdentifier("confirmButton", "id", getPackageName()));
        cancelButton = findViewById(getResources().getIdentifier("cancelButton", "id", getPackageName()));

        cameraExecutor = Executors.newSingleThreadExecutor();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        mrzProcessor = new MrzOcrProcessor();

        // Set initial status
        String documentType = getIntent().getStringExtra("documentType");
        if ("passport".equals(documentType)) {
            statusText.setText("Point camera at the MRZ lines on your passport data page");
        } else {
            statusText.setText("Point camera at the MRZ lines on the back of your ID card");
        }

        cancelButton.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

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

        // Check camera permission
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
                    if (mrzDetected) {
                        imageProxy.close();
                        return;
                    }
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

    private void showResult(MrzOcrProcessor.MrzParseResult result) {
        statusText.setText("MRZ detected (" + result.format + ")!");
        resultContainer.setVisibility(View.VISIBLE);
        resultText.setText("Doc: " + result.documentNumber +
            "\nDOB: " + result.dateOfBirth + " | Exp: " + result.dateOfExpiry);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
