import Foundation
import UIKit
import Vision

/// Decides whether a captured photograph actually shows the document. Mirrors
/// DocumentEvidenceCheck.java, including what it deliberately does not use.
///
/// The Android note carries the full reasoning; the short version is that the two obvious signals
/// were measured on real captures and do not work. Text volume does not separate a document from a
/// desk — an ID card produced 8-9 recognised lines and a photographed keyboard produced 8. Face
/// detection does not either: the MRZ side of a card has no detectable face at the framing people
/// actually use, while a colleague sitting opposite supplies one that means nothing.
///
/// What separates them is the MRZ's shape, and the identifiers the flow already knows. The second
/// is the stronger claim: it establishes not "this is a document" but "this is the document just
/// read", which also rejects a photograph of a different genuine one.
///
/// It cannot tell a card from a photograph of a card, or from one on a screen — that needs capture
/// this plugin does not have. "confirmed" means the right text was in the frame.
enum DocumentEvidenceCheck {

    private static let mrzMinLength = 28
    private static let mrzMaxLength = 48
    private static let minIdentifierLength = 5

    struct Result {
        var status = "notConfirmed"
        var textLines = 0
        var mrzLines = 0
        var mrzFormat: String?
        var matchedIdentifiers: [String] = []
        var reasons: [String] = []

        var isConfirmed: Bool { return status == "confirmed" }

        var dictionary: [String: Any] {
            return [
                "status": status,
                "textLines": textLines,
                "mrzLines": mrzLines,
                "mrzFormat": mrzFormat ?? NSNull(),
                // Which identifiers matched, never their values: those are holder data and are
                // already elsewhere in the payload.
                "matchedIdentifiers": matchedIdentifiers,
                "reasons": reasons
            ]
        }
    }

    /// Recognises the text, then decides. `expected` holds "label:value" identifiers.
    static func inspect(_ image: UIImage, expected: [String]) -> Result {
        guard let cgImage = image.cgImage else {
            var result = Result()
            result.reasons.append("CHECK_NOT_RUN")
            return result
        }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false

        do {
            try VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])
        } catch {
            // A failed check must not read as a failed document.
            var result = Result()
            result.reasons.append("TEXT_RECOGNITION_FAILED")
            return result
        }

        let lines = (request.results ?? []).compactMap {
            ($0 as? VNRecognizedTextObservation)?.topCandidates(1).first?.string
        }
        return decide(lines: lines, mrzFormat: MrzOcrProcessor().processLines(lines)?.format,
                      expected: expected)
    }

    /// The decision itself, separated from how the text was obtained so it can be exercised
    /// against real photographs without a device.
    static func decide(lines: [String], mrzFormat: String?, expected: [String]) -> Result {
        var result = Result()
        result.mrzFormat = mrzFormat
        result.textLines = lines.count

        guard !lines.isEmpty else {
            result.reasons.append("NO_TEXT_FOUND")
            return result
        }

        result.mrzLines = lines.filter { looksLikeMrzLine($0) }.count
        let haystack = normalise(lines.joined(separator: "\n"))

        for labelled in expected {
            guard let colon = labelled.firstIndex(of: ":") else { continue }
            let label = String(labelled[labelled.startIndex..<colon])
            let needle = normalise(String(labelled[labelled.index(after: colon)...]))
            guard label.count > 0, needle.count >= minIdentifierLength else { continue }
            if haystack.contains(needle) { result.matchedIdentifiers.append(label) }
        }

        if !result.matchedIdentifiers.isEmpty || result.mrzFormat != nil || result.mrzLines > 0 {
            result.status = "confirmed"
        } else {
            // Deliberately not graded by how much text there is: a keyboard photographed at a desk
            // produced as many lines as the ID card did, so "plenty of text" is not evidence.
            result.reasons.append(expected.isEmpty
                ? "NO_MRZ_FOUND"
                : "NO_MRZ_OR_KNOWN_IDENTIFIER_FOUND")
        }
        return result
    }

    /// Monospaced, filler-padded, from the MRZ alphabet — a shape nothing on a desk has.
    private static func looksLikeMrzLine(_ line: String) -> Bool {
        let cleaned = line.replacingOccurrences(of: " ", with: "").uppercased()
        guard cleaned.count >= mrzMinLength, cleaned.count <= mrzMaxLength,
              cleaned.contains("<") else { return false }

        let allowed = cleaned.unicodeScalars.filter {
            ($0 >= "A" && $0 <= "Z") || ($0 >= "0" && $0 <= "9") || $0 == "<"
        }.count
        return allowed * 10 >= cleaned.count * 9        // at least 90% MRZ alphabet
    }

    /// Case, spacing and punctuation carry no meaning for this comparison.
    private static func normalise(_ value: String) -> String {
        return value.uppercased().unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init).joined()
    }
}
