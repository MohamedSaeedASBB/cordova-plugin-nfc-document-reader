package com.nfcdocumentreader;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Local-only diagnostics for NFC read failures.
 *
 * WHAT THIS USED TO DO, AND WHY IT NO LONGER DOES
 * Earlier revisions POSTed every read failure to a third-party Supabase project belonging to the
 * plugin's developer, carrying a partially masked document number, a partially masked date of
 * birth and expiry, the host app's package name, the device model and the PACE debug string. The
 * endpoint and its API key were XOR-obfuscated in the source with the stated aim of keeping them
 * out of a decompiled APK.
 *
 * That is customer data leaving the bank to a service the bank does not control, and the masking
 * did not make it anonymous: four leading and two trailing digits of a nine-digit document number
 * leave a thousand candidates, which a birth year and an app package name narrow to one.
 *
 * So the network path is gone. Nothing in this plugin opens a socket. Diagnostics are written to
 * the device log, where a developer with the handset can read them, and the same detail is
 * returned to the caller in the error payload — so an app that wants telemetry sends it through
 * its own audited backend rather than through a channel hidden inside a plugin.
 *
 * Do not reintroduce an endpoint here. If diagnostics must leave the device, they leave through
 * the host application, to a bank-controlled destination, under the bank's own retention rules.
 */
public class DiagnosticsLogger {

    private static final String TAG = "NfcDiagnostics";

    private DiagnosticsLogger() {
    }

    /**
     * Record a read failure locally. The signature is unchanged from the version that posted
     * externally, so call sites did not have to be rewritten to become safe.
     *
     * The MRZ arguments are accepted and deliberately not logged. They identify the customer, and
     * a device log is readable by anything with ADB access. They stay in the parameter list
     * because removing them would silently change what callers pass rather than making the
     * decision visible here.
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
        try {
            Log.w(TAG, "NFC read failed"
                    + " | code=" + safeStr(errorCode)
                    + " | device=" + Build.MANUFACTURER + " " + Build.MODEL
                    + " | os=Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + " | tech=" + safeStr(nfcTechList)
                    + " | pace=" + safeStr(paceInfo));
            // Separate line: a reader's error text can be long, and the fields above stay greppable.
            Log.w(TAG, "NFC read failed | detail=" + truncate(safeStr(technicalError), 2000));
        } catch (Exception e) {
            // Diagnostics must never be the reason a read fails.
            Log.w(TAG, "Could not write diagnostics: " + e.getClass().getSimpleName());
        }
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
