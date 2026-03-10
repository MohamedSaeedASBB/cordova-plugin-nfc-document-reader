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
    private var nfcBottomSheet: NfcScanBottomSheet?
    private var dgReadCount: Int = 0
    private let totalDGs: Int = 3 // DG1, DG2, SOD

    // MARK: - Plugin Lifecycle

    override func pluginInitialize() {
        super.pluginInitialize()
        nfcCallbackId = nil
        mrzScanCallbackId = nil
        documentReader = nil
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
            "enabled": available
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
        guard #available(iOS 13.0, *) else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC requires iOS 13 or later")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        guard let mrzData = command.arguments.first as? [String: Any],
              let documentNumber = mrzData["documentNumber"] as? String,
              let dateOfBirth = mrzData["dateOfBirth"] as? String,
              let dateOfExpiry = mrzData["dateOfExpiry"] as? String else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid MRZ data. Required: documentNumber, dateOfBirth, dateOfExpiry")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        nfcCallbackId = command.callbackId
        dgReadCount = 0

        // Show bottom sheet
        showNfcBottomSheet()

        // Send initial state
        sendProgressEvent(state: "waitingForTag")

        // Start NFC reading
        let reader = NfcDocumentReaderWrapper()
        self.documentReader = reader

        reader.readDocument(
            documentNumber: documentNumber,
            dateOfBirth: dateOfBirth,
            dateOfExpiry: dateOfExpiry,
            progressHandler: { [weak self] state, dgNumber, dgName in
                DispatchQueue.main.async {
                    guard let self = self else { return }

                    // Update bottom sheet
                    switch state {
                    case "waitingForTag":
                        self.nfcBottomSheet?.showWaiting()
                    case "connecting":
                        self.nfcBottomSheet?.showConnecting()
                    case "authenticating":
                        self.nfcBottomSheet?.showAuthenticating()
                    case "readingDataGroup":
                        if let dg = dgNumber, let name = dgName {
                            self.dgReadCount += 1
                            let progress = Float(self.dgReadCount) / Float(self.totalDGs)
                            self.nfcBottomSheet?.showReadingDataGroup(dgNumber: dg, dgName: name, progress: min(progress, 0.95))
                        }
                    default:
                        break
                    }

                    // Send progress event to JS
                    if let dg = dgNumber, let name = dgName {
                        self.sendDataGroupProgress(dgNumber: dg, dgName: name)
                    } else {
                        self.sendProgressEvent(state: state)
                    }
                }
            },
            completionHandler: { [weak self] result, error in
                DispatchQueue.main.async {
                    guard let self = self, let callbackId = self.nfcCallbackId else { return }

                    if let error = error {
                        // Show error on bottom sheet, then dismiss
                        self.nfcBottomSheet?.showError(message: error)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                            self.dismissNfcBottomSheet()
                        }

                        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error)
                        self.commandDelegate.send(pluginResult, callbackId: callbackId)
                    } else if let result = result {
                        // Show success, then dismiss
                        self.nfcBottomSheet?.showSuccess()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                            self.dismissNfcBottomSheet()
                        }

                        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: result)
                        pluginResult?.keepCallback = false
                        self.commandDelegate.send(pluginResult, callbackId: callbackId)
                    } else {
                        self.dismissNfcBottomSheet()
                        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Unknown error")
                        self.commandDelegate.send(pluginResult, callbackId: callbackId)
                    }

                    self.nfcCallbackId = nil
                    self.documentReader = nil
                }
            }
        )
    }

    // MARK: - cancelRead

    @objc(cancelRead:)
    func cancelRead(command: CDVInvokedUrlCommand) {
        documentReader?.cancel()
        dismissNfcBottomSheet()

        if let callbackId = nfcCallbackId {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC reading cancelled")
            commandDelegate.send(result, callbackId: callbackId)
            nfcCallbackId = nil
        }

        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "Cancelled")
        commandDelegate.send(result, callbackId: command.callbackId)
    }

    // MARK: - Bottom Sheet

    private func showNfcBottomSheet() {
        let sheet = NfcScanBottomSheet()
        sheet.modalPresentationStyle = .overFullScreen
        sheet.modalTransitionStyle = .crossDissolve

        sheet.onCancel = { [weak self] in
            self?.documentReader?.cancel()
            self?.dismissNfcBottomSheet()

            if let callbackId = self?.nfcCallbackId {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC reading cancelled")
                self?.commandDelegate.send(result, callbackId: callbackId)
                self?.nfcCallbackId = nil
                self?.documentReader = nil
            }
        }

        self.nfcBottomSheet = sheet
        self.viewController.present(sheet, animated: false)
    }

    private func dismissNfcBottomSheet() {
        nfcBottomSheet?.animateOut { [weak self] in
            self?.nfcBottomSheet = nil
        }
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
