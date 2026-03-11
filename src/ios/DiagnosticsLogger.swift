import Foundation
#if canImport(UIKit)
import UIKit
#endif

/**
 * Silent fire-and-forget diagnostics logger for NFC read errors.
 * Posts error diagnostics + device info to Supabase for the plugin developer's debugging.
 * Never throws — all failures are silently caught.
 */
class DiagnosticsLogger {

    // ---- Supabase Configuration ----
    // Replace these with your Supabase project URL and anon key
    private static let supabaseURL = "https://REDACTED_SUPABASE_URL.supabase.co"
    private static let supabaseAnonKey = "REDACTED_SUPABASE_KEY"
    private static let pluginVersion = "1.0.0"
    private static let tableName = "nfc_diagnostics"

    /**
     * Log an NFC error to Supabase. Fire-and-forget on a background queue.
     */
    static func logError(
        errorCode: String,
        technicalError: String,
        userMessage: String,
        documentNumber: String? = nil,
        dateOfBirth: String? = nil,
        dateOfExpiry: String? = nil,
        paceInfo: String? = nil,
        nfcTechList: String? = nil
    ) {
        // Don't log if Supabase is not configured
        guard !supabaseURL.contains("YOUR_PROJECT"),
              !supabaseAnonKey.contains("YOUR_ANON") else {
            NSLog("[DiagnosticsLogger] Supabase not configured — skipping diagnostics log")
            return
        }

        DispatchQueue.global(qos: .background).async {
            do {
                let payload: [String: Any] = [
                    "error_code": errorCode,
                    "technical_error": truncate(technicalError, maxLen: 2000),
                    "user_message": userMessage,
                    "device_model": deviceModel(),
                    "os_version": osVersion(),
                    "app_package": bundleIdentifier(),
                    "nfc_tech_list": nfcTechList ?? "",
                    "mrz_masked": buildMaskedMrz(
                        docNum: documentNumber,
                        dob: dateOfBirth,
                        expiry: dateOfExpiry
                    ),
                    "pace_info": paceInfo ?? "",
                    "platform": "ios",
                    "plugin_version": pluginVersion,
                    "timestamp": isoTimestamp()
                ]

                postToSupabase(payload: payload)
            }
        }
    }

    // MARK: - PII Masking

    /// Mask a document number: show first 4 + last 2, mask middle.
    /// "113982506" → "1139***06"
    static func maskDocumentNumber(_ docNum: String?) -> String {
        guard let docNum = docNum, !docNum.isEmpty else { return "" }
        if docNum.count <= 4 { return "****" }
        if docNum.count <= 6 {
            let start = docNum.prefix(2)
            let end = docNum.suffix(1)
            return "\(start)***\(end)"
        }
        let start = docNum.prefix(4)
        let end = docNum.suffix(2)
        return "\(start)***\(end)"
    }

    /// Mask a date (YYMMDD): show first 2 + last 1, mask middle.
    /// "951102" → "95***2"
    static func maskDate(_ date: String?) -> String {
        guard let date = date, !date.isEmpty else { return "" }
        if date.count <= 3 { return "****" }
        let start = date.prefix(2)
        let end = date.suffix(1)
        return "\(start)***\(end)"
    }

    private static func buildMaskedMrz(docNum: String?, dob: String?, expiry: String?) -> String {
        return "doc:\(maskDocumentNumber(docNum)) dob:\(maskDate(dob)) exp:\(maskDate(expiry))"
    }

    // MARK: - HTTP

    private static func postToSupabase(payload: [String: Any]) {
        guard let url = URL(string: "\(supabaseURL)/rest/v1/\(tableName)") else {
            NSLog("[DiagnosticsLogger] Invalid Supabase URL")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(supabaseAnonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(supabaseAnonKey)", forHTTPHeaderField: "Authorization")
        request.setValue("return=minimal", forHTTPHeaderField: "Prefer")
        request.timeoutInterval = 10

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        } catch {
            NSLog("[DiagnosticsLogger] JSON serialization failed: \(error.localizedDescription)")
            return
        }

        let task = URLSession.shared.dataTask(with: request) { _, response, error in
            if let error = error {
                NSLog("[DiagnosticsLogger] HTTP POST failed: \(error.localizedDescription)")
                return
            }
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode >= 200 && httpResponse.statusCode < 300 {
                    NSLog("[DiagnosticsLogger] Diagnostics logged successfully")
                } else {
                    NSLog("[DiagnosticsLogger] Supabase returned HTTP \(httpResponse.statusCode)")
                }
            }
        }
        task.resume()
    }

    // MARK: - Utilities

    private static func deviceModel() -> String {
        #if canImport(UIKit)
        return UIDevice.current.model
        #else
        return "unknown"
        #endif
    }

    private static func osVersion() -> String {
        #if canImport(UIKit)
        return "iOS \(UIDevice.current.systemVersion)"
        #else
        return "iOS unknown"
        #endif
    }

    private static func bundleIdentifier() -> String {
        return Bundle.main.bundleIdentifier ?? "unknown"
    }

    private static func truncate(_ s: String, maxLen: Int) -> String {
        if s.count > maxLen {
            return String(s.prefix(maxLen)) + "..."
        }
        return s
    }

    private static func isoTimestamp() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date())
    }
}
