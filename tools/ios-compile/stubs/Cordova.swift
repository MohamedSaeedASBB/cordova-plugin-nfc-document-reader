// Stub of the Cordova iOS bridge, for -typecheck only. Never compiled into the plugin.
import Foundation
import UIKit

@objc public enum CDVCommandStatus_: Int { case ok }
public let CDVCommandStatus_OK = 1
public let CDVCommandStatus_ERROR = 2

@objc open class CDVInvokedUrlCommand: NSObject {
    open var callbackId: String! { "" }
    open var arguments: [Any]! { [] }
    open func argument(at index: UInt) -> Any? { nil }
}

@objc open class CDVPluginResult: NSObject {
    public init?(status: Int, messageAs message: String) {}
    public init?(status: Int, messageAs message: [AnyHashable: Any]) {}
    public init?(status: Int, messageAs message: [Any]) {}
    public init?(status: Int, messageAs message: Bool) {}
    public init?(status: Int) {}
    open var keepCallback: NSNumber = 0
    open func setKeepCallbackAs(_ value: Bool) {}
}

@objc open class CDVCommandDelegateImpl: NSObject {
    open func send(_ result: CDVPluginResult?, callbackId: String?) {}
    open func run(inBackground block: @escaping () -> Void) {}
    open func evalJs(_ js: String) {}
}

@objc open class CDVPlugin: NSObject {
    open var commandDelegate: CDVCommandDelegateImpl = CDVCommandDelegateImpl()
    open var viewController: UIViewController! { nil }
    open var webView: UIView! { nil }
    open func pluginInitialize() {}
}
