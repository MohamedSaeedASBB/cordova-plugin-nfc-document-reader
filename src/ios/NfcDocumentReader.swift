import Foundation
import UIKit

#if canImport(NFCPassportReader)
import NFCPassportReader
#endif

/// Wrapper around NFCPassportReader library for reading MRTD NFC chips on iOS.
class NfcDocumentReaderWrapper {

    typealias ProgressHandler = (_ state: String, _ dgNumber: Int?, _ dgName: String?) -> Void
    typealias CompletionHandler = (_ result: [String: Any]?, _ error: String?) -> Void

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

        // For passports (TD3), request additional data groups (signature, personal/document details).
        // For national IDs (TD1/TD2), only request essentials — DG7, DG11, DG12 are commonly
        // protected by EAC and cause "security status not satisfied" errors.
        let tags: [DataGroupId]
        if mrzFormat == "TD3" {
            tags = [.DG1, .DG2, .DG7, .DG11, .DG12, .SOD]
        } else {
            tags = [.DG1, .DG2, .SOD]
        }

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

                    let errorMessage: String
                    switch error {
                    case .TagNotValid:
                        errorMessage = "Invalid NFC tag. Please ensure you are scanning a valid document."
                    case .ConnectionError:
                        errorMessage = "Connection lost. Please hold the document steady and try again."
                    case .InvalidMRZKey:
                        errorMessage = "Authentication failed. Please verify your MRZ data."
                    default:
                        errorMessage = error.localizedDescription
                    }
                    completionHandler(nil, errorMessage)
                } else if let passport = passport {
                    let documentData = self.extractData(from: passport)
                    completionHandler(documentData, nil)
                } else {
                    completionHandler(nil, "Unknown error reading document")
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
        data["personalNumber"] = clean(passport.personalNumber ?? mrzFields["personalNumber"] ?? "")

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
        data["fullNameOfHolder"] = (lastName + " " + firstName).trimmingCharacters(in: .whitespaces)
        data["otherNames"] = [String]()
        data["personalSummary"] = ""
        data["placeOfBirth"] = passport.placeOfBirth ?? ""
        data["permanentAddress"] = passport.residenceAddress ?? ""
        data["telephone"] = passport.phoneNumber ?? ""

        // DG12 - Additional Document Details
        data["issuingAuthority"] = nonEmpty(passport.issuingAuthority, fallback: mrzFields["issuingState"] ?? "")
        data["dateOfIssue"] = ""
        data["endorsementsAndObservations"] = ""

        // Metadata
        var dataGroupsRead: [Int] = []
        for (dgId, _) in passport.dataGroupsRead {
            dataGroupsRead.append(dataGroupIdToNumber(dgId))
        }
        data["dataGroupsRead"] = dataGroupsRead
        data["bacSucceeded"] = passport.BACStatus == .success
        data["chipAuthSucceeded"] = passport.chipAuthenticationStatus == .success

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
