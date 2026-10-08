import Foundation
import UIKit

#if canImport(NFCPassportReader)
import NFCPassportReader
#endif

/// Wrapper around NFCPassportReader library for reading MRTD NFC chips on iOS.
class NfcDocumentReaderWrapper {

    typealias ProgressHandler = (_ state: String, _ dgNumber: Int?, _ dgName: String?) -> Void
    typealias CompletionHandler = (_ result: [String: Any]?, _ error: String?) -> Void

    /// Default CSCA bundle resource, mirroring PassiveAuthenticator.DEFAULT_TRUST_STORE_ASSET on
    /// Android. See src/csca/README.md.
    static let defaultTrustStoreResource = "csca_master_list.pem"

    /// Returns each data group's raw bytes in the result. Off by default: they duplicate every
    /// field and the portrait, and a backend only needs them to verify the chip independently or
    /// to decode text this plugin could not.
    var includeRawDataGroups = false

    /// Resource name (or absolute path) of the PEM bundle of CSCA certificates used to decide
    /// whether the document signer is trusted. nil disables the issuer check, which caps passive
    /// authentication at "notVerified".
    var trustStoreResource: String? = NfcDocumentReaderWrapper.defaultTrustStoreResource

    #if canImport(NFCPassportReader)
    private var passportReader: PassportReader?
    #endif
    private var isCancelled = false

    /// Read an MRTD document via NFC.
    func readDocument(documentNumber: String,
                      dateOfBirth: String,
                      dateOfExpiry: String,
                      mrzFormat: String,
                      progressHandler: @escaping ProgressHandler,
                      completionHandler: @escaping CompletionHandler) {

        #if canImport(NFCPassportReader)

        isCancelled = false

        let mrzKey = generateMRZKey(documentNumber: documentNumber,
                                     dateOfBirth: dateOfBirth,
                                     dateOfExpiry: dateOfExpiry)

        let passportReader = PassportReader()
        self.passportReader = passportReader

        // NFCPassportReader runs passive authentication itself once the read completes, but the
        // issuer check is only attempted when it has a CSCA bundle to check against. Without one
        // it can still confirm the SOD signature and the data group hashes — self-consistency,
        // which any forger can also produce — so the payload reports "notVerified" rather than
        // passed. Installing the bundle is what turns that into a real verdict.
        if let trustStorePath = resolveTrustStorePath() {
            passportReader.setMasterListURL(URL(fileURLWithPath: trustStorePath))
        } else {
            NSLog("[NfcDocumentReader] No CSCA trust store found (%@); the document signer cannot"
                  + " be verified. See src/csca/README.md.",
                  self.trustStoreResource ?? "disabled")
        }

        // Every document is asked for the same data groups, as Android asks for them.
        //
        // National IDs used to be asked for DG1, DG2 and the SOD only, on the assumption that
        // DG7, DG11 and DG12 are "commonly protected by EAC" on them and that asking would fail
        // the read. That assumption cost the national identification number, the holder's name in
        // Arabic, the place of birth and the issuing authority on every TD1 card read on iOS,
        // while Android — which asks for them unconditionally — returned all four from the same
        // Algerian card.
        //
        // It was also unnecessary. NFCPassportReader does not abort on a refused data group: a
        // "Security status not satisfied" or "File not found" drops that one and carries on
        // (PassportReader.readNextDataGroup). And because .COM is requested first, the library
        // narrows the list to what the chip's own file list advertises, so a document that does
        // not carry DG11 is never asked for it.
        //
        // The cost of asking for a group the card will not give is therefore one refused APDU at
        // worst. The cost of not asking was four missing fields.
        let tags: [DataGroupId] = [.COM, .DG1, .DG2, .DG7, .DG11, .DG12, .SOD]

        // Read document with progress updates
        passportReader.readPassport(mrzKey: mrzKey, tags: tags,
            skipSecureElements: true,
            customDisplayMessage: { displayMessage in
                switch displayMessage {
                case .requestPresentPassport:
                    progressHandler("waitingForTag", nil, nil)
                    return "Hold your iPhone near the document."
                case .authenticatingWithPassport(let progress):
                    progressHandler("authenticating", nil, nil)
                    return "Authenticating...\n\n" + self.progressIndicator(progress)
                case .readingDataGroupProgress(let dg, let progress):
                    let dgNumber = self.dataGroupIdToNumber(dg)
                    let dgName = self.dataGroupName(dgNumber)
                    progressHandler("readingDataGroup", dgNumber, dgName)
                    return "Reading \(dgName)...\n\n" + self.progressIndicator(progress)
                case .successfulRead:
                    progressHandler("success", nil, nil)
                    return "Document read successfully."
                case .error(let error):
                    return "Error: \(error.localizedDescription)"
                }
            },
            completed: { [weak self] (passport, error) in
                guard let self = self, !self.isCancelled else {
                    completionHandler(nil, "NFC reading cancelled")
                    return
                }

                if let error = error {
                    // Check if the error is about a missing optional data group
                    // If we got a passport model despite the error, use it
                    if let passport = passport {
                        let documentData = self.extractData(from: passport)
                        completionHandler(documentData, nil)
                        return
                    }

                    let userMessage: String
                    let errorCode: String
                    let technicalError = error.localizedDescription

                    switch error {
                    case .TagNotValid:
                        errorCode = "TAG_NOT_SUPPORTED"
                        userMessage = "This document's chip could not be detected. Please try repositioning it."
                    case .ConnectionError:
                        errorCode = "TAG_LOST"
                        userMessage = "Connection lost. Please hold the document steady on your phone and try again."
                    case .InvalidMRZKey:
                        errorCode = "AUTH_FAILED"
                        userMessage = "Unable to read this document. Please ensure the document details are correct and try again."
                    default:
                        errorCode = "UNKNOWN"
                        userMessage = "An unexpected error occurred. Please try again."
                    }

                    // Log diagnostics to Supabase (fire-and-forget)
                    DiagnosticsLogger.logError(
                        errorCode: errorCode,
                        technicalError: technicalError,
                        userMessage: userMessage,
                        documentNumber: documentNumber,
                        dateOfBirth: dateOfBirth,
                        dateOfExpiry: dateOfExpiry
                    )

                    completionHandler(nil, userMessage)
                } else if let passport = passport {
                    let documentData = self.extractData(from: passport)
                    completionHandler(documentData, nil)
                } else {
                    // Log unknown error
                    DiagnosticsLogger.logError(
                        errorCode: "UNKNOWN",
                        technicalError: "No passport and no error returned from reader",
                        userMessage: "An unexpected error occurred. Please try again.",
                        documentNumber: documentNumber,
                        dateOfBirth: dateOfBirth,
                        dateOfExpiry: dateOfExpiry
                    )
                    completionHandler(nil, "An unexpected error occurred. Please try again.")
                }

                self.passportReader = nil
            }
        )

        #else
        completionHandler(nil, "NFCPassportReader library not available. Please install the CocoaPod.")
        #endif
    }

    func cancel() {
        isCancelled = true
        #if canImport(NFCPassportReader)
        passportReader = nil
        #endif
    }

    // MARK: - Authentication

    /// Mirrors the Android payload exactly — see PassiveAuthenticator.java for what each field
    /// means and, just as importantly, what it does not mean.
    ///
    /// The three passive-authentication checks are reported separately because only all three
    /// together are evidence: `sodSignatureVerified` and `dataIntegrityVerified` without
    /// `issuerTrusted` prove the chip is internally consistent, which a forger signing their own
    /// data with their own certificate also achieves.
    private func authenticationBlock(from passport: NFCPassportModel) -> [String: Any] {
        let sod = SodInspector.inspect(sod: passport.getDataGroup(.SOD)?.data)
        let sodSignatureVerified = passport.documentSigningCertificateVerified
        let dataIntegrityVerified = passport.passportDataNotTampered
        let issuerTrusted = passport.passportCorrectlySigned

        var reasons: [String] = []
        if !sodSignatureVerified { reasons.append("SOD_SIGNATURE_INVALID") }
        if !dataIntegrityVerified { reasons.append("DG_HASH_MISMATCH") }
        if resolveTrustStorePath() == nil {
            reasons.append("NO_TRUST_ANCHORS")
        } else if !issuerTrusted {
            reasons.append("ISSUER_NOT_TRUSTED")
        }

        let status: String
        if sodSignatureVerified && dataIntegrityVerified && issuerTrusted {
            status = "passed"
        } else if !sodSignatureVerified || !dataIntegrityVerified || !issuerTrusted {
            // A missing trust store is the one shortfall that is not a failure: nothing about the
            // document was contradicted, we simply could not establish the issuer.
            status = reasons == ["NO_TRUST_ANCHORS"] ? "notVerified" : "failed"
        } else {
            status = "notVerified"
        }

        // Per data group: did the computed hash match the one in the SOD. Hash values themselves
        // are holder data and stay on the device.
        var dataGroupHashes: [String: Bool] = [:]
        for (dgId, hash) in passport.dataGroupHashes {
            dataGroupHashes[String(dataGroupIdToNumber(dgId))] = hash.match
        }

        let passiveAuth: [String: Any] = [
            "status": status,
            "sodSignatureVerified": sodSignatureVerified,
            "dataIntegrityVerified": dataIntegrityVerified,
            "issuerTrusted": issuerTrusted,
            // NFCPassportReader does not surface the SOD's algorithm identifiers, so these are
            // reported as unavailable rather than guessed. Android fills them in. Note the
            // document signer certificate does expose a signature algorithm, but that is the
            // algorithm the CSCA used to sign the certificate — not the one used for the SOD —
            // so putting it here would report the wrong thing under the right name. Read from the
            // SOD's own DER instead; see SodInspector.
            "digestAlgorithm": sod.digestAlgorithm ?? NSNull(),
            "signatureAlgorithm": sod.signatureAlgorithm ?? NSNull(),
            // The library only extracts the signer certificate when a CSCA master list is
            // supplied, so without a trust store installed this was null on iOS while Android
            // reported it from every read. Who signed a document is a fact worth recording even
            // when — especially when — the issuer could not be confirmed.
            "documentSignerSubject": passport.documentSigningCertificate?.getSubjectName()
                ?? sod.signerSubject ?? NSNull(),
            "trustStore": resolveTrustStorePath() != nil ? "loaded" : "none",
            "dataGroupHashes": dataGroupHashes,
            "reasons": reasons
        ]

        let chipAuthentication: String
        switch passport.chipAuthenticationStatus {
        case .success: chipAuthentication = "success"
        case .failed: chipAuthentication = "failed"
        case .notDone: chipAuthentication = "notDone"
        }

        // Built as Any before the literal rather than inline. A ternary's branches must share one
        // type, and "BAC" and NSNull() do not — the surrounding dictionary being [String: Any] does
        // not rescue it, which is why this failed to compile for iOS while every other platform and
        // every syntax-only check passed.
        let accessProtocol: Any
        if passport.PACEStatus == .success {
            accessProtocol = "PACE"
        } else if passport.BACStatus == .success {
            accessProtocol = "BAC"
        } else {
            accessProtocol = NSNull()
        }

        return [
            "chipAccessEstablished": passport.BACStatus == .success || passport.PACEStatus == .success,
            "accessProtocol": accessProtocol,
            "chipAuthentication": chipAuthentication,
            "passiveAuthentication": passiveAuth
        ]
    }

    /// Accepts a bundle resource name, a name under www/ (where Cordova stages web assets), or an
    /// absolute path — the same three shapes FaceMatcher.resolveModelPath accepts.
    private func resolveTrustStorePath() -> String? {
        guard let resource = trustStoreResource, !resource.isEmpty else { return nil }

        if FileManager.default.fileExists(atPath: resource) {
            return resource
        }
        let name = (resource as NSString).deletingPathExtension
        let ext = (resource as NSString).pathExtension.isEmpty
            ? "pem"
            : (resource as NSString).pathExtension
        if let path = Bundle.main.path(forResource: name, ofType: ext) {
            return path
        }
        return Bundle.main.path(forResource: (resource as NSString).lastPathComponent,
                                ofType: nil,
                                inDirectory: "www")
    }

    // MARK: - Data Extraction

    #if canImport(NFCPassportReader)
    private func extractData(from passport: NFCPassportModel) -> [String: Any] {
        var data: [String: Any] = [:]

        // Helper to clean MRZ filler characters
        func clean(_ value: String) -> String {
            return value.replacingOccurrences(of: "<", with: " ").trimmingCharacters(in: .whitespaces)
        }

        // Helper to return non-empty value or fallback
        func nonEmpty(_ value: String, fallback: String = "") -> String {
            let cleaned = value.trimmingCharacters(in: .whitespaces)
            return cleaned.isEmpty ? fallback : cleaned
        }

        // Parse raw MRZ as fallback if library properties are empty
        var mrzFields: [String: String] = [:]
        let mrz = passport.passportMRZ
        if !mrz.isEmpty {
            mrzFields = parseMRZString(mrz)
        }

        // DG1 - MRZ Info (with fallback from raw MRZ)
        data["documentType"] = nonEmpty(passport.documentType, fallback: mrzFields["documentType"] ?? "")
        data["issuingState"] = nonEmpty(passport.issuingAuthority, fallback: mrzFields["issuingState"] ?? "")
        data["primaryIdentifier"] = clean(nonEmpty(passport.lastName, fallback: mrzFields["primaryIdentifier"] ?? ""))
        data["secondaryIdentifier"] = clean(nonEmpty(passport.firstName, fallback: mrzFields["secondaryIdentifier"] ?? ""))
        data["documentNumber"] = nonEmpty(passport.documentNumber, fallback: mrzFields["documentNumber"] ?? "")
            .replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
        data["nationality"] = nonEmpty(passport.nationality, fallback: mrzFields["nationality"] ?? "")
        data["dateOfBirth"] = nonEmpty(passport.dateOfBirth, fallback: mrzFields["dateOfBirth"] ?? "")
        data["dateOfExpiry"] = nonEmpty(passport.documentExpiryDate, fallback: mrzFields["dateOfExpiry"] ?? "")
        // The national identification number. NFCPassportReader reads DG11's 0x5F10 itself, but
        // decodes every DG11 value as UTF-8 and yields nil for anything else — so on a card whose
        // other DG11 fields are in a single-byte Arabic code page, a perfectly ASCII number can be
        // lost along with them. Read directly from the data group as a fallback before dropping to
        // the MRZ, which on a TD1 holds a shorter number or none at all.
        data["personalNumber"] = clean(
            passport.personalNumber
                ?? MrtdTextDecoder.value(tag: MrtdTextDecoder.tagPersonalNumber,
                                         in: passport.getDataGroup(.DG11)?.data)
                ?? mrzFields["personalNumber"]
                ?? "")

        // Format gender to match Android (Male/Female/Unspecified)
        let rawGender = nonEmpty(passport.gender, fallback: mrzFields["gender"] ?? "")
        if rawGender.uppercased().hasPrefix("M") {
            data["gender"] = "Male"
        } else if rawGender.uppercased().hasPrefix("F") {
            data["gender"] = "Female"
        } else {
            data["gender"] = "Unspecified"
        }

        // DG2 - Face Image
        if let faceImage = passport.passportImage {
            data["faceImageBase64"] = imageToBase64(faceImage)
        } else {
            data["faceImageBase64"] = NSNull()
        }

        // DG7 - Signature
        if let signatureImage = passport.signatureImage {
            data["signatureImageBase64"] = imageToBase64(signatureImage)
        } else {
            data["signatureImageBase64"] = NSNull()
        }

        // DG11 - Additional Personal Details
        let lastName = clean(nonEmpty(passport.lastName, fallback: mrzFields["primaryIdentifier"] ?? ""))
        let firstName = clean(nonEmpty(passport.firstName, fallback: mrzFields["secondaryIdentifier"] ?? ""))
        // DG11/DG12 text, recovered when the issuer did not use UTF-8. On iOS a field that will
        // not decode comes back nil rather than as replacement characters, so the same
        // non-conformant document that shows boxes on Android shows *empty fields* here — which
        // reads as "the document has no place of birth" instead of as an error. Recovery works
        // from the raw bytes for that reason.
        let recovered = MrtdTextDecoder.recover(
            dg11: passport.getDataGroup(.DG11)?.data,
            dg12: passport.getDataGroup(.DG12)?.data)

        func text(_ tag: Int, _ fallback: String?) -> String {
            return recovered.fields[tag] ?? (fallback ?? "")
        }

        let placeOfBirth = text(MrtdTextDecoder.tagPlaceOfBirth, passport.placeOfBirth)
        let permanentAddress = text(MrtdTextDecoder.tagPermanentAddress, passport.residenceAddress)

        let fullName = recovered.fields[MrtdTextDecoder.tagFullName]
            ?? (lastName + " " + firstName).trimmingCharacters(in: .whitespaces)
        data["fullNameOfHolder"] = fullName
        // The components, as placeOfBirthLines and permanentAddressLines already report theirs.
        // An Algerian card carries the Latin and Arabic forms of the name in one field separated
        // by '<<', so the split is where the Arabic surname actually becomes readable.
        data["fullNameOfHolderLines"] = MrtdTextDecoder.splitComponents(fullName)
        // DG11's 0x5F0F, which on an Algerian card carries the holder's name in Arabic. It was
        // hardcoded empty here while Android returned it, so the field existed on both platforms
        // and meant something on only one.
        let otherNames = recovered.fields[MrtdTextDecoder.tagOtherNames]
            ?? MrtdTextDecoder.value(tag: MrtdTextDecoder.tagOtherNames,
                                     in: passport.getDataGroup(.DG11)?.data)
        data["otherNames"] = MrtdTextDecoder.splitComponents(otherNames ?? "")
        data["personalSummary"] = text(MrtdTextDecoder.tagPersonalSummary, "")
        // Joined for display; the components are what application logic should read, because a
        // single DG11 field can carry several unrelated attributes. See README.
        data["placeOfBirth"] = MrtdTextDecoder.splitComponents(placeOfBirth).joined(separator: ", ")
        data["permanentAddress"] =
            MrtdTextDecoder.splitComponents(permanentAddress).joined(separator: ", ")
        data["placeOfBirthLines"] = MrtdTextDecoder.splitComponents(placeOfBirth)
        data["permanentAddressLines"] = MrtdTextDecoder.splitComponents(permanentAddress)
        data["telephone"] = text(MrtdTextDecoder.tagTelephone, passport.phoneNumber)
        data["textEncoding"] = recovered.encoding ?? NSNull()
        // How much non-ASCII text this plugin put into the payload, counted as it was written.
        //
        // Arabic from DG11 kept arriving at the back office as question marks, and establishing
        // where it was being lost took three rounds of device testing because nothing in the
        // payload said what had left the handset. This does: if a stored payload reports a count
        // here but its own name and address fields hold no non-ASCII characters, they were
        // destroyed after this plugin returned — a lossy conversion somewhere downstream, which
        // is what substitutes '?'. Nothing here converts: the plugin is UTF-8 throughout.
        let nonAsciiCharacters: Int = Self.countNonAscii([
            data["fullNameOfHolder"], data["otherNames"], data["personalSummary"],
            data["placeOfBirth"], data["permanentAddress"], data["issuingAuthority"],
            data["endorsementsAndObservations"]
        ])
        // Only what both platforms can compute to mean exactly the same thing. A count of
        // "fields recovered" was dropped from this block for that reason: the two sides would have
        // counted different sets, which is the class of defect this whole field exists to expose.
        let textRecovery: [String: Any] = [
            "encoding": recovered.encoding ?? NSNull(),
            "nonAsciiCharacters": nonAsciiCharacters
        ]
        data["textRecovery"] = textRecovery

        // DG12 - Additional Document Details
        data["issuingAuthority"] = recovered.fields[MrtdTextDecoder.tagIssuingAuthority]
            ?? nonEmpty(passport.issuingAuthority, fallback: mrzFields["issuingState"] ?? "")
        data["dateOfIssue"] = MrtdTextDecoder.value(
            tag: MrtdTextDecoder.tagDateOfIssue, in: passport.getDataGroup(.DG12)?.data) ?? ""
        data["endorsementsAndObservations"] = text(MrtdTextDecoder.tagEndorsements, "")

        // Raw data groups, base64, only on request: a second full copy of every field and the
        // portrait, but what a backend needs to re-verify the issuer's signature itself.
        if includeRawDataGroups {
            var raw: [String: String] = [:]
            for (dgId, group) in passport.dataGroupsRead {
                let number = dataGroupIdToNumber(dgId)
                // EF.COM and anything unrecognised map to a number that is not a data group;
                // keying on it would put a "-1" entry in the payload.
                guard dgId == .SOD || number >= 1 else { continue }
                let key = dgId == .SOD ? "sod" : String(number)
                raw[key] = Data(group.data).base64EncodedString()
            }
            data["rawDataGroups"] = raw
        }

        // Metadata
        // Numbered data groups only. EF.COM and the SOD both map to a number that is not a data
        // group, and listing them made the iOS array read [1, 0, 2] where Android's read
        // [1, 2, 7, 11, 12] — the same field meaning two different things per platform.
        var dataGroupsRead: [Int] = []
        for (dgId, _) in passport.dataGroupsRead {
            let number = dataGroupIdToNumber(dgId)
            if number >= 1 { dataGroupsRead.append(number) }
        }
        data["dataGroupsRead"] = dataGroupsRead.sorted()
        data["authentication"] = authenticationBlock(from: passport)

        var readErrors: [String: String] = [:]
        for error in passport.verificationErrors {
            readErrors["verification"] = error.localizedDescription
        }
        data["readErrors"] = readErrors

        return data
    }

    /// Parse raw MRZ string into field dictionary as fallback
    private func parseMRZString(_ mrz: String) -> [String: String] {
        var fields: [String: String] = [:]
        let lines = mrz.components(separatedBy: "\n").filter { !$0.isEmpty }

        if lines.count == 2 && lines[0].count >= 44 {
            // TD3 (Passport) - 2 lines of 44 chars
            let line1 = lines[0]
            let line2 = lines[1]
            let l1 = Array(line1)
            let l2 = Array(line2)

            fields["documentType"] = String(l1[0..<2]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["issuingState"] = String(l1[2..<5]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)

            let nameField = String(l1[5...])
            let nameParts = nameField.components(separatedBy: "<<")
            fields["primaryIdentifier"] = nameParts.count > 0 ? nameParts[0] : ""
            fields["secondaryIdentifier"] = nameParts.count > 1 ? nameParts[1] : ""

            fields["documentNumber"] = String(l2[0..<9]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["nationality"] = String(l2[10..<13]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["dateOfBirth"] = String(l2[13..<19])
            fields["gender"] = String(l2[20..<21])
            fields["dateOfExpiry"] = String(l2[21..<27])
            fields["personalNumber"] = String(l2[28..<42]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)

        } else if lines.count == 3 && lines[0].count >= 30 {
            // TD1 (National ID) - 3 lines of 30 chars
            let line1 = lines[0]
            let line2 = lines[1]
            let line3 = lines[2]
            let l1 = Array(line1)
            let l2 = Array(line2)
            let l3 = Array(line3)

            fields["documentType"] = String(l1[0..<2]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["issuingState"] = String(l1[2..<5]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["documentNumber"] = String(l1[5..<14]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)

            fields["dateOfBirth"] = String(l2[0..<6])
            fields["gender"] = String(l2[7..<8])
            fields["dateOfExpiry"] = String(l2[8..<14])
            fields["nationality"] = String(l2[15..<18]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["personalNumber"] = String(l2[18..<29]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)

            let nameField = String(l3[0..<30])
            let nameParts = nameField.components(separatedBy: "<<")
            fields["primaryIdentifier"] = nameParts.count > 0 ? nameParts[0] : ""
            fields["secondaryIdentifier"] = nameParts.count > 1 ? nameParts[1] : ""

        } else if lines.count == 2 && lines[0].count >= 36 {
            // TD2 - 2 lines of 36 chars
            let line1 = lines[0]
            let line2 = lines[1]
            let l1 = Array(line1)
            let l2 = Array(line2)

            fields["documentType"] = String(l1[0..<2]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["issuingState"] = String(l1[2..<5]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)

            let nameField = String(l1[5...])
            let nameParts = nameField.components(separatedBy: "<<")
            fields["primaryIdentifier"] = nameParts.count > 0 ? nameParts[0] : ""
            fields["secondaryIdentifier"] = nameParts.count > 1 ? nameParts[1] : ""

            fields["documentNumber"] = String(l2[0..<9]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["nationality"] = String(l2[10..<13]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
            fields["dateOfBirth"] = String(l2[13..<19])
            fields["gender"] = String(l2[20..<21])
            fields["dateOfExpiry"] = String(l2[21..<27])
        }

        return fields
    }
    #endif

    // MARK: - Utilities

    private func generateMRZKey(documentNumber: String, dateOfBirth: String, dateOfExpiry: String) -> String {
        // MRZ key format: documentNumber(9 chars padded) + checkDigit + dateOfBirth + checkDigit + dateOfExpiry + checkDigit
        // Document number MUST be padded to 9 characters with '<' for BAC authentication
        var paddedDocNum = documentNumber
        while paddedDocNum.count < 9 { paddedDocNum.append("<") }
        let docNumCheck = computeCheckDigit(paddedDocNum)
        let dobCheck = computeCheckDigit(dateOfBirth)
        let expCheck = computeCheckDigit(dateOfExpiry)
        return paddedDocNum + String(docNumCheck) + dateOfBirth + String(dobCheck) + dateOfExpiry + String(expCheck)
    }

    private func computeCheckDigit(_ input: String) -> Character {
        let weights = [7, 3, 1]
        var sum = 0

        for (i, char) in input.enumerated() {
            let value: Int
            if char >= "0" && char <= "9" {
                value = Int(String(char))!
            } else if char >= "A" && char <= "Z" {
                value = Int(char.asciiValue!) - Int(Character("A").asciiValue!) + 10
            } else {
                value = 0
            }
            sum += value * weights[i % 3]
        }

        return Character(String(sum % 10))
    }

    private func imageToBase64(_ image: UIImage) -> String {
        guard let pngData = image.pngData() else { return "" }
        return pngData.base64EncodedString()
    }

    private func progressIndicator(_ progress: Int) -> String {
        let totalSegments = 5
        let filled = max(0, min(totalSegments, (progress * totalSegments) / 100))
        let empty = totalSegments - filled
        return String(repeating: "\u{25CF} ", count: filled) + String(repeating: "\u{25CB} ", count: empty)
    }

    #if canImport(NFCPassportReader)
    /// Characters outside printable ASCII across the values given, descending into string arrays.
    /// A plain count, deliberately: it is a tripwire for text being mangled in transit, not an
    /// analysis of what the text says.
    static func countNonAscii(_ values: [Any?]) -> Int {
        var total = 0
        for value in values {
            if let text = value as? String {
                total += nonAscii(in: text)
            } else if let list = value as? [String] {
                for text in list { total += nonAscii(in: text) }
            }
        }
        return total
    }

    private static func nonAscii(in text: String) -> Int {
        return text.unicodeScalars.reduce(0) { $0 + ($1.value > 0x7E ? 1 : 0) }
    }

    private func dataGroupIdToNumber(_ dg: DataGroupId) -> Int {
        switch dg {
        case .DG1: return 1
        case .DG2: return 2
        case .DG3: return 3
        case .DG4: return 4
        case .DG5: return 5
        case .DG6: return 6
        case .DG7: return 7
        case .DG8: return 8
        case .DG9: return 9
        case .DG10: return 10
        case .DG11: return 11
        case .DG12: return 12
        case .DG13: return 13
        case .DG14: return 14
        case .DG15: return 15
        case .DG16: return 16
        case .SOD: return 0
        default: return -1
        }
    }
    #endif

    private func dataGroupName(_ number: Int) -> String {
        switch number {
        case 0: return "Security Object"
        case 1: return "MRZ Information"
        case 2: return "Facial Image"
        case 7: return "Signature"
        case 11: return "Additional Personal Details"
        case 12: return "Additional Document Details"
        default: return "Data Group \(number)"
        }
    }
}
