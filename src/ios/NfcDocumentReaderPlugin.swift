import Foundation
import CoreNFC

#if canImport(Cordova)
import Cordova
#endif

@objc(NfcDocumentReaderPlugin)
class NfcDocumentReaderPlugin: CDVPlugin {

    private var nfcCallbackId: String?
    private var mrzScanCallbackId: String?
    private var documentReader: NfcDocumentReaderWrapper?

    override init() {
        self.nfcCallbackId = nil
        self.mrzScanCallbackId = nil
        self.documentReader = nil
        super.init()
    }

    // MARK: - isNFCAvailable

    @objc(isNFCAvailable:)
    func isNFCAvailable(command: CDVInvokedUrlCommand) {
        let available: Bool
        if #available(iOS 13.0, *) {
            available = NFCTagReaderSession.readingAvailable
        } else {
            available = false
        }

        let result: [String: Any] = [
            "available": available,
            "enabled": available // On iOS, available means enabled
        ]

        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: result)
        commandDelegate.send(pluginResult, callbackId: command.callbackId)
    }

    // MARK: - scanMRZ

    @objc(scanMRZ:)
    func scanMRZ(command: CDVInvokedUrlCommand) {
        mrzScanCallbackId = command.callbackId

        let options = command.arguments.first as? [String: Any] ?? [:]
        let documentType = options["documentType"] as? String ?? "id"

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let cameraVC = MrzCameraViewController()
            cameraVC.documentType = documentType
            cameraVC.delegate = self
            cameraVC.modalPresentationStyle = .fullScreen
            self.viewController.present(cameraVC, animated: true)
        }
    }

    // MARK: - readNFC

    @objc(readNFC:)
    func readNFC(command: CDVInvokedUrlCommand) {
        guard #available(iOS 13.0, *), NFCTagReaderSession.readingAvailable else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC is not available on this device")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        guard let mrzData = command.arguments.first as? [String: Any],
              let documentNumber = mrzData["documentNumber"] as? String,
              let dateOfBirth = mrzData["dateOfBirth"] as? String,
              let dateOfExpiry = mrzData["dateOfExpiry"] as? String else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid MRZ data")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        nfcCallbackId = command.callbackId

        // Send initial state
        sendProgressEvent(state: "waitingForTag")

        // Start NFC reading
        documentReader = NfcDocumentReaderWrapper()
        documentReader?.readDocument(
            documentNumber: documentNumber,
            dateOfBirth: dateOfBirth,
            dateOfExpiry: dateOfExpiry,
            progressHandler: { [weak self] state, dgNumber, dgName in
                guard let self = self else { return }
                if let dg = dgNumber, let name = dgName {
                    self.sendDataGroupProgress(dgNumber: dg, dgName: name)
                } else {
                    self.sendProgressEvent(state: state)
                }
            },
            completionHandler: { [weak self] result, error in
                guard let self = self, let callbackId = self.nfcCallbackId else { return }

                if let error = error {
                    let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error)
                    self.commandDelegate.send(pluginResult, callbackId: callbackId)
                } else if let result = result {
                    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: result)
                    pluginResult?.keepCallback = false
                    self.commandDelegate.send(pluginResult, callbackId: callbackId)
                }

                self.nfcCallbackId = nil
                self.documentReader = nil
            }
        )
    }

    // MARK: - cancelRead

    @objc(cancelRead:)
    func cancelRead(command: CDVInvokedUrlCommand) {
        documentReader?.cancel()

        if let callbackId = nfcCallbackId {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC reading cancelled")
            commandDelegate.send(result, callbackId: callbackId)
            nfcCallbackId = nil
        }

        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "Cancelled")
        commandDelegate.send(result, callbackId: command.callbackId)
    }

    // MARK: - Progress Events

    private func sendProgressEvent(state: String) {
        guard let callbackId = nfcCallbackId else { return }
        let event: [String: Any] = [
            "event": "stateChanged",
            "state": state
        ]
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: event)
        result?.keepCallback = true
        commandDelegate.send(result, callbackId: callbackId)
    }

    private func sendDataGroupProgress(dgNumber: Int, dgName: String) {
        guard let callbackId = nfcCallbackId else { return }
        let event: [String: Any] = [
            "event": "stateChanged",
            "state": "readingDataGroup",
            "dgNumber": dgNumber,
            "dgName": dgName
        ]
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: event)
        result?.keepCallback = true
        commandDelegate.send(result, callbackId: callbackId)
    }
}

// MARK: - MrzCameraViewControllerDelegate

extension NfcDocumentReaderPlugin: MrzCameraViewControllerDelegate {
    func mrzCameraViewController(_ controller: MrzCameraViewController,
                                  didDetectMRZ result: MrzCameraResult) {
        controller.dismiss(animated: true)

        guard let callbackId = mrzScanCallbackId else { return }

        let response: [String: Any] = [
            "documentNumber": result.documentNumber,
            "dateOfBirth": result.dateOfBirth,
            "dateOfExpiry": result.dateOfExpiry,
            "format": result.format,
            "rawMrzLines": result.rawLines
        ]

        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response)
        commandDelegate.send(pluginResult, callbackId: callbackId)
        mrzScanCallbackId = nil
    }

    func mrzCameraViewControllerDidCancel(_ controller: MrzCameraViewController) {
        controller.dismiss(animated: true)

        guard let callbackId = mrzScanCallbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "MRZ scan cancelled")
        commandDelegate.send(pluginResult, callbackId: callbackId)
        mrzScanCallbackId = nil
    }
}
