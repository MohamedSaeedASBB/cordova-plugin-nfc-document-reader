package com.nfcdocumentreader;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
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

    private NfcAdapter nfcAdapter;
    private CallbackContext nfcCallbackContext;
    private CallbackContext mrzScanCallbackContext;
    private NfcDocumentReader documentReader;

    // MRZ data for BAC authentication
    private String pendingDocumentNumber;
    private String pendingDateOfBirth;
    private String pendingDateOfExpiry;
    private boolean nfcReadingActive = false;

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

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_MRZ_SCAN) {
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
    }

    // ==================== readNFC ====================

    private void readNFC(JSONArray args, CallbackContext callbackContext) {
        if (nfcAdapter == null) {
            callbackContext.error("NFC is not available on this device");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            callbackContext.error("NFC is not enabled. Please enable NFC in device settings.");
            return;
        }

        try {
            JSONObject mrzData = args.getJSONObject(0);
            pendingDocumentNumber = mrzData.getString("documentNumber");
            pendingDateOfBirth = mrzData.getString("dateOfBirth");
            pendingDateOfExpiry = mrzData.getString("dateOfExpiry");
        } catch (JSONException e) {
            callbackContext.error("Invalid MRZ data: " + e.getMessage());
            return;
        }

        nfcCallbackContext = callbackContext;
        nfcReadingActive = true;

        // Show the NFC scan bottom sheet
        showNfcDialog();

        // Send initial state
        sendProgressEvent("waitingForTag");

        // Enable foreground dispatch to receive NFC intents
        enableNfcForegroundDispatch();
    }

    private void cancelRead(CallbackContext callbackContext) {
        nfcReadingActive = false;
        disableNfcForegroundDispatch();
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
                            disableNfcForegroundDispatch();
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

    // ==================== NFC Intent Handling ====================

    @Override
    public void onNewIntent(Intent intent) {
        if (!nfcReadingActive || nfcCallbackContext == null) return;

        String action = intent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
            NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {

            Tag tag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
            } else {
                tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            }

            if (tag != null) {
                Log.d(TAG, "NFC tag discovered, starting read...");
                disableNfcForegroundDispatch();

                // Update dialog: tag found, reading started
                updateNfcDialogState(
                    "Reading Document",
                    "Keep the document still against your phone.\nDo not move it until reading is complete.",
                    "Connecting...",
                    "\uD83D\uDD04",  // 🔄
                    true
                );

                readTag(tag);
            }
        }
    }

    private void readTag(final Tag tag) {
        final CallbackContext callback = nfcCallbackContext;
        if (callback == null) return;

        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
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
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
                        pluginResult.setKeepCallback(false);
                        callback.sendPluginResult(pluginResult);
                    } else {
                        String error = documentReader.getError();

                        // Error — update dialog then dismiss
                        updateNfcDialogState(
                            "Error",
                            error != null ? error : "Unknown error",
                            "Failed",
                            "\u274C",  // ❌
                            false
                        );

                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

                        dismissNfcDialog();

                        callback.error(error != null ? error : "Unknown error reading document");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading NFC tag: " + e.getMessage(), e);
                    dismissNfcDialog();
                    callback.error("Error reading document: " + e.getMessage());
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

    // ==================== NFC Foreground Dispatch ====================

    private void enableNfcForegroundDispatch() {
        final Activity activity = cordova.getActivity();
        if (nfcAdapter == null || activity == null) return;

        // MUST run on UI thread — enableForegroundDispatch throws if called from background
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(activity, activity.getClass())
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        flags |= PendingIntent.FLAG_MUTABLE;
                    }
                    PendingIntent pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags);
                    String[][] techList = new String[][]{new String[]{"android.nfc.tech.IsoDep"}};

                    nfcAdapter.enableForegroundDispatch(activity, pendingIntent, null, techList);
                    Log.d(TAG, "NFC foreground dispatch enabled");
                } catch (Exception e) {
                    Log.e(TAG, "Error enabling NFC foreground dispatch: " + e.getMessage());
                }
            }
        });
    }

    private void disableNfcForegroundDispatch() {
        final Activity activity = cordova.getActivity();
        if (nfcAdapter == null || activity == null) return;

        // MUST run on UI thread
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    nfcAdapter.disableForegroundDispatch(activity);
                    Log.d(TAG, "NFC foreground dispatch disabled");
                } catch (Exception e) {
                    Log.e(TAG, "Error disabling NFC foreground dispatch: " + e.getMessage());
                }
            }
        });
    }

    // ==================== Lifecycle ====================

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        if (nfcReadingActive) {
            enableNfcForegroundDispatch();
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        disableNfcForegroundDispatch();
    }

    @Override
    public void onDestroy() {
        nfcReadingActive = false;
        nfcCallbackContext = null;
        mrzScanCallbackContext = null;
        dismissNfcDialog();
        super.onDestroy();
    }
}
