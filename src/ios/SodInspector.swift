import Foundation
import Security

/// Reads the parts of EF.SOD that NFCPassportReader keeps to itself.
///
/// WHY THIS EXISTS
/// The library parses all of this already — `SOD.getEncapsulatedContentDigestAlgorithm()`,
/// `SOD.getSignatureAlgorithm()`, and the signer certificate extracted in
/// `validateAndExtractSigningCertificates` — but `SOD` is internal to that module, and the
/// certificate is only ever extracted when a CSCA master list is supplied. A build with no trust
/// store installed therefore reported the digest algorithm, the signature algorithm and the
/// document signer as null, while Android reported all three from the same card, because jmrtd
/// exposes them whether or not a trust store exists.
///
/// Those are not trust decisions. Which algorithm signed a document, and who signed it, are facts
/// about the document that an audit trail wants recorded even when — especially when — the issuer
/// could not be confirmed. So they are read here directly from the DER.
///
/// This parses. It does not verify: nothing in this file decides whether a signature is good, and
/// nothing here should ever be used to. Verification stays with the library and OpenSSL.
enum SodInspector {

    struct Details {
        var digestAlgorithm: String?
        var signatureAlgorithm: String?
        var signerSubject: String?
    }

    static func inspect(sod: [UInt8]?) -> Details {
        var details = Details()
        guard let sod = sod, !sod.isEmpty else { return details }

        // EF.SOD wraps the CMS ContentInfo in an application-specific tag 0x77.
        var der = ArraySlice(sod)
        if let wrapper = Der.first(in: der, tag: 0x77) { der = wrapper.value }

        // ContentInfo ::= SEQUENCE { contentType OID, content [0] EXPLICIT SignedData }
        guard let contentInfo = Der.first(in: der, tag: 0x30),
              let explicit = Der.children(of: contentInfo.value).first(where: { $0.tag == 0xA0 }),
              let signedData = Der.first(in: explicit.value, tag: 0x30) else { return details }

        let fields = Der.children(of: signedData.value)

        // SignedData ::= SEQUENCE { version, digestAlgorithms SET OF AlgorithmIdentifier, ... }
        if let digestAlgorithms = fields.first(where: { $0.tag == 0x31 }),
           let algorithm = Der.children(of: digestAlgorithms.value).first,
           let oid = Der.children(of: algorithm.value).first(where: { $0.tag == 0x06 }) {
            details.digestAlgorithm = OID.name(for: Der.oidString(oid.value))
        }

        // certificates [0] IMPLICIT CertificateSet — the document signer is the first.
        if let certificates = fields.first(where: { $0.tag == 0xA0 }),
           let certificate = Der.children(of: certificates.value).first(where: { $0.tag == 0x30 }) {
            details.signerSubject = subjectName(ofCertificate: certificate.element)
        }

        // signerInfos SET OF SignerInfo — the last SET in the sequence, after digestAlgorithms.
        if let signerInfos = fields.last(where: { $0.tag == 0x31 }),
           let signerInfo = Der.children(of: signerInfos.value).first(where: { $0.tag == 0x30 }) {
            // SignerInfo ::= SEQUENCE { version, sid, digestAlgorithm, [0] signedAttrs OPTIONAL,
            //                           signatureAlgorithm, signature, ... }
            // The signature algorithm is the AlgorithmIdentifier that follows the signed
            // attributes, so it is the second plain SEQUENCE after them — taking "the last
            // AlgorithmIdentifier before the signature OCTET STRING" finds it without counting.
            let parts = Der.children(of: signerInfo.value)
            if let signatureIndex = parts.firstIndex(where: { $0.tag == 0x04 }),
               signatureIndex > 0 {
                let candidate = parts[signatureIndex - 1]
                if candidate.tag == 0x30,
                   let oid = Der.children(of: candidate.value).first(where: { $0.tag == 0x06 }) {
                    details.signatureAlgorithm = OID.name(for: Der.oidString(oid.value))
                }
            }
        }

        return details
    }

    /// The subject distinguished name, rendered the way jmrtd renders it on Android so the two
    /// platforms produce the same string for the same certificate: attributes in the order the
    /// certificate stores them, comma separated — country first, common name last.
    private static func subjectName(ofCertificate certificate: ArraySlice<UInt8>) -> String? {
        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        // TBSCertificate ::= SEQUENCE { [0] version, serialNumber, signature, issuer, validity,
        //                               subject, ... } — subject is the second Name.
        guard let cert = Der.first(in: certificate, tag: 0x30),
              let tbs = Der.children(of: cert.value).first(where: { $0.tag == 0x30 }) else {
            return nil
        }
        let parts = Der.children(of: tbs.value)
        let names = parts.filter { $0.tag == 0x30 }
        // issuer, then validity (also a SEQUENCE), then subject. Validity holds two time values,
        // which is what tells it apart from a Name.
        let distinguishedNames = names.filter { name in
            let children = Der.children(of: name.value)
            return !children.isEmpty && children.allSatisfy { $0.tag == 0x31 }
        }
        guard distinguishedNames.count >= 2 else { return nil }
        return render(name: distinguishedNames[1])
    }

    private static func render(name: Der.Element) -> String? {
        var attributes: [String] = []
        for rdn in Der.children(of: name.value) where rdn.tag == 0x31 {
            for pair in Der.children(of: rdn.value) where pair.tag == 0x30 {
                let fields = Der.children(of: pair.value)
                guard let oid = fields.first(where: { $0.tag == 0x06 }),
                      let value = fields.first(where: { $0.tag != 0x06 }) else { continue }
                let label = OID.attributeName(for: Der.oidString(oid.value))
                let text = String(bytes: value.value, encoding: .utf8)
                    ?? String(bytes: value.value, encoding: .isoLatin1)
                guard let text = text, !text.isEmpty else { continue }
                attributes.append("\(label)=\(text)")
            }
        }
        guard !attributes.isEmpty else { return nil }
        // Encounter order, which is what both jmrtd and openssl print. An earlier draft reversed
        // this on the assumption that RFC 2253's most-specific-first applied; the Android payload
        // for a real Algerian signer reads "C=DZ, SERIALNUMBER=..., CN=DZ-DS-HSM", and openssl
        // agrees, so reversing would have made the two platforms disagree about the same
        // certificate while both looked correct in isolation.
        return attributes.joined(separator: ", ")
    }
}

/// A minimal DER reader. Enough to walk a known structure, and deliberately no more — it does not
/// validate, does not decode values, and treats anything malformed as absent.
enum Der {

    struct Element {
        let tag: UInt8
        /// The whole element including its header, for handing to another parser.
        let element: ArraySlice<UInt8>
        let value: ArraySlice<UInt8>
    }

    /// The first element with this tag at the top level of `data`.
    static func first(in data: ArraySlice<UInt8>, tag: UInt8) -> Element? {
        return children(of: data).first { $0.tag == tag }
    }

    /// Every element at the top level of `data`.
    static func children(of data: ArraySlice<UInt8>) -> [Element] {
        var out: [Element] = []
        var i = data.startIndex
        // A hard ceiling: a malformed length could otherwise spin here on a hostile document.
        var guardCount = 0
        while i < data.endIndex, guardCount < 4096 {
            guardCount += 1
            let start = i
            let tag = data[i]
            i = data.index(after: i)
            // Multi-byte tags are not used by any structure this walks.
            guard i < data.endIndex else { break }

            var length = Int(data[i])
            i = data.index(after: i)
            if length & 0x80 != 0 {
                let count = length & 0x7F
                guard count > 0, count <= 4, data.distance(from: i, to: data.endIndex) >= count else { break }
                length = 0
                for _ in 0..<count {
                    length = (length << 8) | Int(data[i])
                    i = data.index(after: i)
                }
            }
            guard length >= 0, data.distance(from: i, to: data.endIndex) >= length else { break }
            let end = data.index(i, offsetBy: length)
            out.append(Element(tag: tag, element: data[start..<end], value: data[i..<end]))
            i = end
        }
        return out
    }

    /// Dotted-decimal form of an OBJECT IDENTIFIER's contents.
    static func oidString(_ value: ArraySlice<UInt8>) -> String {
        guard let firstByte = value.first else { return "" }
        var parts = [String(firstByte / 40), String(firstByte % 40)]
        var current = 0
        for byte in value.dropFirst() {
            current = (current << 7) | Int(byte & 0x7F)
            if byte & 0x80 == 0 {
                parts.append(String(current))
                current = 0
            }
        }
        return parts.joined(separator: ".")
    }
}

/// The handful of identifiers an EF.SOD actually uses, named as jmrtd names them so a payload reads
/// the same on both platforms. An unknown identifier is returned as its dotted form rather than
/// guessed at or dropped — an auditor can look up a number, but not an omission.
enum OID {

    private static let algorithms: [String: String] = [
        "1.3.14.3.2.26":            "SHA-1",
        "2.16.840.1.101.3.4.2.1":   "SHA-256",
        "2.16.840.1.101.3.4.2.2":   "SHA-384",
        "2.16.840.1.101.3.4.2.3":   "SHA-512",
        "2.16.840.1.101.3.4.2.4":   "SHA-224",
        "1.2.840.113549.1.1.1":     "RSA",
        "1.2.840.113549.1.1.5":     "SHA1withRSA",
        "1.2.840.113549.1.1.11":    "SHA256withRSA",
        "1.2.840.113549.1.1.12":    "SHA384withRSA",
        "1.2.840.113549.1.1.13":    "SHA512withRSA",
        "1.2.840.113549.1.1.10":    "RSASSA-PSS",
        "1.2.840.10045.2.1":        "ECDSA",
        "1.2.840.10045.4.1":        "SHA1withECDSA",
        "1.2.840.10045.4.3.1":      "SHA224withECDSA",
        "1.2.840.10045.4.3.2":      "SHA256withECDSA",
        "1.2.840.10045.4.3.3":      "SHA384withECDSA",
        "1.2.840.10045.4.3.4":      "SHA512withECDSA"
    ]

    private static let attributes: [String: String] = [
        "2.5.4.3":  "CN",
        "2.5.4.6":  "C",
        "2.5.4.7":  "L",
        "2.5.4.8":  "ST",
        "2.5.4.10": "O",
        "2.5.4.11": "OU",
        "2.5.4.5":  "SERIALNUMBER",
        "1.2.840.113549.1.9.1": "E"
    ]

    static func name(for oid: String) -> String {
        return algorithms[oid] ?? oid
    }

    static func attributeName(for oid: String) -> String {
        return attributes[oid] ?? oid
    }
}
