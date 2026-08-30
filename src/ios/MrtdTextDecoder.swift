import Foundation

/// Recovers DG11/DG12 text that UTF-8 decoding could not read. Mirrors MrtdTextDecoder.java.
///
/// ICAO 9303 specifies UTF-8 for these fields and NFCPassportReader decodes them that way. Some
/// issuers do not comply: an Algerian ID observed in testing stores its Arabic fields in a
/// single-byte Arabic code page, so every Arabic letter is one byte that is not valid UTF-8.
///
/// The failure looks different on the two platforms and is easy to misread here. Android's decoder
/// substitutes U+FFFD per bad byte, so the damage is visible as boxes. Swift's
/// `String(bytes:encoding:.utf8)` returns nil instead, so the same document simply arrives with
/// empty fields — which reads as "this document has no place of birth" rather than as an error.
/// Detection therefore works from the bytes rather than from the decoded string: a tag that is
/// present but will not decode as UTF-8 is damaged.
///
/// The bytes themselves are sound — passive authentication hashes them against the issuer's
/// signature — so the text is simply parsed out of the data group again and decoded properly.
enum MrtdTextDecoder {

    /// Tried in order. A conformant document never reaches the fallbacks.
    private static var candidateEncodings: [(name: String, encoding: String.Encoding)] {
        var candidates: [(String, String.Encoding)] = [("UTF-8", .utf8)]
        // Neither Arabic code page is in String.Encoding, so both come from CoreFoundation.
        let windows1256 = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.windowsArabic.rawValue))
        let iso88596 = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.isoLatinArabic.rawValue))
        if windows1256 != kCFStringEncodingInvalidId {
            candidates.append(("windows-1256", String.Encoding(rawValue: windows1256)))
        }
        if iso88596 != kCFStringEncodingInvalidId {
            candidates.append(("ISO-8859-6", String.Encoding(rawValue: iso88596)))
        }
        return candidates
    }

    // DG11
    static let tagFullName         = 0x5F0E
    static let tagOtherNames       = 0x5F0F
    static let tagPlaceOfBirth     = 0x5F11
    static let tagPermanentAddress = 0x5F42
    static let tagTelephone        = 0x5F12
    static let tagPersonalSummary  = 0x5F15
    // DG12
    static let tagIssuingAuthority = 0x5F19
    static let tagEndorsements     = 0x5F1B

    /// Text recovered from one document, plus the encoding it took.
    struct Recovered {
        var fields: [Int: String] = [:]
        var encoding: String?
    }

    /// - Parameters:
    ///   - dg11: raw DG11 bytes, or nil
    ///   - dg12: raw DG12 bytes, or nil
    /// - Returns: recovered text keyed by tag. `encoding` is nil when the document was conformant,
    ///   in which case the caller should keep the library's own values.
    static func recover(dg11: [UInt8]?, dg12: [UInt8]?) -> Recovered {
        var result = Recovered()

        let textTags: Set<Int> = [tagFullName, tagOtherNames, tagPlaceOfBirth, tagPermanentAddress,
                                  tagTelephone, tagPersonalSummary, tagIssuingAuthority,
                                  tagEndorsements]
        var values: [Int: [UInt8]] = [:]
        for raw in [dg11, dg12] {
            guard let raw = raw else { continue }
            for (tag, tagValues) in parseTlv(raw) where textTags.contains(tag) {
                if let first = tagValues.first, values[tag] == nil { values[tag] = first }
            }
        }
        guard !values.isEmpty else { return result }

        // Damaged means "present but not valid UTF-8".
        let damaged = values.filter { String(bytes: $0.value, encoding: .utf8) == nil }
        guard !damaged.isEmpty else { return result }

        guard let chosen = chooseEncoding(Array(damaged.values)) else { return result }
        for (tag, bytes) in values {
            if let text = String(bytes: bytes, encoding: chosen.encoding) {
                result.fields[tag] = text
            }
        }
        result.encoding = chosen.name
        NSLog("[MrtdTextDecoder] Recovered DG11/DG12 text using %@ after UTF-8 decoding failed."
              + " Verify against the physical document.", chosen.name)
        return result
    }

    /// One encoding for the whole document, scored across every damaged field together. Choosing
    /// per field lets two code pages that both decode the other's bytes into plausible Arabic
    /// disagree between neighbouring fields — see the Java implementation's note.
    static func chooseEncoding(_ values: [[UInt8]]) -> (name: String, encoding: String.Encoding)? {
        var best: (name: String, encoding: String.Encoding)?
        var bestScore = Int.min

        for candidate in candidateEncodings {
            var total = 0
            var usable = true
            for value in values {
                guard let text = String(bytes: value, encoding: candidate.encoding) else {
                    usable = false
                    break
                }
                total += score(text)
            }
            guard usable else { continue }
            if candidate.name == "UTF-8" { return candidate }
            if total > bestScore {
                bestScore = total
                best = candidate
            }
        }
        return best
    }

    /// Arabic letters count for, replacement and control characters against.
    private static func score(_ text: String) -> Int {
        var score = 0
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0600...0x06FF: score += 2               // Arabic block
            case 0xFFFD:          score -= 5
            case 0x00...0x1F, 0x7F: score -= 3
            case 0x20..<0x7F:     score += 1               // printable ASCII, e.g. "<<"
            default: break
            }
        }
        return score
    }

    /// DG11 separates a field's components with '<'; runs of them are one separator.
    static func splitComponents(_ value: String) -> [String] {
        return value.components(separatedBy: "<")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    // MARK: - BER-TLV

    /// Flattens a data group into tag -> values, descending into constructed tags.
    static func parseTlv(_ data: [UInt8]) -> [Int: [[UInt8]]] {
        var out: [Int: [[UInt8]]] = [:]
        walk(data, 0, data.count, &out, 0)
        return out
    }

    private static func walk(_ data: [UInt8], _ start: Int, _ end: Int,
                             _ out: inout [Int: [[UInt8]]], _ depth: Int) {
        if depth > 8 { return }                                  // structural loop guard
        var index = start
        while index < end {
            let first = Int(data[index])
            if first == 0x00 || first == 0xFF { index += 1; continue }   // padding

            var tag = first
            index += 1
            if (first & 0x1F) == 0x1F {                          // multi-byte tag, e.g. 0x5F0E
                var more = true
                while more {
                    if index >= end { return }
                    tag = (tag << 8) | Int(data[index])
                    more = (data[index] & 0x80) != 0
                    index += 1
                }
            }

            if index >= end { return }
            let lengthByte = Int(data[index]); index += 1
            var length = 0
            if lengthByte < 0x80 {
                length = lengthByte
            } else {
                let lengthBytes = lengthByte & 0x7F
                if lengthBytes == 0 || lengthBytes > 4 || index + lengthBytes > end { return }
                for _ in 0..<lengthBytes {
                    length = (length << 8) | Int(data[index])
                    index += 1
                }
            }
            if length < 0 || index + length > end { return }

            if (first & 0x20) != 0 {                             // constructed
                walk(data, index, index + length, &out, depth + 1)
            } else {
                out[tag, default: []].append(Array(data[index..<(index + length)]))
            }
            index += length
        }
    }
}
