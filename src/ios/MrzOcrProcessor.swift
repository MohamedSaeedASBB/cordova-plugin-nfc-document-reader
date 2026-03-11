import Foundation

/// MRZ parsing result.
struct MrzCameraResult {
    let documentNumber: String
    let dateOfBirth: String
    let dateOfExpiry: String
    let rawLines: [String]
    let format: String
}

/// Processes text recognition results to extract MRZ data.
/// Supports TD1 (3 lines x 30 chars), TD2 (2 lines x 36 chars),
/// and TD3 (2 lines x 44 chars).
class MrzOcrProcessor {

    private static let ocrCorrections: [Character: Character] = [
        "O": "0",
        "I": "1",
        "S": "5",
        "B": "8",
        "G": "6",
        "D": "0"
    ]

    /// Process recognized text lines and extract MRZ data.
    func processLines(_ lines: [String]) -> MrzCameraResult? {
        var allLines: [String] = []

        for line in lines {
            let cleaned = cleanMrzLine(line)
            if cleaned.count >= 28 && isMrzLine(cleaned) {
                allLines.append(cleaned)
            }
        }

        if allLines.isEmpty { return nil }

        // Try TD3 (passport - 2 lines x 44 chars)
        let td3Lines = allLines.filter { $0.count >= 42 && $0.count <= 46 }
        if td3Lines.count >= 2 {
            let line1 = padRight(String(td3Lines[0].prefix(44)), to: 44)
            let line2 = padRight(String(td3Lines[1].prefix(44)), to: 44)
            if let result = parseTD3(line1: line1, line2: line2) {
                return result
            }
        }

        // Try TD1 (ID card - 3 lines x 30 chars)
        let td1Lines = allLines.filter { $0.count >= 28 && $0.count <= 32 }
        if td1Lines.count >= 3 {
            let line1 = padRight(String(td1Lines[0].prefix(30)), to: 30)
            let line2 = padRight(String(td1Lines[1].prefix(30)), to: 30)
            let line3 = padRight(String(td1Lines[2].prefix(30)), to: 30)
            if let result = parseTD1(line1: line1, line2: line2, line3: line3) {
                return result
            }
        }

        // Try TD2 (2 lines x 36 chars)
        let td2Lines = allLines.filter { $0.count >= 34 && $0.count <= 38 }
        if td2Lines.count >= 2 {
            let line1 = padRight(String(td2Lines[0].prefix(36)), to: 36)
            let line2 = padRight(String(td2Lines[1].prefix(36)), to: 36)
            if let result = parseTD2(line1: line1, line2: line2) {
                return result
            }
        }

        return nil
    }

    // MARK: - TD3

    private func parseTD3(line1: String, line2: String) -> MrzCameraResult? {
        guard line2.count >= 28 else { return nil }

        let chars = Array(line2)
        let docNumber = String(chars[0..<9]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
        let dob = correctNumericField(String(chars[13..<19]))
        let expiry = correctNumericField(String(chars[21..<27]))

        guard !docNumber.isEmpty else { return nil }

        return MrzCameraResult(
            documentNumber: docNumber,
            dateOfBirth: dob,
            dateOfExpiry: expiry,
            rawLines: [line1, line2],
            format: "TD3"
        )
    }

    // MARK: - TD1

    private func parseTD1(line1: String, line2: String, line3: String) -> MrzCameraResult? {
        guard line1.count >= 15, line2.count >= 14 else { return nil }

        let chars1 = Array(line1)
        let chars2 = Array(line2)

        // ICAO 9303 Part 5: positions 5-13 = doc number (9 chars), position 14 = check digit
        // If position 14 is '<', document number overflows into optional data (positions 15+)
        let docNumberBase = String(chars1[5..<14])
        let pos14 = chars1[14]
        let docNumber: String

        if pos14 == "<" || pos14 == Character("<") {
            // Extended document number — continues in optional data
            if line1.count > 15 {
                let optionalData = String(chars1[15...])
                if let fillerIdx = optionalData.firstIndex(of: "<"), fillerIdx > optionalData.startIndex {
                    let contAndCheck = String(optionalData[optionalData.startIndex..<fillerIdx])
                    if contAndCheck.count > 1 {
                        // Last char is check digit, rest is continuation
                        let continuation = String(contAndCheck.dropLast())
                        docNumber = (docNumberBase + continuation).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
                    } else {
                        docNumber = docNumberBase.replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
                    }
                } else {
                    docNumber = docNumberBase.replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
                }
            } else {
                docNumber = docNumberBase.replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            }
        } else {
            // Standard case — 9-char document number
            docNumber = docNumberBase.replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
        }

        let dob = correctNumericField(String(chars2[0..<6]))
        let expiry = correctNumericField(String(chars2[8..<14]))

        guard !docNumber.isEmpty else { return nil }

        return MrzCameraResult(
            documentNumber: docNumber,
            dateOfBirth: dob,
            dateOfExpiry: expiry,
            rawLines: [line1, line2, line3],
            format: "TD1"
        )
    }

    // MARK: - TD2

    private func parseTD2(line1: String, line2: String) -> MrzCameraResult? {
        guard line2.count >= 28 else { return nil }

        let chars = Array(line2)
        let docNumber = String(chars[0..<9]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
        let dob = correctNumericField(String(chars[13..<19]))
        let expiry = correctNumericField(String(chars[21..<27]))

        guard !docNumber.isEmpty else { return nil }

        return MrzCameraResult(
            documentNumber: docNumber,
            dateOfBirth: dob,
            dateOfExpiry: expiry,
            rawLines: [line1, line2],
            format: "TD2"
        )
    }

    // MARK: - Utility

    private func cleanMrzLine(_ raw: String) -> String {
        var cleaned = raw.uppercased()
        cleaned = cleaned.replacingOccurrences(of: " ", with: "")
        cleaned = cleaned.replacingOccurrences(of: "\u{00AB}", with: "<") // «
        cleaned = cleaned.replacingOccurrences(of: "\u{2039}", with: "<") // ‹
        cleaned = cleaned.replacingOccurrences(of: "(", with: "<")
        cleaned = cleaned.replacingOccurrences(of: ")", with: "")
        cleaned = cleaned.replacingOccurrences(of: "[", with: "<")
        cleaned = cleaned.replacingOccurrences(of: "]", with: "")
        cleaned = String(cleaned.filter { $0.isLetter || $0.isNumber || $0 == "<" })
        return cleaned
    }

    private func isMrzLine(_ line: String) -> Bool {
        return line.allSatisfy { $0.isUppercase || $0.isNumber || $0 == "<" }
    }

    private func correctNumericField(_ field: String) -> String {
        var corrected = ""
        for ch in field {
            if ch.isNumber || ch == "<" {
                corrected.append(ch)
            } else if ch.isLetter {
                if let replacement = MrzOcrProcessor.ocrCorrections[ch] {
                    corrected.append(replacement)
                } else {
                    corrected.append("0")
                }
            } else {
                corrected.append("0")
            }
        }
        while corrected.count < field.count { corrected.append("0") }
        if corrected.count > field.count { corrected = String(corrected.prefix(field.count)) }
        return corrected
    }

    private func padRight(_ s: String, to length: Int) -> String {
        var result = s
        while result.count < length { result.append("<") }
        return result
    }
}
