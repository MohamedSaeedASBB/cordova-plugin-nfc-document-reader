// Stub of CordovaLib's Swift-visible surface — only what this plugin uses.
//
// Shaped from what the real toolchain accepted in a MABS build of this plugin, not from a guess:
// that build compiled NfcDocumentReaderPlugin.swift with a single unrelated warning, so every
// construct the file uses is valid against the genuine CordovaLib. It uses CDVCommandStatus_OK
// (the C enumerator name) and .error (the Swift case) interchangeably, and treats the result of
// CDVPluginResult(status:messageAs:) as optional — so the stub offers all three.
import Foundation
import UIKit

public enum CDVCommandStatus: UInt {
    case noResult = 0
    case ok = 1
    case classNotFound = 2
    case illegalAccessException = 3
    case instantiationException = 4
    case malformedURLException = 5
    case ioException = 6
    case invalidAction = 7
    case jsonException = 8
    case error = 9
}

public let CDVCommandStatus_NO_RESULT = CDVCommandStatus.noResult
public let CDVCommandStatus_OK = CDVCommandStatus.ok
public let CDVCommandStatus_ERROR = CDVCommandStatus.error

open class CDVInvokedUrlCommand: NSObject {
    open var arguments: [Any] = []
    open var callbackId: String = ""
    open func argument(at index: UInt) -> Any? { return nil }
}

open class CDVPluginResult: NSObject {
    open var keepCallback: Bool = false
    public init?(status: CDVCommandStatus) { super.init() }
    public init?(status: CDVCommandStatus, messageAs message: String?) { super.init() }
    public init?(status: CDVCommandStatus, messageAs message: [String: Any]?) { super.init() }
    public init?(status: CDVCommandStatus, messageAs message: [Any]?) { super.init() }
    public init?(status: CDVCommandStatus, messageAs message: Bool) { super.init() }
    public init?(status: CDVCommandStatus, messageAs message: Int) { super.init() }
    open func setKeepCallbackAs(_ value: Bool) {}
}

public protocol CDVCommandDelegate {
    func send(_ result: CDVPluginResult?, callbackId: String?)
    func run(inBackground block: @escaping () -> Void)
    func evalJs(_ js: String)
}

open class CDVPlugin: NSObject {
    open var commandDelegate: CDVCommandDelegate!
    open var viewController: UIViewController!
    open var webView: UIView!
    open func pluginInitialize() {}
}
