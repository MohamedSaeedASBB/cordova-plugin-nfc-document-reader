import Foundation

/// Compares the MRZ printed on the document against the MRZ the chip carries in DG1.
///
/// WHAT THIS CATCHES, AND WHAT IT DOES NOT
/// A document whose printed data has been altered while its chip is genuine — or a genuine chip
/// moved into a forged card — shows up here as a disagreement between the two. Passive
/// authentication proves the chip's data is what the issuing state signed; this proves the plastic
/// in the customer's hand says the same thing.
///
/// Three fields cannot disagree by construction: the document number, date of birth and date of
/// expiry are what the BAC/PACE key is derived from, so a chip that opened at all already agreed
/// with the scanned values. They are still reported, as evidence rather than as a test.
///
/// The fields that can genuinely differ are the names, nationality, issuing state and document
/// code. Those are also the ones an OCR misread can corrupt, which is why a mismatch is reported
/// as a finding for a human rather than as proof of fraud: a smudged character on a worn card
/// produces the same signal as a tampered one.
///
/// The Android side parses the scanned lines with jmrtd's own MRZInfo — the parser that also read
/// the chip's DG1. iOS has no such parser to borrow, so the layout is implemented here from
/// ICAO 9303 directly. Both sides must agree field for field, which is what the offsets below are
/// checked against: the same scanned string has to produce the same comparison on either platform.
enum MrzChipComparison {

    /// - Parameters:
    ///   - rawMrzLines: the MRZ as scanned, lines separated by " | ", newlines, or nothing at all
    ///   - chip: the chip payload already assembled from DG1
    static func compare(rawMrzLines: String?, chip: [String: Any]?) -> [String: Any] {
        var comparison: [String: Any] = [
            "status": "notCompared",
            "mismatches": [],
            "fieldsCompared": []
        ]

        guard let rawMrzLines = rawMrzLines,
              !rawMrzLines.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let chip = chip else {
            comparison["reason"] = "NO_SCANNED_MRZ"
            return comparison
        }

        let joined = rawMrzLines
            .replacingOccurrences(of: "|", with: "")
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()

        guard let scanned = ScannedMrz(joined: joined) else {
            // A partial or misread scan is not a mismatch: there is nothing to compare.
            comparison["reason"] = "SCANNED_MRZ_NOT_PARSEABLE"
            return comparison
        }

        var mismatches: [[String: Any]] = []
        var compared: [String] = []

        func string(_ key: String) -> String { chip[key] as? String ?? "" }

        compareField(&compared, &mismatches, "documentNumber",
                     scanned.documentNumber, string("documentNumber"))
        compareField(&compared, &mismatches, "dateOfBirth",
                     scanned.dateOfBirth, string("dateOfBirth"))
        compareField(&compared, &mismatches, "dateOfExpiry",
                     scanned.dateOfExpiry, string("dateOfExpiry"))
        // Surname and given names are compared as one string, not as two fields. The MRZ separates
        // them with "<<", and a single chevron misread as a letter — "ISA<<SHEREEN" scanned as
        // "ISAK<SHEREEN" — moves the whole name into the surname and empties the given names.
        // Compared separately that reads as two catastrophic mismatches; compared as one name it
        // is what it actually is, a single wrong character.
        compareField(&compared, &mismatches, "name",
                     join(scanned.primaryIdentifier, scanned.secondaryIdentifier),
                     join(string("primaryIdentifier"), string("secondaryIdentifier")))
        compareField(&compared, &mismatches, "nationality",
                     scanned.nationality, string("nationality"))
        compareField(&compared, &mismatches, "issuingState",
                     scanned.issuingState, string("issuingState"))
        compareField(&compared, &mismatches, "documentType",
                     scanned.documentCode, string("documentType"))

        comparison["fieldsCompared"] = compared
        comparison["mismatches"] = mismatches
        comparison["status"] = mismatches.isEmpty ? "matched" : "mismatch"
        if !mismatches.isEmpty {
            // A mismatch cannot be judged without seeing what was actually read off the card.
            // Everything here already appears elsewhere in the payload.
            comparison["scannedMrz"] = joined
        }
        // Says plainly why three of these could never have disagreed, so nobody reads the match as
        // stronger evidence than it is.
        comparison["note"] = "documentNumber, dateOfBirth and dateOfExpiry derive the chip access "
            + "key, so a successful read already agreed with them. A mismatch elsewhere may equally "
            + "be an OCR misread of worn print: check 'distance' before treating one as evidence of "
            + "tampering."

        return comparison
    }

    private static func compareField(_ compared: inout [String],
                                     _ mismatches: inout [[String: Any]],
                                     _ field: String, _ scanned: String, _ chip: String) {
        let a = normalise(scanned)
        let b = normalise(chip)
        if a.isEmpty || b.isEmpty { return }   // absent on one side is not a disagreement

        compared.append(field)
        if a == b { return }

        mismatches.append([
            "field": field,
            "printed": scanned,
            "chip": chip,
            // How far apart, so a caller can tell one misread character from a different person.
            // A single chevron read as a letter costs 1; a substituted name costs most of its
            // length. What counts as tolerable is a policy decision and is left to the backend.
            "distance": editDistance(a, b),
            "comparedLength": max(a.count, b.count)
        ])
    }

    private static func join(_ first: String, _ second: String) -> String {
        return (first + " " + second).trimmingCharacters(in: .whitespaces)
    }

    /// Levenshtein distance, on the normalised forms.
    static func editDistance(_ a: String, _ b: String) -> Int {
        let x = Array(a), y = Array(b)
        var previous = Array(0...y.count)
        var current = [Int](repeating: 0, count: y.count + 1)

        for i in 1...max(x.count, 1) where !x.isEmpty {
            current[0] = i
            for j in 1...max(y.count, 1) where !y.isEmpty {
                let substitution = previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1)
                current[j] = min(substitution, min(previous[j] + 1, current[j - 1] + 1))
            }
            swap(&previous, &current)
        }
        return previous[y.count]
    }

    /// MRZ filler characters, spacing and case carry no meaning; a difference in them is not one.
    static func normalise(_ value: String) -> String {
        return String(value.uppercased().filter { $0.isLetter || $0.isNumber })
    }
}

/// The scanned MRZ, read by ICAO 9303 offsets. The format follows from the total length, which is
/// what the caller has after the scanner has padded each line to its full width: TD1 is 3x30, TD2
/// 2x36, TD3 2x44. Anything else is a partial read, and a partial read is not something to compare.
struct ScannedMrz {

    let documentCode: String
    let issuingState: String
    let documentNumber: String
    let dateOfBirth: String
    let dateOfExpiry: String
    let nationality: String
    let primaryIdentifier: String
    let secondaryIdentifier: String

    init?(joined: String) {
        let c = Array(joined)

        func field(_ from: Int, _ to: Int) -> String {
            guard from >= 0, to <= c.count, from < to else { return "" }
            return String(c[from..<to])
        }
        /// Strips the filler used to pad a field out to its fixed width.
        func trimmed(_ from: Int, _ to: Int) -> String {
            return field(from, to)
                .replacingOccurrences(of: "<", with: " ")
                .trimmingCharacters(in: .whitespaces)
        }

        switch c.count {
        case 90:    // TD1 — 3 lines of 30
            documentCode = trimmed(0, 2)
            issuingState = trimmed(2, 5)
            // Position 14 holds the document number's check digit. A '<' there means the number
            // was too long for its nine characters and runs on into the optional data, ending
            // with its check digit before the first filler. Algerian cards do this.
            documentNumber = ScannedMrz.documentNumber(base: field(5, 14),
                                                       checkDigit: field(14, 15),
                                                       overflow: field(15, 30))
            dateOfBirth = field(30, 36)
            dateOfExpiry = field(38, 44)
            nationality = trimmed(45, 48)
            let names = ScannedMrz.splitName(field(60, 90))
            primaryIdentifier = names.0
            secondaryIdentifier = names.1

        case 72:    // TD2 — 2 lines of 36
            documentCode = trimmed(0, 2)
            issuingState = trimmed(2, 5)
            let names = ScannedMrz.splitName(field(5, 36))
            primaryIdentifier = names.0
            secondaryIdentifier = names.1
            documentNumber = trimmed(36, 45)
            nationality = trimmed(46, 49)
            dateOfBirth = field(49, 55)
            dateOfExpiry = field(57, 63)

        case 88:    // TD3 — 2 lines of 44
            documentCode = trimmed(0, 2)
            issuingState = trimmed(2, 5)
            let names = ScannedMrz.splitName(field(5, 44))
            primaryIdentifier = names.0
            secondaryIdentifier = names.1
            // Same overflow rule as TD1, here running into the personal number field.
            documentNumber = ScannedMrz.documentNumber(base: field(44, 53),
                                                       checkDigit: field(53, 54),
                                                       overflow: field(72, 86))
            nationality = trimmed(54, 57)
            dateOfBirth = field(57, 63)
            dateOfExpiry = field(65, 71)

        default:
            return nil
        }
    }

    /// Splits "SURNAME<<GIVEN<NAMES<<<<" into its two halves.
    private static func splitName(_ raw: String) -> (String, String) {
        let parts = raw.components(separatedBy: "<<")
        let primary = parts.first ?? ""
        let secondary = parts.count > 1 ? parts[1...].joined(separator: "<<") : ""
        func clean(_ s: String) -> String {
            return s.replacingOccurrences(of: "<", with: " ")
                .trimmingCharacters(in: .whitespaces)
        }
        return (clean(primary), clean(secondary))
    }

    /// The document number, including the overflow form where it is longer than nine characters.
    private static func documentNumber(base: String, checkDigit: String, overflow: String) -> String {
        func strip(_ s: String) -> String {
            return s.replacingOccurrences(of: "<", with: " ").trimmingCharacters(in: .whitespaces)
        }
        guard checkDigit == "<" else { return strip(base) }

        // Everything before the first filler is the continuation plus its check digit.
        let head = overflow.components(separatedBy: "<").first ?? ""
        guard head.count > 1 else { return strip(base) }
        return strip(base) + String(head.dropLast())
    }
}
