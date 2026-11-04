// Stub of NFCPassportReader, for -typecheck only. Never compiled into the plugin.
import Foundation
import UIKit

public enum DataGroupId: Int, CaseIterable {
    case COM, DG1, DG2, DG3, DG4, DG5, DG6, DG7, DG8, DG9, DG10
    case DG11, DG12, DG13, DG14, DG15, DG16, SOD, Unknown
}

open class DataGroup {
    open var data: [UInt8] = []
}

public struct DataGroupHash {
    public var id: String = ""
    public var sodHash: String = ""
    public var computedHash: String = ""
    public var match: Bool = false
}

public enum ChipAuthenticationStatus { case notDone, success, failed }
public enum PACEStatus { case notDone, success, failed }
public enum BACStatus { case notDone, success, failed }

open class X509Wrapper {
    open func getSubjectName() -> String? { nil }
    open func getIssuerName() -> String? { nil }
    open func getSerialNumber() -> String? { nil }
}

public enum NFCPassportReaderError: Error {
    case ResponseError(String, UInt8, UInt8)
    case InvalidResponse
    case UnexpectedError
    case NFCNotSupported
    case NoConnectedTag
    case D087Malformed
    case InvalidResponseChecksum
    case MissingMandatoryFields
    case CannotDecodeASN1Length
    case InvalidASN1Value
    case UnableToProtectAPDU
    case UnableToUnprotectAPDU
    case UnsupportedDataGroup
    case DataGroupNotRead
    case UnknownTag
    case UnknownImageFormat
    case NotImplemented
    case TagNotValid
    case ConnectionError
    case UserCanceled
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

open class NFCPassportModel {
    open var passportMRZ: String { "" }
    open var documentType: String { "" }
    open var documentSubType: String { "" }
    open var documentNumber: String { "" }
    open var issuingAuthority: String { "" }
    open var documentExpiryDate: String { "" }
    open var dateOfBirth: String { "" }
    open var gender: String { "" }
    open var nationality: String { "" }
    open var lastName: String { "" }
    open var firstName: String { "" }
    open var personalNumber: String? { nil }
    open var placeOfBirth: String? { nil }
    open var residenceAddress: String? { nil }
    open var phoneNumber: String? { nil }

    open var passportImage: UIImage? { nil }
    open var signatureImage: UIImage? { nil }

    open var documentSigningCertificate: X509Wrapper? { nil }
    open var documentSigningCertificateVerified: Bool { false }
    open var passportDataNotTampered: Bool { false }
    open var passportCorrectlySigned: Bool { false }

    open var chipAuthenticationStatus: ChipAuthenticationStatus { .notDone }
    open var PACEStatus: PACEStatus { .notDone }
    open var BACStatus: BACStatus { .notDone }

    open var dataGroupHashes: [DataGroupId: DataGroupHash] { [:] }
    open var dataGroupsRead: [DataGroupId: DataGroup] { [:] }
    open var verificationErrors: [Error] { [] }

    open func getDataGroup(_ id: DataGroupId) -> DataGroup? { nil }
}

open class PassportReader: NSObject {
    public override init() {}
    open func setMasterListURL(_ url: URL) {}
    open func readPassport(mrzKey: String,
                           tags: [DataGroupId],
                           skipSecureElements: Bool,
                           customDisplayMessage: ((NFCViewDisplayMessage) -> String?)?,
                           completed: @escaping (NFCPassportModel?, NFCPassportReaderError?) -> Void) {}
}
