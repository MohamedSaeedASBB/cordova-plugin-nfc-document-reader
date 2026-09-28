import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Local-only diagnostics for NFC read failures.
///
/// WHAT THIS USED TO DO, AND WHY IT NO LONGER DOES
/// Earlier revisions POSTed every read failure to a third-party Supabase project belonging to the
/// plugin's developer, carrying a partially masked document number, a partially masked date of
/// birth and expiry, the bundle identifier, the device model and the PACE debug string. The
/// endpoint and its API key were XOR-obfuscated in the source with the stated aim of keeping them
/// out of the shipped binary.
///
/// That is customer data leaving the bank to a service the bank does not control, and the masking
/// did not make it anonymous: four leading and two trailing digits of a nine-digit document number
/// leave a thousand candidates, which a birth year and a bundle identifier narrow to one.
///
/// So the network path is gone. Nothing in this plugin opens a socket. Diagnostics go to the
/// device log, and the same detail is returned to the caller in the error payload — so an app that
/// wants telemetry sends it through its own audited backend rather than through a channel hidden
/// inside a plugin.
///
/// Do not reintroduce an endpoint here. If diagnostics must leave the device, they leave through
/// the host application, to a bank-controlled destination, under the bank's own retention rules.
enum DiagnosticsLogger {

    /// Record a read failure locally. The signature is unchanged from the version that posted
    /// externally, so call sites did not have to be rewritten to become safe.
    ///
    /// The MRZ arguments are accepted and deliberately not logged. They identify the customer, and
    /// the device log is readable by anything with the handset attached. They stay in the
    /// parameter list because removing them would silently change what callers pass rather than
    /// making the decision visible here.
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
        NSLog("[NfcDiagnostics] NFC read failed | code=%@ | device=%@ | os=%@ | tech=%@ | pace=%@",
              errorCode, deviceModel(), osVersion(), nfcTechList ?? "", paceInfo ?? "")
        // Separate line: a reader's error text can be long, and the fields above stay greppable.
        NSLog("[NfcDiagnostics] NFC read failed | detail=%@", truncate(technicalError, maxLen: 2000))
    }

    private static func truncate(_ s: String, maxLen: Int) -> String {
        return s.count > maxLen ? String(s.prefix(maxLen)) + "..." : s
    }

    private static func deviceModel() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let identifier = withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(validatingUTF8: $0) ?? "" }
        }
        return identifier.isEmpty ? "Apple" : "Apple \(identifier)"
    }

    private static func osVersion() -> String {
        #if canImport(UIKit)
        return "iOS \(UIDevice.current.systemVersion)"
        #else
        return "iOS"
        #endif
    }
}
