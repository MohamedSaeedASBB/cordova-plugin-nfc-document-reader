// Stub of NFCPassportReader 1.1.9 — only the surface this plugin uses, with the real signatures.
// Exists so the plugin's iOS sources can be type-checked without the CocoaPod.
import Foundation
import UIKit

public enum PassportAuthenticationStatus { case notDone, success, failed }

public enum DataGroupId: Hashable {
    case COM, DG1, DG2, DG3, DG4, DG5, DG6, DG7, DG8, DG9, DG10
    case DG11, DG12, DG13, DG14, DG15, DG16, SOD, Unknown
    public func getName() -> String { return "" }
}

public struct DataGroupHash {
    public var id: String
    public var sodHash: String
    public var computedHash: String
    public var match: Bool
}

public class DataGroup {
    public var datagroupType: DataGroupId = .Unknown
    public private(set) var body: [UInt8] = []
    public private(set) var data: [UInt8] = []
    public func hash(_ hashAlgorythm: String) -> [UInt8] { return [] }
}

public class X509Wrapper {
    public func getSubjectName() -> String? { return nil }
    public func getIssuerName() -> String? { return nil }
    public func getSignatureAlgorithm() -> String? { return nil }
}

public enum NFCPassportReaderError: Error {
    case ResponseError(String, UInt8, UInt8)
    case InvalidResponse
    case UnexpectedError
    case NFCNotSupported
    case NoConnectedTag
    case TagNotValid
    case ConnectionError
    case InvalidMRZKey
    case MoreThanOneTagFound
}

public enum NFCViewDisplayMessage {
    case requestPresentPassport
    case authenticatingWithPassport(Int)
    case readingDataGroupProgress(DataGroupId, Int)
    case error(NFCPassportReaderError)
    case successfulRead
}

public class NFCPassportModel {
    public var passportMRZ: String = ""
    public var documentType: String = ""
    public var documentSubType: String = ""
    public var documentNumber: String = ""
    public var issuingAuthority: String = ""
    public var documentExpiryDate: String = ""
    public var dateOfBirth: String = ""
    public var gender: String = ""
    public var nationality: String = ""
    public var lastName: String = ""
    public var firstName: String = ""
    public var personalNumber: String? = nil
    public var placeOfBirth: String? = nil
    public var residenceAddress: String? = nil
    public var phoneNumber: String? = nil
    public var passportImage: UIImage? = nil
    public var signatureImage: UIImage? = nil

    public private(set) var dataGroupsRead: [DataGroupId: DataGroup] = [:]
    public private(set) var dataGroupHashes: [DataGroupId: DataGroupHash] = [:]
    public internal(set) var BACStatus: PassportAuthenticationStatus = .notDone
    public internal(set) var PACEStatus: PassportAuthenticationStatus = .notDone
    public internal(set) var chipAuthenticationStatus: PassportAuthenticationStatus = .notDone
    public private(set) var passportCorrectlySigned: Bool = false
    public private(set) var documentSigningCertificateVerified: Bool = false
    public private(set) var passportDataNotTampered: Bool = false
    public private(set) var verificationErrors: [Error] = []
    public private(set) lazy var documentSigningCertificate: X509Wrapper? = { nil }()

    public func getDataGroup(_ id: DataGroupId) -> DataGroup? { return nil }
}

public class PassportReader {
    public init(logLevel: Int = 0, masterListURL: URL? = nil) {}
    public func setMasterListURL(_ masterListURL: URL) {}
    public func readPassport(mrzKey: String,
                             tags: [DataGroupId] = [],
                             skipSecureElements: Bool = true,
                             skipCA: Bool = false,
                             skipPACE: Bool = false,
                             customDisplayMessage: ((NFCViewDisplayMessage) -> String?)? = nil,
                             completed: @escaping (NFCPassportModel?, NFCPassportReaderError?) -> ()) {}
}
