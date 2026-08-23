import Foundation
import CoreNFC
import UIKit

#if canImport(Cordova)
import Cordova
#endif

@objc(NfcDocumentReaderPlugin)
class NfcDocumentReaderPlugin: CDVPlugin {

    private var nfcCallbackId: String?
    private var mrzScanCallbackId: String?
    private var livenessCallbackId: String?
    private var documentReader: NfcDocumentReaderWrapper?
    private var nfcBottomSheet: NfcScanBottomSheet?
    private var dgReadCount: Int = 0
    private var totalDGs: Int = 3

    // Chip-read-then-liveness flow: the document result is held here while the liveness
    // screen runs, then merged with the liveness result and the face comparison.
    private var pendingDocumentResult: [String: Any]?
    private var pendingLivenessOptions: LivenessOptions?
    private var pendingFaceMatchConfig: [String: Any]?
    private let comparisonQueue = DispatchQueue(label: "liveness.comparison.queue")

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

    // MARK: - checkLiveness

    @objc(checkLiveness:)
    func checkLiveness(command: CDVInvokedUrlCommand) {
        livenessCallbackId = command.callbackId

        let options = command.arguments.first as? [String: Any] ?? [:]

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let livenessVC = LivenessCameraViewController()
            livenessVC.options = LivenessOptions.from(options)
            livenessVC.delegate = self
            livenessVC.modalPresentationStyle = .fullScreen
            self.viewController.present(livenessVC, animated: true)
        }
    }

    // MARK: - Chip read + liveness + on-device face match

    private func presentLivenessForChipRead() {
        let livenessVC = LivenessCameraViewController()
        livenessVC.options = pendingLivenessOptions ?? LivenessOptions.from([:])
        livenessVC.delegate = self
        livenessVC.modalPresentationStyle = .fullScreen
        viewController.present(livenessVC, animated: true)
    }

    /// Merges the chip result, the liveness result and the on-device face comparison into the
    /// single payload the app forwards to the back office.
    private func completeChipReadWithLiveness(_ livenessResult: [String: Any]) {
        guard let callbackId = nfcCallbackId,
              var documentResult = pendingDocumentResult else { return }

        let faceMatchConfig = pendingFaceMatchConfig
        let imageOptions = (pendingLivenessOptions ?? LivenessOptions.from([:])).imageOptions()

        // Clear the holding state before the async work so a second read cannot see it.
        pendingDocumentResult = nil
        pendingLivenessOptions = nil
        pendingFaceMatchConfig = nil
        nfcCallbackId = nil

        // ML Kit's results(in:) raises on the main thread, and TFLite inference is heavy.
        comparisonQueue.async { [weak self] in
            guard let self = self else { return }

            documentResult["liveness"] = livenessResult

            let documentPortrait = Self.decodeBase64Image(documentResult["faceImageBase64"])
            var outcome = FaceComparison.compare(documentPortrait: documentPortrait,
                                                 livenessFaceBase64: livenessResult["faceImageBase64"] as? String,
                                                 imageOptions: imageOptions)

            let matcher = FaceMatcher(config: FaceMatcher.Config.from(faceMatchConfig))
            let match = matcher.match(documentPortrait: documentPortrait,
                                      documentFaceBox: outcome.documentFaceBox,
                                      livenessPortrait: outcome.livenessPortrait,
                                      livenessFaceBox: outcome.livenessFaceBox)
            outcome.json["match"] = match.dictionary

            documentResult["faceComparison"] = outcome.json

            let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: documentResult)
            pluginResult?.keepCallback = false
            self.commandDelegate.send(pluginResult, callbackId: callbackId)
        }
    }

    private func failChipReadLiveness(_ message: String) {
        pendingDocumentResult = nil
        pendingLivenessOptions = nil
        pendingFaceMatchConfig = nil

        guard let callbackId = nfcCallbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: message)
        commandDelegate.send(pluginResult, callbackId: callbackId)
        nfcCallbackId = nil
    }

    private static func decodeBase64Image(_ value: Any?) -> UIImage? {
        guard let base64 = value as? String, !base64.isEmpty,
              let data = Data(base64Encoded: base64) else {
            return nil
        }
        return UIImage(data: data)
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

        let mrzFormat = mrzData["format"] as? String ?? "TD1"

        // Optional second argument: { liveness: ..., faceMatch: {...} }.
        // Absent means chip-read only, so existing callers are unaffected.
        pendingLivenessOptions = nil
        pendingFaceMatchConfig = nil
        if command.arguments.count > 1, let readOptions = command.arguments[1] as? [String: Any] {
            if let enabled = readOptions["liveness"] as? Bool, enabled {
                pendingLivenessOptions = LivenessOptions.from([:])
            } else if let livenessOptions = readOptions["liveness"] as? [String: Any] {
                pendingLivenessOptions = LivenessOptions.from(livenessOptions)
            }
            pendingFaceMatchConfig = readOptions["faceMatch"] as? [String: Any]
        }

        nfcCallbackId = command.callbackId
        dgReadCount = 0

        // Set total DGs based on format
        totalDGs = mrzFormat == "TD3" ? 6 : 3

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
            mrzFormat: mrzFormat,
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

                        if self.pendingLivenessOptions != nil {
                            // Chip read done — now prove the holder is present and compare their
                            // face against the portrait we just read off the chip. The readNFC
                            // callback deliberately stays open until that finishes.
                            self.pendingDocumentResult = result as? [String: Any]
                            self.sendProgressEvent(state: "livenessCheck")
                            self.documentReader = nil
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                                self.presentLivenessForChipRead()
                            }
                            return
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

// MARK: - LivenessCameraViewControllerDelegate

extension NfcDocumentReaderPlugin: LivenessCameraViewControllerDelegate {

    func livenessCameraViewController(_ controller: LivenessCameraViewController,
                                      didComplete result: [String: Any]) {
        controller.dismiss(animated: true)

        // Chip-read flow: fold the liveness result into the document result and compare.
        if pendingDocumentResult != nil {
            completeChipReadWithLiveness(result)
            return
        }

        guard let callbackId = livenessCallbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: result)
        pluginResult?.keepCallback = false
        commandDelegate.send(pluginResult, callbackId: callbackId)
        livenessCallbackId = nil
    }

    func livenessCameraViewController(_ controller: LivenessCameraViewController,
                                      didFailWith code: String, message: String) {
        controller.dismiss(animated: true)

        if pendingDocumentResult != nil {
            failChipReadLiveness(message)
            return
        }

        guard let callbackId = livenessCallbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: message)
        commandDelegate.send(pluginResult, callbackId: callbackId)
        livenessCallbackId = nil
    }
}
