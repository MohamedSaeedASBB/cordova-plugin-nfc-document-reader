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
    private var pendingPassiveAuthConfig: [String: Any]?
    private var pendingIncludeRawDataGroups = false
    private var captureCallbackId: String?
    private let comparisonQueue = DispatchQueue(label: "liveness.comparison.queue")

    /// The multi-step flow currently running, or nil. The MRZ, chip and capture screens all
    /// report through the same delegates whichever flow asked for them, so each handler checks
    /// this before deciding whether the result is its own to deliver.
    private var activeFlow: String?
    private static let flowCaptureAndReadNFC = "captureAndReadNFC"

    /// Options held while captureAndReadNFC's MRZ scan runs, before the chip read starts.
    private var pendingCombinedOptions: [String: Any]?
    /// True when the chip read now running should photograph the card before returning.
    private var captureAfterRead = false
    /// The finished chip payload, waiting for the photographs to be taken and folded in.
    private var payloadAwaitingCapture: [String: Any]?
    private var payloadAwaitingCaptureCallbackId: String?
    /// The MRZ as scanned, kept so the print can be compared against what the chip says.
    private var pendingRawMrzInfo = ""

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
        activeFlow = nil
        mrzScanCallbackId = command.callbackId
        presentMrzScanner(options: command.arguments.first as? [String: Any] ?? [:])
    }

    /// Shared by scanMRZ and by the combined flow's first phase, so the scanner is paced the same
    /// way whichever asked for it. Absent keys leave the controller's own defaults alone.
    private func presentMrzScanner(options: [String: Any]) {
        let documentType = options["documentType"] as? String ?? "id"
        let frameIntervalMs = options["frameIntervalMs"] as? Double
        let requiredMatches = options["requiredMatches"] as? Int

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let cameraVC = MrzCameraViewController()
            cameraVC.documentType = documentType
            if let frameIntervalMs = frameIntervalMs { cameraVC.frameIntervalMs = frameIntervalMs }
            if let requiredMatches = requiredMatches { cameraVC.requiredMatches = max(1, requiredMatches) }
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

            if self.captureAfterRead {
                // Same tail as the non-liveness path: the card is photographed last, once the chip
                // has been read and checked against the print.
                self.captureAfterRead = false
                let finished = documentResult
                DispatchQueue.main.async {
                    self.launchCaptureAfterRead(finished, callbackId: callbackId)
                }
                return
            }

            self.sendFinalPayload(callbackId: callbackId, payload: documentResult,
                                  captureIssue: nil)
        }
    }

    private func failChipReadLiveness(_ message: String) {
        pendingDocumentResult = nil
        pendingLivenessOptions = nil
        pendingFaceMatchConfig = nil
        captureAfterRead = false

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

    // MARK: - Document capture

    /// Photographs the card: front and back for an ID, the photo page alone for a passport.
    /// Which sides exist follows from the document type, as on Android.
    @objc(captureDocument:)
    func captureDocument(command: CDVInvokedUrlCommand) {
        let options = command.arguments.first as? [String: Any] ?? [:]
        let documentType = options["documentType"] as? String ?? "id"
        let isPassport = documentType.lowercased() == "passport"

        presentCapture(callbackId: command.callbackId, options: options) { controller in
            controller.captureType = "document"
            controller.documentType = documentType
            controller.title = options["title"] as? String
                ?? (isPassport ? "Capture passport" : "Capture ID card")
            controller.steps = DocumentCaptureViewController.steps(forDocumentType: documentType)
            // No OCR: the chip carries these fields signed, so the photograph is for the record.
            controller.runOcr = false
        }
    }

    /// One page of whatever the customer brought. OCR on by default: reading the page is the
    /// reason this capture exists, and there is no chip behind a utility bill.
    @objc(captureProofOfAddress:)
    func captureProofOfAddress(command: CDVInvokedUrlCommand) {
        let options = command.arguments.first as? [String: Any] ?? [:]

        presentCapture(callbackId: command.callbackId, options: options) { controller in
            controller.captureType = "proofOfAddress"
            controller.title = options["title"] as? String ?? "Proof of address"
            controller.steps = [DocumentCaptureStep(
                key: "document", label: "Proof of address",
                hint: "Photograph the whole page, including the name and address")]
            controller.runOcr = (options["ocr"] as? Bool) ?? true
            // A bill is not an identity document; the evidence check would fail every honest one.
            controller.verifyDocument = (options["verifyDocument"] as? Bool) ?? false
            // The picture is the data here, so it is allowed more room than a card.
            controller.imageOptions.maxDimension = options["maxImageDimension"] as? Int ?? 1800
            controller.imageOptions.maxBytes = options["maxImageBytes"] as? Int ?? 600 * 1024
            controller.imageOptions.initialQuality =
                CGFloat(options["jpegQuality"] as? Int ?? 88) / 100.0
        }
    }

    /// Still Android-only: this chains the MRZ scan, both photographs and a liveness check, and
    /// that orchestration is not yet built here. The individual steps all work — scanMRZ,
    /// captureDocument and checkLiveness — so the flow can be assembled from them meanwhile.
    @objc(captureDocumentAndLiveness:)
    func captureDocumentAndLiveness(command: CDVInvokedUrlCommand) {
        let result = CDVPluginResult(
            status: .error,
            messageAs: "captureDocumentAndLiveness is not available on iOS yet. Call scanMRZ, "
                     + "captureDocument and checkLiveness in sequence instead — each works on iOS.")
        commandDelegate.send(result, callbackId: command.callbackId)
    }

    // MARK: - captureAndReadNFC

    /// Scan the MRZ, read the chip, check the two agree, then photograph the card — on one
    /// callback.
    ///
    /// The order is the point. The MRZ has to be read first because it derives the chip access
    /// key, and the chip has to be read before the photographs so that the comparison between what
    /// is printed and what is stored happens while the customer is still at the counter with the
    /// document in their hand. Photographing first would produce images of a card nobody had yet
    /// established was genuine.
    ///
    /// If the photographs are abandoned the chip result is still delivered, with capture absent
    /// and captureCancelled true: a completed read cost the customer a tap and possibly a liveness
    /// check, and throwing it away because of a cancelled camera screen would be worse than
    /// returning it incomplete.
    @objc(captureAndReadNFC:)
    func captureAndReadNFC(command: CDVInvokedUrlCommand) {
        guard #available(iOS 13.0, *) else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR,
                                         messageAs: "NFC requires iOS 13 or later")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        let options = command.arguments.first as? [String: Any] ?? [:]
        pendingCombinedOptions = options
        activeFlow = Self.flowCaptureAndReadNFC
        mrzScanCallbackId = command.callbackId
        presentMrzScanner(options: options)
    }

    /// Phase two: the MRZ is in hand, so read the chip with it.
    private func continueCombinedFlow(mrzData: [String: Any], callbackId: String?) {
        let options = pendingCombinedOptions ?? [:]
        pendingCombinedOptions = nil
        activeFlow = nil
        captureAfterRead = true
        startNfcRead(mrzData: mrzData, readOptions: options, callbackId: callbackId)
    }

    /// Phase four: photograph the card, now that the chip has been read and checked.
    private func launchCaptureAfterRead(_ payload: [String: Any], callbackId: String?) {
        // The MRZ document code is "P" for a passport and "I"/"ID" for a card; either way the chip
        // has just told us what this document is, so the step list follows from it rather than
        // from what the caller guessed at the start.
        let documentType = payload["documentType"] as? String ?? "id"
        let isPassport = documentType.uppercased().hasPrefix("P")
        let captureType = isPassport ? "passport" : "id"

        payloadAwaitingCapture = payload
        payloadAwaitingCaptureCallbackId = callbackId

        let identifiers = Self.identifiers(from: payload)
        presentCapture(callbackId: callbackId, options: [:]) { controller in
            controller.captureType = "document"
            controller.documentType = captureType
            controller.title = isPassport ? "Capture passport" : "Capture ID card"
            controller.steps = DocumentCaptureViewController.steps(forDocumentType: captureType)
            controller.runOcr = false          // the chip supplies these fields, signed
            controller.expectedIdentifiers = identifiers
        }
    }

    /// The identifiers a photograph of this document should contain, drawn from what has already
    /// been read. Printed on the card in Latin characters and large enough to survive OCR, they
    /// are what lets the capture screen tell the document from whatever else is on the desk — and
    /// tell this document from a different one.
    ///
    /// Labelled rather than bare so the result can report which matched without repeating holder
    /// data that is already elsewhere in the payload.
    private static func identifiers(from source: [String: Any]) -> [String] {
        let candidates = [
            ("documentNumber", source["documentNumber"] as? String ?? ""),
            ("personalNumber", source["personalNumber"] as? String ?? ""),
            ("surname", source["primaryIdentifier"] as? String ?? ""),
            ("givenNames", source["secondaryIdentifier"] as? String ?? "")
        ]
        return candidates.filter { $0.1.count >= 5 }.map { "\($0.0):\($0.1)" }
    }

    /// One exit for the combined flow, so the payload shape is the same however it got here.
    private func sendFinalPayload(callbackId: String?, payload: [String: Any],
                                  captureIssue: String?) {
        guard let callbackId = callbackId else { return }
        var payload = payload
        if let captureIssue = captureIssue {
            payload["captureCancelled"] = true
            payload["captureIssue"] = captureIssue
        }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: payload)
        pluginResult?.keepCallback = false
        commandDelegate.send(pluginResult, callbackId: callbackId)
    }

    /// Shared presentation for the capture screens, so the options that mean the same thing are
    /// read the same way whichever capture asked for them.
    private func presentCapture(callbackId: String?, options: [String: Any],
                                configure: @escaping (DocumentCaptureViewController) -> Void) {
        captureCallbackId = callbackId

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let controller = DocumentCaptureViewController()
            controller.imageOptions.maxDimension = options["maxImageDimension"] as? Int ?? 1200
            controller.imageOptions.maxBytes = options["maxImageBytes"] as? Int ?? 250 * 1024
            controller.imageOptions.initialQuality =
                CGFloat(options["jpegQuality"] as? Int ?? 80) / 100.0
            controller.verifyDocument = (options["verifyDocument"] as? Bool) ?? true
            controller.requireDocument = (options["requireDocument"] as? Bool) ?? false
            controller.expectedIdentifiers = options["expectedIdentifiers"] as? [String] ?? []
            configure(controller)
            controller.delegate = self
            controller.modalPresentationStyle = .fullScreen
            self.viewController.present(controller, animated: true)
        }
    }

    @objc(readNFC:)
    func readNFC(command: CDVInvokedUrlCommand) {
        guard #available(iOS 13.0, *) else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC requires iOS 13 or later")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        guard let mrzData = command.arguments.first as? [String: Any] else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid MRZ data. Required: documentNumber, dateOfBirth, dateOfExpiry")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        captureAfterRead = false
        let readOptions = command.arguments.count > 1
            ? (command.arguments[1] as? [String: Any] ?? [:]) : [:]
        startNfcRead(mrzData: mrzData, readOptions: readOptions, callbackId: command.callbackId)
    }

    /// The chip read itself, driven either by readNFC or by captureAndReadNFC's second phase.
    /// Both arrive with the same MRZ dictionary the scanner produces, so neither needs to know how
    /// the other got here.
    private func startNfcRead(mrzData: [String: Any], readOptions: [String: Any],
                              callbackId: String?) {
        guard let documentNumber = mrzData["documentNumber"] as? String,
              let dateOfBirth = mrzData["dateOfBirth"] as? String,
              let dateOfExpiry = mrzData["dateOfExpiry"] as? String else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid MRZ data. Required: documentNumber, dateOfBirth, dateOfExpiry")
            commandDelegate.send(result, callbackId: callbackId)
            return
        }

        let mrzFormat = mrzData["format"] as? String ?? "TD1"

        // Kept for the comparison against DG1 once the chip is open. Held whenever the scanner
        // supplied the lines, not only in the combined flow, so a caller who scans and then reads
        // gets the same check.
        pendingRawMrzInfo = ""
        if let rawLines = mrzData["rawMrzLines"] as? [String] {
            pendingRawMrzInfo = rawLines.joined(separator: " | ")
        } else if let rawLines = mrzData["rawMrzLines"] as? String {
            pendingRawMrzInfo = rawLines
        }

        // Optional second argument: { liveness: ..., faceMatch: {...}, passiveAuth: {...} }.
        // Absent means chip-read only, so existing callers are unaffected.
        pendingLivenessOptions = nil
        pendingFaceMatchConfig = nil
        pendingPassiveAuthConfig = nil
        pendingIncludeRawDataGroups = false
        if let enabled = readOptions["liveness"] as? Bool, enabled {
            pendingLivenessOptions = LivenessOptions.from([:])
        } else if let livenessOptions = readOptions["liveness"] as? [String: Any] {
            pendingLivenessOptions = LivenessOptions.from(livenessOptions)
        }
        pendingFaceMatchConfig = readOptions["faceMatch"] as? [String: Any]
        pendingPassiveAuthConfig = readOptions["passiveAuth"] as? [String: Any]
        pendingIncludeRawDataGroups = (readOptions["includeRawDataGroups"] as? Bool) ?? false

        nfcCallbackId = callbackId
        dgReadCount = 0

        // Set total DGs based on format
        totalDGs = mrzFormat == "TD3" ? 6 : 3

        // Show bottom sheet
        showNfcBottomSheet()

        // Send initial state
        sendProgressEvent(state: "waitingForTag")

        // Start NFC reading
        let reader = NfcDocumentReaderWrapper()
        // Passive authentication always runs; the trust store decides how far it can get. An
        // explicit null opts out of the issuer check, matching the Android parser.
        if let passiveAuth = pendingPassiveAuthConfig,
           passiveAuth.index(forKey: "trustStoreAsset") != nil {
            reader.trustStoreResource = passiveAuth["trustStoreAsset"] as? String
        }
        reader.includeRawDataGroups = pendingIncludeRawDataGroups
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
                        // The read failed, so there is nothing to photograph.
                        self.captureAfterRead = false
                        // Show error on bottom sheet, then dismiss
                        self.nfcBottomSheet?.showError(message: error)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                            self.dismissNfcBottomSheet()
                        }

                        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error)
                        self.commandDelegate.send(pluginResult, callbackId: callbackId)
                    } else if var result = result {
                        self.nfcBottomSheet?.showSuccess()

                        // What the document says in print, against what its chip says. Runs
                        // whenever the scanned MRZ lines were supplied, not only in the combined
                        // flow, and on both the plain and the liveness paths.
                        result["mrzComparison"] = MrzChipComparison.compare(
                            rawMrzLines: self.pendingRawMrzInfo, chip: result)

                        // Long enough for the tick to register as something that happened.
                        let showSuccessFor = 1.5
                        // Whatever comes next is presented from the dismissal's completion rather
                        // than after a delay chosen to outlast it. Presenting over a dismissal
                        // still in flight is the "already presenting another controller" failure,
                        // and it would land exactly here, between the chip read and the next step.
                        if self.pendingLivenessOptions != nil {
                            // Chip read done — now prove the holder is present and compare their
                            // face against the portrait we just read off the chip. The readNFC
                            // callback deliberately stays open until that finishes.
                            self.pendingDocumentResult = result
                            self.sendProgressEvent(state: "livenessCheck")
                            self.documentReader = nil
                            DispatchQueue.main.asyncAfter(deadline: .now() + showSuccessFor) {
                                self.dismissNfcBottomSheet { self.presentLivenessForChipRead() }
                            }
                            return
                        }

                        if self.captureAfterRead {
                            // The chip has been read and checked against the print; now photograph
                            // the card the customer is still holding.
                            self.captureAfterRead = false
                            self.nfcCallbackId = nil
                            self.documentReader = nil
                            let payload = result
                            DispatchQueue.main.asyncAfter(deadline: .now() + showSuccessFor) {
                                self.dismissNfcBottomSheet {
                                    self.launchCaptureAfterRead(payload, callbackId: callbackId)
                                }
                            }
                            return
                        }

                        DispatchQueue.main.asyncAfter(deadline: .now() + showSuccessFor) {
                            self.dismissNfcBottomSheet()
                        }
                        self.sendFinalPayload(callbackId: callbackId, payload: result,
                                              captureIssue: nil)
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
        captureAfterRead = false
        activeFlow = nil
        pendingCombinedOptions = nil

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
            self?.captureAfterRead = false
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

    /// The completion runs once the sheet is off the screen, so the caller can present the next
    /// one without racing the dismissal.
    private func dismissNfcBottomSheet(completion: (() -> Void)? = nil) {
        guard let sheet = nfcBottomSheet else {
            completion?()
            return
        }
        sheet.animateOut { [weak self] in
            self?.nfcBottomSheet = nil
            completion?()
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
        guard let callbackId = mrzScanCallbackId else {
            controller.dismiss(animated: true)
            return
        }
        mrzScanCallbackId = nil

        let response: [String: Any] = [
            "documentNumber": result.documentNumber,
            "dateOfBirth": result.dateOfBirth,
            "dateOfExpiry": result.dateOfExpiry,
            "format": result.format,
            "rawMrzLines": result.rawLines
        ]

        if activeFlow == Self.flowCaptureAndReadNFC {
            // Phase one of the combined flow: the scan was the means, not the answer. The NFC
            // sheet is presented from the camera's dismissal completion, not over it.
            controller.dismiss(animated: true) { [weak self] in
                self?.continueCombinedFlow(mrzData: response, callbackId: callbackId)
            }
            return
        }

        controller.dismiss(animated: true)
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response)
        commandDelegate.send(pluginResult, callbackId: callbackId)
    }

    func mrzCameraViewControllerDidCancel(_ controller: MrzCameraViewController) {
        controller.dismiss(animated: true)

        activeFlow = nil
        pendingCombinedOptions = nil
        captureAfterRead = false

        guard let callbackId = mrzScanCallbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "MRZ scan cancelled")
        commandDelegate.send(pluginResult, callbackId: callbackId)
        mrzScanCallbackId = nil
    }
}

// MARK: - LivenessCameraViewControllerDelegate

extension NfcDocumentReaderPlugin: DocumentCaptureViewControllerDelegate {

    func documentCapture(_ controller: DocumentCaptureViewController, didFinish result: [String: Any]) {
        controller.dismiss(animated: true)
        let callbackId = captureCallbackId
        captureCallbackId = nil

        if var payload = takePayloadAwaitingCapture() {
            payload.0["capture"] = result
            sendFinalPayload(callbackId: payload.1, payload: payload.0, captureIssue: nil)
            return
        }

        guard let callbackId = callbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: result)
        commandDelegate.send(pluginResult, callbackId: callbackId)
    }

    func documentCaptureDidCancel(_ controller: DocumentCaptureViewController) {
        controller.dismiss(animated: true)
        let callbackId = captureCallbackId
        captureCallbackId = nil

        // A cancelled camera must not discard a chip read that already cost the customer a tap and
        // possibly a liveness check. The payload goes back without the photographs, saying so.
        if let payload = takePayloadAwaitingCapture() {
            sendFinalPayload(callbackId: payload.1, payload: payload.0,
                             captureIssue: "CAPTURE_CANCELLED")
            return
        }

        guard let callbackId = callbackId else { return }
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR,
                                           messageAs: "Document capture was cancelled.")
        commandDelegate.send(pluginResult, callbackId: callbackId)
    }

    /// The chip payload held for captureAndReadNFC's last phase, cleared as it is taken so a
    /// second capture cannot deliver the same read twice.
    private func takePayloadAwaitingCapture() -> ([String: Any], String?)? {
        guard let payload = payloadAwaitingCapture else { return nil }
        let callbackId = payloadAwaitingCaptureCallbackId
        payloadAwaitingCapture = nil
        payloadAwaitingCaptureCallbackId = nil
        return (payload, callbackId)
    }
}

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
