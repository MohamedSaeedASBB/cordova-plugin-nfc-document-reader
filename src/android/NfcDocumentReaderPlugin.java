package com.nfcdocumentreader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Cordova plugin entry point for NFC Document Reader.
 * Bridges JavaScript API calls to native Android NFC/camera functionality.
 */
public class NfcDocumentReaderPlugin extends CordovaPlugin {

    private static final String TAG = "NfcDocReaderPlugin";
    private static final int REQUEST_MRZ_SCAN = 9001;
    private static final int REQUEST_LIVENESS = 9002;
    private static final int REQUEST_DOCUMENT_CAPTURE = 9003;

    private NfcAdapter nfcAdapter;
    private CallbackContext nfcCallbackContext;
    private CallbackContext mrzScanCallbackContext;
    private CallbackContext livenessCallbackContext;
    private CallbackContext captureCallbackContext;
    /** Non-null while a capture is phase one of captureAndReadNFC: [mrzData, options]. */
    private JSONArray pendingCaptureThenReadArgs;
    /** A finished capture handed to the next readNFC, which adopts it and clears this. */
    private JSONObject captureAwaitingRead;
    /** The capture belonging to the read currently running. */
    private JSONObject pendingCaptureJson;
    private NfcDocumentReader documentReader;

    // MRZ data for BAC authentication
    private String pendingDocumentNumber;
    private String pendingDateOfBirth;
    private String pendingDateOfExpiry;
    private String pendingRawMrzInfo = "";
    private JSONObject pendingPassiveAuthConfig;
    private boolean pendingIncludeRawDataGroups = false;
    private volatile boolean nfcReadingActive = false;

    /**
     * Presence checks interrupt long transactions. A full MRTD read with BAC/PACE takes many
     * seconds, so the platform is told to wait this long between checks.
     */
    private static final int READER_PRESENCE_CHECK_DELAY_MS = 20000;

    /**
     * Arming reader mode is a state to reach, not a call to make.
     *
     * NfcAdapter.enableReaderMode requires a resumed activity and throws if it does not have one.
     * The common way to hit that is the natural JS flow — calling readNFC from inside the
     * scanMRZ callback, which runs while the MRZ camera activity is still on top. The call used
     * to throw into a catch that only logged, so the bottom sheet opened, nothing was listening
     * for a tag, and the read waited forever on a document that could never be detected.
     *
     * So: readNFC records that a read wants reader mode, arming is attempted whenever the
     * activity is actually resumed, retried a few times if the platform still refuses, and
     * reported to the JS error callback if it never succeeds. A read that is not listening must
     * never look like a read that is waiting for the user to tap.
     */
    private volatile boolean activityResumed = true;
    private volatile boolean readerModeArmed = false;
    private final Handler armHandler = new Handler(Looper.getMainLooper());
    private static final int MAX_ARM_ATTEMPTS = 8;
    private static final int ARM_RETRY_DELAY_MS = 500;
    /**
     * A successful arm is not a lasting one. Observed on Android 14 (One UI 6): arming during the
     * MRZ activity's teardown succeeds, and ~700ms later the framework re-applies reader-mode
     * state for the newly resumed activity and clears ours —
     *
     *   NfcService: setReaderMode: uid=10242, packageName: <app>, flags: 387   (ours)
     *   NfcService: setReaderMode: uid=1000,  packageName: android, flags: 0   (wiped)
     *
     * after which taps are detected by the platform but handled as ordinary NDEF tags and never
     * reach our callback. Re-applying the binding is idempotent, so it is re-asserted a couple of
     * times across the transition rather than trusted once.
     */
    private static final int REASSERT_COUNT = 3;
    private static final int REASSERT_DELAY_MS = 900;

    /** Guards against a second tag callback starting a concurrent read. */
    private final Object tagLock = new Object();
    private boolean tagBeingRead = false;

    private boolean isTagBeingRead() {
        synchronized (tagLock) {
            return tagBeingRead;
        }
    }

    // Chip-read-then-liveness flow: the document result is held here while the liveness
    // activity runs, then merged with the liveness result and the face comparison.
    private JSONObject pendingDocumentJson;
    private android.graphics.Bitmap pendingDocumentPortrait;
    private String pendingLivenessOptionsJson;
    private JSONObject pendingFaceMatchConfig;

    // NFC scan bottom sheet dialog
    private Dialog nfcDialog;
    private TextView nfcTitle;
    private TextView nfcDescription;
    private TextView nfcStatus;
    private TextView nfcIcon;
    private ProgressBar nfcProgressBar;

    @Override
    protected void pluginInitialize() {
        // Register full BouncyCastle provider — Android's built-in version is stripped
        // and lacks elliptic curve support needed for PACE authentication (e.g. Algerian IDs)
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        Log.d(TAG, "BouncyCastle security provider registered");

        nfcAdapter = NfcAdapter.getDefaultAdapter(cordova.getActivity());
        documentReader = new NfcDocumentReader();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "isNFCAvailable":
                isNFCAvailable(callbackContext);
                return true;
            case "scanMRZ":
                scanMRZ(args, callbackContext);
                return true;
            case "checkLiveness":
                checkLiveness(args, callbackContext);
                return true;
            case "captureDocument":
                captureDocument(args, callbackContext);
                return true;
            case "captureProofOfAddress":
                captureProofOfAddress(args, callbackContext);
                return true;
            case "captureAndReadNFC":
                captureAndReadNFC(args, callbackContext);
                return true;
            case "readNFC":
                readNFC(args, callbackContext);
                return true;
            case "cancelRead":
                cancelRead(callbackContext);
                return true;
            default:
                return false;
        }
    }

    // ==================== isNFCAvailable ====================

    private void isNFCAvailable(CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("available", nfcAdapter != null);
            result.put("enabled", nfcAdapter != null && nfcAdapter.isEnabled());
            callbackContext.success(result);
        } catch (JSONException e) {
            callbackContext.error("Error checking NFC availability: " + e.getMessage());
        }
    }

    // ==================== scanMRZ ====================

    private void scanMRZ(JSONArray args, CallbackContext callbackContext) {
        mrzScanCallbackContext = callbackContext;
        Activity activity = cordova.getActivity();

        Intent intent = new Intent(activity, MrzCameraActivity.class);
        if (args.length() > 0) {
            try {
                JSONObject options = args.getJSONObject(0);
                if (options.has("documentType")) {
                    intent.putExtra("documentType", options.getString("documentType"));
                }
            } catch (JSONException e) {
                Log.w(TAG, "Error parsing scanMRZ options: " + e.getMessage());
            }
        }

        cordova.startActivityForResult(this, intent, REQUEST_MRZ_SCAN);
    }

    // ==================== captureDocument / captureProofOfAddress ====================

    /**
     * Photographs the document itself. An ID card has two sides worth capturing and a passport
     * has one, so the step list is decided here rather than by the app or the camera screen.
     */
    private void captureDocument(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options == null) options = new JSONObject();

        String documentType = options.optString("documentType", "id");
        try {
            options.put("captureType", "document");
            options.put("documentType", documentType);
            options.put("steps", DocumentCaptureOptions.stepsForDocumentType(documentType));
            // No OCR here, whatever the caller asked for. The chip already carries these fields —
            // including the Arabic — covered by the issuer's signature and hash-verified, so
            // reading them off a photograph instead would substitute an unsigned, camera-dependent
            // guess for data that was proven authentic. Any value passed is ignored deliberately.
            if (options.optBoolean("ocr", false)) {
                Log.i(TAG, "Ignoring ocr:true for captureDocument — the chip provides these fields"
                        + " signed. OCR is available on captureProofOfAddress.");
            }
            options.put("ocr", false);
            if (!options.has("title")) {
                options.put("title", "passport".equalsIgnoreCase(documentType)
                        ? "Capture passport" : "Capture ID card");
            }
        } catch (JSONException e) {
            callbackContext.error("Invalid capture options: " + e.getMessage());
            return;
        }
        launchCapture(options, callbackContext);
    }

    /** A single page of whatever the customer brought — a bill, a statement, a tenancy contract. */
    private void captureProofOfAddress(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options == null) options = new JSONObject();

        try {
            options.put("captureType", "proofOfAddress");
            // On by default: reading the page is the reason this capture exists. There is no chip
            // behind a utility bill to take the text from instead.
            options.put("ocr", options.optBoolean("ocr", true));
            // More room than an identity document gets. Here the picture is the data: the print on
            // a bill is small, and a backend re-reading it for Arabic is limited by what was sent,
            // not by what the camera saw. Only applied where the caller did not choose.
            if (!options.has("maxImageDimension")) {
                options.put("maxImageDimension", DocumentCaptureOptions.PROOF_MAX_DIMENSION);
            }
            if (!options.has("maxImageBytes")) {
                options.put("maxImageBytes", DocumentCaptureOptions.PROOF_MAX_BYTES);
            }
            if (!options.has("jpegQuality")) {
                options.put("jpegQuality", DocumentCaptureOptions.PROOF_JPEG_QUALITY);
            }
            if (!options.has("title")) options.put("title", "Proof of address");
            if (!options.has("steps")) {
                JSONArray steps = new JSONArray();
                JSONObject step = new JSONObject();
                step.put("key", "document");
                step.put("label", "Proof of address");
                step.put("hint", "Photograph the whole page, including the name and address");
                steps.put(step);
                options.put("steps", steps);
            }
        } catch (JSONException e) {
            callbackContext.error("Invalid capture options: " + e.getMessage());
            return;
        }
        launchCapture(options, callbackContext);
    }

    /**
     * Photographs the card and then reads its chip, on one callback.
     *
     * Photographs first, deliberately: the customer is already holding the card, and tapping it
     * to the phone is the natural next move rather than the other way round.
     *
     * Note what this does NOT do. If the chip read fails, the error callback fires with a message
     * and the photographs are discarded with it — the error contract carries a string, not a
     * payload. A flow that must keep the images through a failed read should call captureDocument
     * and readNFC separately and combine them itself.
     */
    private void captureAndReadNFC(JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(1);
        if (options == null) options = new JSONObject();

        JSONObject captureOptions = new JSONObject();
        try {
            // Only the capture-side options travel to the camera; liveness, faceMatch and the rest
            // belong to the chip read that follows.
            String documentType = options.optString("documentType", "id");
            captureOptions.put("documentType", documentType);
            if (options.has("title")) captureOptions.put("title", options.optString("title"));
            for (String key : new String[] { "maxImageDimension", "maxImageBytes", "jpegQuality" }) {
                if (options.has(key)) captureOptions.put(key, options.opt(key));
            }
            captureOptions.put("captureType", "document");
            captureOptions.put("ocr", false);           // the chip supplies these fields, signed
            captureOptions.put("steps", DocumentCaptureOptions.stepsForDocumentType(documentType));
            if (!captureOptions.has("title")) {
                captureOptions.put("title", "passport".equalsIgnoreCase(documentType)
                        ? "Capture passport" : "Capture ID card");
            }
        } catch (JSONException e) {
            callbackContext.error("Invalid capture options: " + e.getMessage());
            return;
        }

        pendingCaptureThenReadArgs = args;
        captureAwaitingRead = null;
        launchCapture(captureOptions, callbackContext);
    }

    private void launchCapture(JSONObject options, CallbackContext callbackContext) {
        captureCallbackContext = callbackContext;
        DocumentCaptureActivity.clearResult();

        Intent intent = new Intent(cordova.getActivity(), DocumentCaptureActivity.class);
        intent.putExtra(DocumentCaptureActivity.EXTRA_OPTIONS, options.toString());
        cordova.startActivityForResult(this, intent, REQUEST_DOCUMENT_CAPTURE);
    }

    private void onDocumentCaptureResult(int resultCode, Intent intent) {
        // Same reason as the liveness result: the payload carries base64 JPEGs, which are far too
        // large for Intent extras, so the activity hands it over in memory.
        JSONObject result = DocumentCaptureActivity.consumeResult();
        CallbackContext callback = captureCallbackContext;
        captureCallbackContext = null;
        JSONArray thenReadArgs = pendingCaptureThenReadArgs;
        pendingCaptureThenReadArgs = null;
        if (callback == null) return;

        boolean captured = resultCode == Activity.RESULT_OK && result != null;

        if (thenReadArgs == null) {
            if (captured) {
                callback.success(result);
            } else {
                callback.error("Document capture was cancelled.");
            }
            return;
        }

        // Phase one of captureAndReadNFC. Abandoning the photos abandons the whole flow: the chip
        // read would otherwise start behind a screen the user just backed out of.
        if (!captured) {
            callback.error("Document capture was cancelled.");
            return;
        }
        captureAwaitingRead = result;
        readNFC(thenReadArgs, callback);
    }

    // ==================== checkLiveness ====================

    private void checkLiveness(JSONArray args, CallbackContext callbackContext) {
        livenessCallbackContext = callbackContext;
        Activity activity = cordova.getActivity();

        // Any result left over from an abandoned session must not leak into this one.
        LivenessCameraActivity.clearResult();

        Intent intent = new Intent(activity, LivenessCameraActivity.class);
        if (args.length() > 0 && !args.isNull(0)) {
            try {
                intent.putExtra(LivenessCameraActivity.EXTRA_OPTIONS, args.getJSONObject(0).toString());
            } catch (JSONException e) {
                Log.w(TAG, "Error parsing checkLiveness options: " + e.getMessage());
            }
        }

        cordova.startActivityForResult(this, intent, REQUEST_LIVENESS);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_LIVENESS) {
            onLivenessResult(resultCode, intent);
        } else if (requestCode == REQUEST_MRZ_SCAN) {
            onMrzScanResult(resultCode, intent);
        } else if (requestCode == REQUEST_DOCUMENT_CAPTURE) {
            onDocumentCaptureResult(resultCode, intent);
        }
    }

    private void onLivenessResult(int resultCode, Intent intent) {
        // The payload carries base64 JPEGs, which are too large for Intent extras — the activity
        // hands it over in memory instead. Consuming clears it so the biometric data does not
        // outlive the callback.
        JSONObject result = LivenessCameraActivity.consumeResult();
        String errorMsg = intent != null ? intent.getStringExtra("error") : null;
        boolean ok = resultCode == Activity.RESULT_OK && result != null;

        if (pendingDocumentJson != null) {
            completeChipReadWithLiveness(ok, result, errorMsg);
            return;
        }

        if (livenessCallbackContext == null) return;

        if (ok) {
            livenessCallbackContext.success(result);
        } else {
            livenessCallbackContext.error(errorMsg != null ? errorMsg : "Liveness check cancelled");
        }
        livenessCallbackContext = null;
    }

    // ==================== Chip read + liveness + on-device face match ====================

    private void launchLivenessForChipRead() {
        Activity activity = cordova.getActivity();
        LivenessCameraActivity.clearResult();

        Intent intent = new Intent(activity, LivenessCameraActivity.class);
        intent.putExtra(LivenessCameraActivity.EXTRA_OPTIONS, pendingLivenessOptionsJson);
        cordova.startActivityForResult(this, intent, REQUEST_LIVENESS);
    }

    /**
     * Merges the chip result, the liveness result and the on-device face comparison into the
     * single payload the app forwards to the back office.
     */
    private void completeChipReadWithLiveness(final boolean livenessOk,
                                              final JSONObject livenessResult,
                                              final String errorMsg) {
        final CallbackContext callback = nfcCallbackContext;
        final JSONObject documentJson = pendingDocumentJson;
        final android.graphics.Bitmap documentPortrait = pendingDocumentPortrait;
        final JSONObject faceMatchConfig = pendingFaceMatchConfig;

        // Clear the holding fields before the async work so a second read cannot see stale state.
        pendingDocumentJson = null;
        pendingDocumentPortrait = null;
        nfcCallbackContext = null;

        if (callback == null) return;

        if (!livenessOk) {
            callback.error(errorMsg != null ? errorMsg : "Liveness check cancelled");
            return;
        }

        // Face detection on stills blocks, and TFLite inference is heavy — never on the UI thread.
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    documentJson.put("liveness", livenessResult);

                    LivenessOptions livenessOptions =
                            LivenessOptions.fromJson(pendingLivenessOptionsJson);
                    FaceComparison.Outcome outcome = FaceComparison.compare(
                            documentPortrait,
                            livenessResult.optString("faceImageBase64", null),
                            livenessOptions.imageOptions());

                    try {
                        // ---- On-device 1:1 match ----
                        FaceMatcher.Config matchConfig = parseFaceMatchConfig(faceMatchConfig);
                        FaceMatcher matcher = new FaceMatcher(cordova.getActivity(), matchConfig);
                        FaceMatcher.MatchResult match = matcher.match(
                                documentPortrait, outcome.documentFaceBox,
                                outcome.livenessPortrait, outcome.livenessFaceBox);

                        JSONObject matchJson = new JSONObject();
                        matchJson.put("status", match.status);
                        matchJson.put("similarity", match.similarity != null
                                ? match.similarity : JSONObject.NULL);
                        matchJson.put("reason", match.reason != null ? match.reason : JSONObject.NULL);
                        matchJson.put("onDevice", true);
                        outcome.json.put("match", matchJson);
                    } finally {
                        if (outcome.livenessPortrait != null) {
                            outcome.livenessPortrait.recycle();
                        }
                    }

                    documentJson.put("faceComparison", outcome.json);

                    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, documentJson);
                    pluginResult.setKeepCallback(false);
                    callback.sendPluginResult(pluginResult);
                } catch (Exception e) {
                    Log.e(TAG, "Error building liveness comparison result: " + e.getMessage(), e);
                    callback.error("The document was read but the face comparison failed. Please try again.");
                }
            }
        });
    }

    /**
     * Parses {@code options.passiveAuth}. The trust store defaults to the asset name documented
     * in src/csca/README.md, so a build that installs the CSCA bundle gets full passive
     * authentication with no JS changes; without it the payload reports "notVerified".
     */
    private PassiveAuthenticator.Config parsePassiveAuthConfig(JSONObject json) {
        PassiveAuthenticator.Config config = new PassiveAuthenticator.Config();
        config.trustStoreAsset = PassiveAuthenticator.DEFAULT_TRUST_STORE_ASSET;
        if (json == null) {
            return config;
        }
        if (json.has("trustStoreAsset")) {
            config.trustStoreAsset = json.isNull("trustStoreAsset")
                    ? null                                      // explicit opt-out
                    : json.optString("trustStoreAsset", config.trustStoreAsset);
        }
        return config;
    }

    private FaceMatcher.Config parseFaceMatchConfig(JSONObject json) {
        FaceMatcher.Config config = new FaceMatcher.Config();
        if (json == null) {
            return config;
        }
        // Only touch modelAsset when the caller actually sent the key: optString's fallback
        // fires for an absent key too, so reading it unconditionally wiped the documented
        // default and silently disabled matching for anyone passing { threshold: ... } alone.
        if (json.has("modelAsset")) {
            config.modelAssetExplicit = true;
            config.modelAsset = json.isNull("modelAsset")
                    ? null                                      // explicit opt-out
                    : json.optString("modelAsset", config.modelAsset);
        }
        config.inputSize = json.optInt("inputSize", config.inputSize);
        config.embeddingSize = json.optInt("embeddingSize", config.embeddingSize);
        return config;
    }

    private void onMrzScanResult(int resultCode, Intent intent) {
        if (mrzScanCallbackContext == null) return;

        if (resultCode == Activity.RESULT_OK && intent != null) {
            try {
                JSONObject result = new JSONObject();
                result.put("documentNumber", intent.getStringExtra("documentNumber"));
                result.put("dateOfBirth", intent.getStringExtra("dateOfBirth"));
                result.put("dateOfExpiry", intent.getStringExtra("dateOfExpiry"));
                result.put("format", intent.getStringExtra("format"));

                String[] rawLines = intent.getStringArrayExtra("rawMrzLines");
                JSONArray linesArray = new JSONArray();
                if (rawLines != null) {
                    for (String line : rawLines) {
                        linesArray.put(line);
                    }
                }
                result.put("rawMrzLines", linesArray);

                mrzScanCallbackContext.success(result);
            } catch (JSONException e) {
                mrzScanCallbackContext.error("Error building MRZ result: " + e.getMessage());
            }
        } else {
            String errorMsg = intent != null ? intent.getStringExtra("error") : "MRZ scan cancelled";
            mrzScanCallbackContext.error(errorMsg != null ? errorMsg : "MRZ scan cancelled");
        }
        mrzScanCallbackContext = null;
    }

    // ==================== readNFC ====================

    private void readNFC(JSONArray args, CallbackContext callbackContext) {
        if (nfcAdapter == null) {
            callbackContext.error("NFC is not available on this device.");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            callbackContext.error("NFC is turned off. Please enable NFC in your device settings.");
            return;
        }

        pendingRawMrzInfo = "";
        pendingLivenessOptionsJson = null;
        pendingFaceMatchConfig = null;
        pendingPassiveAuthConfig = null;
        pendingIncludeRawDataGroups = false;
        // Adopt a capture only if one was just taken for this read. A plain readNFC clears it, so
        // photographs from an abandoned combined flow cannot attach themselves to a later read.
        pendingCaptureJson = captureAwaitingRead;
        captureAwaitingRead = null;

        // Optional second argument: { liveness: {...}, faceMatch: {...} }.
        // Absent means chip-read only, so existing callers are unaffected.
        if (args.length() > 1 && !args.isNull(1)) {
            try {
                JSONObject readOptions = args.getJSONObject(1);
                if (readOptions.optBoolean("liveness", false)) {
                    pendingLivenessOptionsJson = "{}";
                } else if (readOptions.has("liveness") && !readOptions.isNull("liveness")) {
                    pendingLivenessOptionsJson = readOptions.getJSONObject("liveness").toString();
                }
                if (readOptions.has("faceMatch") && !readOptions.isNull("faceMatch")) {
                    pendingFaceMatchConfig = readOptions.getJSONObject("faceMatch");
                }
                if (readOptions.has("passiveAuth") && !readOptions.isNull("passiveAuth")) {
                    pendingPassiveAuthConfig = readOptions.getJSONObject("passiveAuth");
                }
                pendingIncludeRawDataGroups =
                        readOptions.optBoolean("includeRawDataGroups", false);
            } catch (JSONException e) {
                Log.w(TAG, "Error parsing readNFC options: " + e.getMessage());
            }
        }

        try {
            JSONObject mrzData = args.getJSONObject(0);
            pendingDocumentNumber = mrzData.getString("documentNumber");
            pendingDateOfBirth = mrzData.getString("dateOfBirth");
            pendingDateOfExpiry = mrzData.getString("dateOfExpiry");
            // Extract raw MRZ lines for diagnostic purposes
            if (mrzData.has("rawMrzLines")) {
                JSONArray rawLines = mrzData.getJSONArray("rawMrzLines");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rawLines.length(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(rawLines.getString(i));
                }
                pendingRawMrzInfo = sb.toString();
            }
        } catch (JSONException e) {
            callbackContext.error("Invalid MRZ data: " + e.getMessage());
            return;
        }

        nfcCallbackContext = callbackContext;
        nfcReadingActive = true;
        synchronized (tagLock) {
            // Clear any flag left by an abandoned read so this one is not ignored.
            tagBeingRead = false;
        }

        // Show the NFC scan bottom sheet
        showNfcDialog();

        // Send initial state
        sendProgressEvent("waitingForTag");

        // Arm tag detection
        enableNfcReaderMode();
    }

    private void cancelRead(CallbackContext callbackContext) {
        nfcReadingActive = false;
        synchronized (tagLock) {
            tagBeingRead = false;
        }
        disableNfcReaderMode();
        dismissNfcDialog();

        if (nfcCallbackContext != null) {
            nfcCallbackContext.error("NFC reading cancelled");
            nfcCallbackContext = null;
        }

        callbackContext.success("Cancelled");
    }

    // ==================== NFC Bottom Sheet Dialog ====================

    private void showNfcDialog() {
        final Activity activity = cordova.getActivity();
        if (activity == null) return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    nfcDialog = new Dialog(activity);
                    nfcDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                    int layoutId = activity.getResources().getIdentifier(
                        "dialog_nfc_scan", "layout", activity.getPackageName());
                    nfcDialog.setContentView(layoutId);

                    // Style as bottom sheet
                    Window window = nfcDialog.getWindow();
                    if (window != null) {
                        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT);
                        window.setGravity(Gravity.BOTTOM);
                        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                        window.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
                    }

                    // Prevent dismiss on outside touch while reading
                    nfcDialog.setCancelable(false);
                    nfcDialog.setCanceledOnTouchOutside(false);

                    // Find views
                    nfcTitle = nfcDialog.findViewById(activity.getResources().getIdentifier(
                        "nfcTitle", "id", activity.getPackageName()));
                    nfcDescription = nfcDialog.findViewById(activity.getResources().getIdentifier(
                        "nfcDescription", "id", activity.getPackageName()));
                    nfcStatus = nfcDialog.findViewById(activity.getResources().getIdentifier(
                        "nfcStatus", "id", activity.getPackageName()));
                    nfcIcon = nfcDialog.findViewById(activity.getResources().getIdentifier(
                        "nfcIcon", "id", activity.getPackageName()));
                    nfcProgressBar = nfcDialog.findViewById(activity.getResources().getIdentifier(
                        "nfcProgressBar", "id", activity.getPackageName()));

                    Button cancelBtn = nfcDialog.findViewById(activity.getResources().getIdentifier(
                        "nfcCancelButton", "id", activity.getPackageName()));

                    cancelBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            nfcReadingActive = false;
                            disableNfcReaderMode();
                            dismissNfcDialog();

                            if (nfcCallbackContext != null) {
                                nfcCallbackContext.error("NFC reading cancelled");
                                nfcCallbackContext = null;
                            }
                        }
                    });

                    nfcDialog.show();
                    Log.d(TAG, "NFC scan dialog shown");
                } catch (Exception e) {
                    Log.e(TAG, "Error showing NFC dialog: " + e.getMessage(), e);
                }
            }
        });
    }

    private void dismissNfcDialog() {
        final Activity activity = cordova.getActivity();
        if (activity == null) return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (nfcDialog != null && nfcDialog.isShowing()) {
                        nfcDialog.dismiss();
                        Log.d(TAG, "NFC scan dialog dismissed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error dismissing NFC dialog: " + e.getMessage());
                }
                nfcDialog = null;
            }
        });
    }

    private void updateNfcDialogState(final String title, final String description,
                                       final String status, final String icon,
                                       final boolean showProgress) {
        final Activity activity = cordova.getActivity();
        if (activity == null) return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (nfcDialog == null || !nfcDialog.isShowing()) return;
                    if (title != null && nfcTitle != null) nfcTitle.setText(title);
                    if (description != null && nfcDescription != null) nfcDescription.setText(description);
                    if (status != null && nfcStatus != null) nfcStatus.setText(status);
                    if (icon != null && nfcIcon != null) nfcIcon.setText(icon);
                    if (nfcProgressBar != null) {
                        nfcProgressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating NFC dialog: " + e.getMessage());
                }
            }
        });
    }

    // ==================== NFC Tag Handling ====================

    // Tags arrive via the reader-mode callback further down, not through onNewIntent. Reader
    // mode suppresses the platform's tag dispatch entirely, so no ACTION_TECH_DISCOVERED intent
    // is delivered while a read is armed — and the host activity is never relaunched as a
    // result, which is what previously reset the WebView to its first screen.

    private void readTag(final Tag tag) {
        final CallbackContext callback = nfcCallbackContext;
        if (callback == null) return;

        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Passive authentication always runs; the trust store decides how far it can
                    // get. Configured per read so the CSCA bundle can be swapped without a
                    // rebuild, and so a caller can point at a test bundle.
                    documentReader.setPassiveAuthentication(cordova.getActivity().getApplicationContext(),
                            parsePassiveAuthConfig(pendingPassiveAuthConfig));
                    documentReader.setIncludeRawDataGroups(pendingIncludeRawDataGroups);

                    documentReader.readDocument(tag, pendingDocumentNumber,
                        pendingDateOfBirth, pendingDateOfExpiry,
                        new NfcDocumentReader.ProgressListener() {
                            @Override
                            public void onStateChanged(String state) {
                                sendProgressEvent(state);
                                // Update dialog based on state
                                switch (state) {
                                    case "connecting":
                                        updateNfcDialogState(null, null, "Connecting to chip...", null, true);
                                        break;
                                    case "authenticating":
                                        updateNfcDialogState(null, null, "Authenticating...", null, true);
                                        break;
                                }
                            }

                            @Override
                            public void onReadingDataGroup(int dgNumber, String dgName) {
                                sendDataGroupProgress(dgNumber, dgName);
                                updateNfcDialogState(null, null,
                                    "Reading " + dgName + "...", null, true);
                            }
                        });

                    DocumentData data = documentReader.getResult();
                    if (data != null) {
                        // Success — update dialog briefly then dismiss
                        updateNfcDialogState(
                            "Success!",
                            "Document read successfully.",
                            "Complete",
                            "\u2705",  // ✅
                            false
                        );

                        // Small delay so user sees success state
                        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

                        dismissNfcDialog();

                        JSONObject result = data.toJSON();

                        // Fold in the photographs taken before the chip read, on both the plain
                        // and the liveness paths — this result becomes pendingDocumentJson below.
                        if (pendingCaptureJson != null) {
                            try {
                                result.put("capture", pendingCaptureJson);
                            } catch (JSONException e) {
                                Log.w(TAG, "Could not attach the capture to the chip result: "
                                        + e.getMessage());
                            }
                            pendingCaptureJson = null;
                        }

                        if (pendingLivenessOptionsJson != null) {
                            // Chip read done — now prove the holder is present and compare their
                            // face against the portrait we just read off the chip. The readNFC
                            // callback stays open until that finishes.
                            pendingDocumentJson = result;
                            pendingDocumentPortrait = data.faceImage;
                            // NFC work is over; the callback deliberately stays open.
                            nfcReadingActive = false;
                            sendProgressEvent("livenessCheck");
                            launchLivenessForChipRead();
                            return;
                        }

                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
                        pluginResult.setKeepCallback(false);
                        callback.sendPluginResult(pluginResult);
                    } else {
                        String userMessage = documentReader.getError();
                        if (userMessage == null) userMessage = "An unexpected error occurred. Please try again.";

                        // Log diagnostics to Supabase (fire-and-forget)
                        DiagnosticsLogger.logError(
                            cordova.getActivity(),
                            documentReader.getErrorCode() != null ? documentReader.getErrorCode() : "UNKNOWN",
                            documentReader.getTechnicalError() != null ? documentReader.getTechnicalError() : userMessage,
                            userMessage,
                            pendingDocumentNumber,
                            pendingDateOfBirth,
                            pendingDateOfExpiry,
                            documentReader.getPaceDebugInfo(),
                            documentReader.getNfcTechList()
                        );

                        // Error — update dialog with friendly message then dismiss
                        updateNfcDialogState(
                            "Error",
                            userMessage,
                            "Failed",
                            "\u274C",  // ❌
                            false
                        );

                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

                        dismissNfcDialog();

                        callback.error(userMessage);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading NFC tag: " + e.getMessage(), e);
                    dismissNfcDialog();
                    callback.error("Error reading document: " + e.getMessage());
                } finally {
                    // Reader mode is what keeps the RF field and the IsoDep connection alive, so
                    // it can only be torn down once the transaction is over. A finally block
                    // covers the early return on the liveness path too.
                    disableNfcReaderMode();
                    synchronized (tagLock) {
                        tagBeingRead = false;
                    }
                }

                nfcReadingActive = false;
                nfcCallbackContext = null;
            }
        });
    }

    // ==================== Progress Events ====================

    private void sendProgressEvent(String state) {
        if (nfcCallbackContext == null) return;
        try {
            JSONObject event = new JSONObject();
            event.put("event", "stateChanged");
            event.put("state", state);
            PluginResult result = new PluginResult(PluginResult.Status.OK, event);
            result.setKeepCallback(true);
            nfcCallbackContext.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending progress event", e);
        }
    }

    private void sendDataGroupProgress(int dgNumber, String dgName) {
        if (nfcCallbackContext == null) return;
        try {
            JSONObject event = new JSONObject();
            event.put("event", "stateChanged");
            event.put("state", "readingDataGroup");
            event.put("dgNumber", dgNumber);
            event.put("dgName", dgName);
            PluginResult result = new PluginResult(PluginResult.Status.OK, event);
            result.setKeepCallback(true);
            nfcCallbackContext.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending DG progress event", e);
        }
    }

    // ==================== NFC Reader Mode ====================

    /**
     * Arms tag detection using reader mode rather than foreground dispatch.
     *
     * Foreground dispatch delivers tags by firing a PendingIntent at the host activity. Whether
     * Android reuses the running instance depends on that activity's launchMode, which the plugin
     * does not control — under some Cordova hosts (OutSystems MABS among them) a second instance
     * is created instead, the WebView reloads index.html, and the web app appears to navigate
     * back to its first screen. The same relaunch means onNewIntent never fires on the instance
     * that started the read, so the tag is silently dropped as well.
     *
     * Reader mode has no Intent and no PendingIntent: tags arrive on a callback, so nothing can
     * relaunch or reload anything. It also suppresses the platform's own tag handling while
     * active, which stops the OS chime and any "New tag collected" UI.
     */
    private void enableNfcReaderMode() {
        armHandler.removeCallbacksAndMessages(null);
        // Never trust a previous arm: the framework clears registrations without telling us, so
        // "already armed" is not a reason to skip re-arming.
        readerModeArmed = false;
        attemptArmReaderMode(1);
    }

    /**
     * Tries to bind reader mode, deferring to onResume while the activity is not resumed and
     * retrying a few times if the platform refuses anyway (our resumed flag can lag a window
     * focus change). Gives up loudly rather than quietly.
     */
    private void attemptArmReaderMode(final int attempt) {
        final Activity activity = cordova.getActivity();
        if (nfcAdapter == null || activity == null) return;
        // No read wants a tag any more, or one is already armed.
        if (!nfcReadingActive || readerModeArmed) return;
        // A chip is mid-read. Reader mode is what holds the RF field and the IsoDep connection
        // open, so re-registering it now would sever the transaction we are waiting on.
        if (isTagBeingRead()) return;

        if (!activityResumed) {
            // Attempt it anyway. onResume re-arms too, but only if the host dispatches plugin
            // lifecycle callbacks — OutSystems/MABS wraps the Cordova activity, and a flag that
            // never flips back to true would defer the arm forever and report nothing. The
            // platform is the authority here: if it really is paused, enableReaderMode throws
            // and the retry below handles it.
            Log.d(TAG, "Activity not reported as resumed; attempting to arm anyway");
        }

        // MUST run on UI thread — enableReaderMode requires a resumed activity.
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!nfcReadingActive || readerModeArmed) return;
                try {
                    // Passport/ID chips are ISO-DEP over NFC-A or NFC-B. Skipping the NDEF check
                    // avoids the platform probing the card before we get to it.
                    int flags = NfcAdapter.FLAG_READER_NFC_A
                        | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                        | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

                    Bundle extras = new Bundle();
                    // Presence checks interrupt long transactions; a full MRTD read with BAC/PACE
                    // takes many seconds, so keep the platform from pinging mid-read.
                    extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                        READER_PRESENCE_CHECK_DELAY_MS);

                    nfcAdapter.enableReaderMode(activity, readerCallback, flags, extras);
                    readerModeArmed = true;
                    Log.d(TAG, "NFC reader mode enabled (attempt " + attempt + ") on "
                        + activity.getClass().getName());
                    // Distinct from "waitingForTag", which only means the sheet is up: this says
                    // the platform accepted the binding and a tap can now be detected.
                    sendProgressEvent("readerArmed");
                    scheduleReaderModeReassert(REASSERT_COUNT);
                } catch (Exception e) {
                    Log.w(TAG, "Could not enable NFC reader mode (attempt " + attempt + " of "
                        + MAX_ARM_ATTEMPTS + "): " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
                    if (attempt < MAX_ARM_ATTEMPTS) {
                        armHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                attemptArmReaderMode(attempt + 1);
                            }
                        }, ARM_RETRY_DELAY_MS);
                    } else {
                        reportArmFailure(e);
                    }
                }
            }
        });
    }

    /**
     * Re-applies the reader-mode binding a few times after a successful arm, so a framework reset
     * during the activity transition cannot leave the read deaf. Silent: no progress events, and
     * no failure reporting — the arm already succeeded once.
     */
    private void scheduleReaderModeReassert(final int remaining) {
        if (remaining <= 0) return;
        armHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Same reason as in attemptArmReaderMode: once a tag is connected, leave the
                // binding alone. The re-assert exists only to survive the activity transition
                // before the first tap.
                if (!nfcReadingActive || isTagBeingRead()) return;

                final Activity activity = cordova.getActivity();
                if (nfcAdapter == null || activity == null) return;

                try {
                    int flags = NfcAdapter.FLAG_READER_NFC_A
                        | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                        | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

                    Bundle extras = new Bundle();
                    extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                        READER_PRESENCE_CHECK_DELAY_MS);

                    nfcAdapter.enableReaderMode(activity, readerCallback, flags, extras);
                    readerModeArmed = true;
                    Log.d(TAG, "NFC reader mode re-asserted (" + remaining + " left)");
                } catch (Exception e) {
                    Log.d(TAG, "Reader mode re-assert skipped: " + e.getMessage());
                }
                scheduleReaderModeReassert(remaining - 1);
            }
        }, REASSERT_DELAY_MS);
    }

    /**
     * Tag detection could not be started, so no tap will ever be noticed. Ends the read instead
     * of leaving the sheet up: silence here is what made this look like a dead NFC antenna.
     */
    private void reportArmFailure(Exception cause) {
        sendProgressEvent("readerArmFailed");
        Log.e(TAG, "NFC reader mode could not be enabled after " + MAX_ARM_ATTEMPTS
            + " attempts; ending the read. Last error: "
            + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                             : "unknown"));

        CallbackContext callback = nfcCallbackContext;
        nfcReadingActive = false;
        readerModeArmed = false;
        synchronized (tagLock) {
            tagBeingRead = false;
        }
        dismissNfcDialog();
        if (callback != null) {
            nfcCallbackContext = null;
            callback.error("Could not start NFC scanning. Please return to the previous screen "
                + "and try again.");
        }
    }

    private void disableNfcReaderMode() {
        armHandler.removeCallbacksAndMessages(null);
        readerModeArmed = false;

        final Activity activity = cordova.getActivity();
        if (nfcAdapter == null || activity == null) return;

        // MUST run on UI thread
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    nfcAdapter.disableReaderMode(activity);
                    Log.d(TAG, "NFC reader mode disabled");
                } catch (Exception e) {
                    Log.e(TAG, "Error disabling NFC reader mode: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Called on a binder thread, not the UI thread.
     *
     * Reader mode is deliberately left enabled for the duration of the read: unlike foreground
     * dispatch, it is what keeps the RF field and the IsoDep connection alive, so disabling it
     * here would sever the transaction. It is torn down in readTag's finally block instead.
     */
    private final NfcAdapter.ReaderCallback readerCallback = new NfcAdapter.ReaderCallback() {
        @Override
        public void onTagDiscovered(Tag tag) {
            if (tag == null) return;

            synchronized (tagLock) {
                if (!nfcReadingActive || nfcCallbackContext == null || tagBeingRead) return;
                tagBeingRead = true;
            }

            Log.d(TAG, "NFC tag discovered, starting read...");

            updateNfcDialogState(
                "Reading Document",
                "Keep the document still against your phone.\nDo not move it until reading is complete.",
                "Connecting...",
                "\uD83D\uDD04",  // 🔄
                true
            );

            readTag(tag);
        }
    };

    // ==================== Lifecycle ====================

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        activityResumed = true;
        Log.d(TAG, "onResume (nfcReadingActive=" + nfcReadingActive
            + ", tagBeingRead=" + isTagBeingRead() + ")");
        // Unconditionally, not just when !readerModeArmed. Resuming is precisely when the
        // framework re-applies reader-mode state and drops a binding made while this activity was
        // not the resumed one, so a stale "armed" flag here is what left the read deaf.
        if (nfcReadingActive && !isTagBeingRead()) {
            enableNfcReaderMode();
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        activityResumed = false;
        Log.d(TAG, "onPause (nfcReadingActive=" + nfcReadingActive + ")");
        disableNfcReaderMode();
    }

    @Override
    public void onDestroy() {
        nfcReadingActive = false;
        nfcCallbackContext = null;
        mrzScanCallbackContext = null;
        livenessCallbackContext = null;
        // Drop any unconsumed liveness payload rather than leaving biometric data in memory.
        LivenessCameraActivity.clearResult();
        dismissNfcDialog();
        super.onDestroy();
    }
}
