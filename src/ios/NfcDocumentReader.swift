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
                      progressHandler: @escaping ProgressHandler,
                      completionHandler: @escaping CompletionHandler) {

        #if canImport(NFCPassportReader)

        isCancelled = false

        let mrzKey = generateMRZKey(documentNumber: documentNumber,
                                     dateOfBirth: dateOfBirth,
                                     dateOfExpiry: dateOfExpiry)

        let passportReader = PassportReader()
        self.passportReader = passportReader

        // Configure data groups to read
        let tags: [DataGroupId] = [.DG1, .DG2, .DG7, .DG11, .DG12, .SOD]

        // Read passport with progress updates
        passportReader.readPassport(mrzKey: mrzKey, tags: tags,
            skipSecureElements: true,
            customDisplayMessage: { displayMessage in
                // Map NFCPassportReader display messages to our progress events
                switch displayMessage {
                case .requestPresentPassport:
                    progressHandler("waitingForTag", nil, nil)
                case .authenticatingWithPassport(_):
                    progressHandler("authenticating", nil, nil)
                case .readingDataGroupProgress(let dg, _):
                    let dgNumber = self.dataGroupIdToNumber(dg)
                    let dgName = self.dataGroupName(dgNumber)
                    progressHandler("readingDataGroup", dgNumber, dgName)
                case .successfulRead:
                    progressHandler("success", nil, nil)
                default:
                    progressHandler("connecting", nil, nil)
                }
                return displayMessage.description
            },
            completed: { [weak self] (passport, error) in
                guard let self = self, !self.isCancelled else {
                    completionHandler(nil, "NFC reading cancelled")
                    return
                }

                if let error = error {
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

        // DG1 - MRZ Info
        data["documentType"] = passport.documentType
        data["issuingState"] = passport.issuingAuthority
        data["primaryIdentifier"] = passport.lastName
        data["secondaryIdentifier"] = passport.firstName
        data["documentNumber"] = passport.documentNumber
        data["nationality"] = passport.nationality
        data["dateOfBirth"] = passport.dateOfBirth
        data["gender"] = passport.gender
        data["dateOfExpiry"] = passport.documentExpiryDate
        data["personalNumber"] = passport.personalNumber ?? ""

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
        data["fullNameOfHolder"] = (passport.lastName + " " + passport.firstName).trimmingCharacters(in: .whitespaces)
        data["otherNames"] = [String]()
        data["personalSummary"] = ""
        data["placeOfBirth"] = passport.placeOfBirth ?? ""
        data["permanentAddress"] = passport.residenceAddress ?? ""
        data["telephone"] = passport.phoneNumber ?? ""

        // DG12 - Additional Document Details
        data["issuingAuthority"] = passport.issuingAuthority
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
    #endif

    // MARK: - Utilities

    private func generateMRZKey(documentNumber: String, dateOfBirth: String, dateOfExpiry: String) -> String {
        // MRZ key format: documentNumber + checkDigit + dateOfBirth + checkDigit + dateOfExpiry + checkDigit
        let docNumCheck = computeCheckDigit(documentNumber)
        let dobCheck = computeCheckDigit(dateOfBirth)
        let expCheck = computeCheckDigit(dateOfExpiry)
        return documentNumber + String(docNumCheck) + dateOfBirth + String(dobCheck) + dateOfExpiry + String(expCheck)
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
