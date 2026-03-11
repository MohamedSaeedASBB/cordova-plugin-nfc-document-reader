package com.nfcdocumentreader;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Silent fire-and-forget diagnostics logger for NFC read errors.
 * Posts error diagnostics + device info to Supabase for the plugin developer's debugging.
 * Never throws exceptions — all failures are silently caught.
 */
public class DiagnosticsLogger {

    private static final String TAG = "DiagnosticsLogger";

    // ---- Supabase Configuration (obfuscated) ----
    // XOR-encoded to prevent plaintext extraction from decompiled APK
    private static final int[] OB_URL = {38, 18, 23, 52, 26, 91, 72, 97, 21, 9, 39, 3, 7, 9, 32, 9, 2, 55, 6, 5, 3, 58, 22, 1, 41, 3, 25, 1, 96, 21, 22, 52, 8, 3, 6, 61, 3, 77, 39, 6};
    private static final int[] OB_KEY = {61, 4, 60, 52, 28, 3, 11, 39, 21, 11, 37, 11, 13, 2, 17, 84, 37, 53, 90, 25, 53, 118, 55, 10, 8, 48, 56, 2, 33, 47, 59, 52, 11, 27, 9, 35, 55, 60, 40, 46, 11, 34, 123, 87, 5, 54};
    private static final int[] XOR_MASK = {78, 102, 99, 68, 105, 97, 103};
    private static final String PLUGIN_VERSION = "1.0.0";
    private static final String TABLE_NAME = "nfc_diagnostics";

    /** Decode an XOR-obfuscated int array back to a string at runtime. */
    private static String deobfuscate(int[] data) {
        char[] chars = new char[data.length];
        for (int i = 0; i < data.length; i++) {
            chars[i] = (char) (data[i] ^ XOR_MASK[i % XOR_MASK.length]);
        }
        return new String(chars);
    }

    private static String getSupabaseUrl() { return deobfuscate(OB_URL); }
    private static String getSupabaseKey() { return deobfuscate(OB_KEY); }

    /**
     * Log an NFC error to Supabase. Fire-and-forget — runs on a background daemon thread.
     *
     * @param context        Android context (for package name)
     * @param errorCode      Error code enum string (e.g. "AUTH_FAILED")
     * @param technicalError Full technical error message (for debugging)
     * @param userMessage    Friendly user-facing message
     * @param documentNumber Raw document number (will be masked)
     * @param dateOfBirth    Raw DOB in YYMMDD (will be masked)
     * @param dateOfExpiry   Raw expiry in YYMMDD (will be masked)
     * @param paceInfo       PACE debug info string (nullable)
     * @param nfcTechList    NFC technology list string (nullable)
     */
    public static void logError(
            Context context,
            String errorCode,
            String technicalError,
            String userMessage,
            String documentNumber,
            String dateOfBirth,
            String dateOfExpiry,
            String paceInfo,
            String nfcTechList
    ) {
        // Don't log if Supabase is not configured (check decoded values)
        String supabaseUrl = getSupabaseUrl();
        String supabaseKey = getSupabaseKey();
        if (supabaseUrl.contains("YOUR_PROJECT") || supabaseKey.contains("YOUR_ANON")) {
            Log.d(TAG, "Supabase not configured — skipping diagnostics log");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("error_code", safeStr(errorCode));
                payload.put("technical_error", truncate(safeStr(technicalError), 2000));
                payload.put("user_message", safeStr(userMessage));
                payload.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
                payload.put("os_version", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                payload.put("app_package", getPackageName(context));
                payload.put("nfc_tech_list", safeStr(nfcTechList));
                payload.put("mrz_masked", buildMaskedMrz(documentNumber, dateOfBirth, dateOfExpiry));
                payload.put("pace_info", safeStr(paceInfo));
                payload.put("platform", "android");
                payload.put("plugin_version", PLUGIN_VERSION);
                payload.put("timestamp", getIsoTimestamp());

                postToSupabase(payload);
            } catch (Exception e) {
                Log.w(TAG, "Failed to log diagnostics: " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // ---- PII Masking ----

    /**
     * Mask a document number: show first 4 + last 2, mask middle.
     * "113982506" → "1139***06"
     */
    static String maskDocumentNumber(String docNum) {
        if (docNum == null || docNum.isEmpty()) return "";
        if (docNum.length() <= 4) return "****";
        if (docNum.length() <= 6) return docNum.substring(0, 2) + "***" + docNum.substring(docNum.length() - 1);
        return docNum.substring(0, 4) + "***" + docNum.substring(docNum.length() - 2);
    }

    /**
     * Mask a date (YYMMDD): show first 2 + last 1, mask middle.
     * "951102" → "95***2"
     */
    static String maskDate(String date) {
        if (date == null || date.isEmpty()) return "";
        if (date.length() <= 3) return "****";
        return date.substring(0, 2) + "***" + date.substring(date.length() - 1);
    }

    private static String buildMaskedMrz(String docNum, String dob, String expiry) {
        return "doc:" + maskDocumentNumber(docNum)
                + " dob:" + maskDate(dob)
                + " exp:" + maskDate(expiry);
    }

    // ---- HTTP ----

    private static void postToSupabase(JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String supabaseUrl = getSupabaseUrl();
            String supabaseKey = getSupabaseKey();
            URL url = new URL(supabaseUrl + "/rest/v1/" + TABLE_NAME);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", supabaseKey);
            conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);
            conn.setRequestProperty("Prefer", "return=minimal");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                Log.d(TAG, "Diagnostics logged successfully");
            } else {
                Log.w(TAG, "Supabase returned HTTP " + responseCode);
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP POST failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Utilities ----

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static String getPackageName(Context context) {
        try {
            return context != null ? context.getPackageName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String getIsoTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
