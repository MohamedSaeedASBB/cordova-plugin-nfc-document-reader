package com.nfcdocumentreader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    @Override
    protected void pluginInitialize() {
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

        // Send initial state
        sendProgressEvent("waitingForTag");

        // Enable foreground dispatch to receive NFC intents
        enableNfcForegroundDispatch();
    }

    private void cancelRead(CallbackContext callbackContext) {
        nfcReadingActive = false;
        disableNfcForegroundDispatch();

        if (nfcCallbackContext != null) {
            nfcCallbackContext.error("NFC reading cancelled");
            nfcCallbackContext = null;
        }

        callbackContext.success("Cancelled");
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
                            }

                            @Override
                            public void onReadingDataGroup(int dgNumber, String dgName) {
                                sendDataGroupProgress(dgNumber, dgName);
                            }
                        });

                    DocumentData data = documentReader.getResult();
                    if (data != null) {
                        JSONObject result = data.toJSON();
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
                        pluginResult.setKeepCallback(false);
                        callback.sendPluginResult(pluginResult);
                    } else {
                        String error = documentReader.getError();
                        callback.error(error != null ? error : "Unknown error reading document");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading NFC tag: " + e.getMessage(), e);
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
        Activity activity = cordova.getActivity();
        if (nfcAdapter == null || activity == null) return;

        Intent intent = new Intent(activity, activity.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags);
        String[][] techList = new String[][]{new String[]{"android.nfc.tech.IsoDep"}};

        try {
            nfcAdapter.enableForegroundDispatch(activity, pendingIntent, null, techList);
            Log.d(TAG, "NFC foreground dispatch enabled");
        } catch (Exception e) {
            Log.e(TAG, "Error enabling NFC foreground dispatch: " + e.getMessage());
        }
    }

    private void disableNfcForegroundDispatch() {
        Activity activity = cordova.getActivity();
        if (nfcAdapter == null || activity == null) return;

        try {
            nfcAdapter.disableForegroundDispatch(activity);
            Log.d(TAG, "NFC foreground dispatch disabled");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling NFC foreground dispatch: " + e.getMessage());
        }
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
        super.onDestroy();
    }
}
