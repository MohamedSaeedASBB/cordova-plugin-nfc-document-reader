import UIKit
import AVFoundation
import CoreMedia
import MLKitFaceDetection
import MLKitVision

/// Delegate protocol for liveness check results.
protocol LivenessCameraViewControllerDelegate: AnyObject {
    /// `result` is the fully-built JS payload, images already compressed.
    func livenessCameraViewController(_ controller: LivenessCameraViewController,
                                      didComplete result: [String: Any])
    func livenessCameraViewController(_ controller: LivenessCameraViewController,
                                      didFailWith code: String, message: String)
}

/// Front-camera liveness check: AVFoundation preview plus ML Kit face detection, driving the
/// challenge-response sequence in `LivenessDetector`.
///
/// The portrait handed back is captured from the same analysed frame stream that satisfied the
/// challenges — not from a separate still capture afterwards — and is compressed by
/// `ImageCompressor` before it crosses into the WebView.
class LivenessCameraViewController: UIViewController {

    weak var delegate: LivenessCameraViewControllerDelegate?
    var options = LivenessOptions()

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let videoQueue = DispatchQueue(label: "liveness.camera.queue")
    private let ciContext = CIContext(options: nil)

    private var faceDetector: FaceDetector?
    private var detector: LivenessDetector?
    private var finished = false
    private var lastFrameTimestampMs: Double = 0

    /// Best neutral frame so far, already oriented, plus its face box in that frame's pixels.
    private var portraitFrame: UIImage?
    private var portraitFaceBox: CGRect?
    private var challengeFrames: [(challenge: LivenessDetector.Challenge, image: UIImage, faceBox: CGRect)] = []

    // UI elements
    private let topBar = UIView()
    private let titleLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let guideOvalLayer = CAShapeLayer()
    private let guideContainer = UIView()
    private let bottomPanel = UIView()
    private let stepLabel = UILabel()
    private let promptLabel = UILabel()
    private let hintLabel = UILabel()
    private let cancelButton = UIButton(type: .system)

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        detector = LivenessDetector(config: options.detectorConfig())
        faceDetector = FaceDetector.faceDetector(options: makeDetectorOptions())

        setupUI()
        checkCameraPermissionAndStart()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        guideOvalLayer.path = UIBezierPath(ovalIn: guideContainer.bounds).cgPath
        guideOvalLayer.frame = guideContainer.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    override var prefersStatusBarHidden: Bool { return true }

    /// Portrait-locked: the ML Kit image orientation below assumes a portrait device.
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { return .portrait }

    private func makeDetectorOptions() -> FaceDetectorOptions {
        let detectorOptions = FaceDetectorOptions()
        // .fast keeps the frame rate high enough to catch a blink; .accurate is for stills and
        // would drop the transition frames this check depends on.
        detectorOptions.performanceMode = .fast
        detectorOptions.classificationMode = .all
        detectorOptions.landmarkMode = .none
        detectorOptions.contourMode = .none
        detectorOptions.minFaceSize = 0.15
        // Tracking ids let us detect the face being swapped mid-session. ML Kit only honours
        // this when classification or landmarks are on, which is why .all is set above.
        detectorOptions.isTrackingEnabled = true
        return detectorOptions
    }

    // MARK: - UI Setup

    private func setupUI() {
        // ---- Top Bar ----
        topBar.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        titleLabel.text = "Liveness Check"
        titleLabel.textColor = .white
        titleLabel.font = .boldSystemFont(ofSize: 18)
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(titleLabel)

        closeButton.setTitle("\u{2715}", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 22)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        topBar.addSubview(closeButton)

        // ---- Face guide oval ----
        guideContainer.backgroundColor = .clear
        guideContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(guideContainer)

        guideOvalLayer.fillColor = UIColor.clear.cgColor
        guideOvalLayer.strokeColor = UIColor.white.cgColor
        guideOvalLayer.lineWidth = 3
        guideContainer.layer.addSublayer(guideOvalLayer)

        // ---- Bottom Panel ----
        bottomPanel.backgroundColor = UIColor.black.withAlphaComponent(0.85)
        bottomPanel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomPanel)

        stepLabel.text = ""
        stepLabel.textColor = UIColor(red: 0.56, green: 0.79, blue: 0.98, alpha: 1.0)
        stepLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        stepLabel.textAlignment = .center
        stepLabel.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.addSubview(stepLabel)

        promptLabel.text = options.prompt("findFace")
        promptLabel.textColor = .white
        promptLabel.font = .boldSystemFont(ofSize: 20)
        promptLabel.textAlignment = .center
        promptLabel.numberOfLines = 0
        promptLabel.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.addSubview(promptLabel)

        hintLabel.text = options.prompt("hint")
        hintLabel.textColor = UIColor.white.withAlphaComponent(0.6)
        hintLabel.font = .systemFont(ofSize: 13)
        hintLabel.textAlignment = .center
        hintLabel.numberOfLines = 0
        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.addSubview(hintLabel)

        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.setTitleColor(UIColor.white.withAlphaComponent(0.7), for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 15)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        bottomPanel.addSubview(cancelButton)

        // ---- Constraints ----
        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: view.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            titleLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            titleLabel.centerXAnchor.constraint(equalTo: topBar.centerXAnchor),
            titleLabel.bottomAnchor.constraint(equalTo: topBar.bottomAnchor, constant: -12),

            closeButton.centerYAnchor.constraint(equalTo: titleLabel.centerYAnchor),
            closeButton.trailingAnchor.constraint(equalTo: topBar.trailingAnchor, constant: -16),
            closeButton.widthAnchor.constraint(equalToConstant: 40),
            closeButton.heightAnchor.constraint(equalToConstant: 40),

            // Face guide oval (centered, portrait ellipse)
            guideContainer.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            guideContainer.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -60),
            guideContainer.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.68),
            guideContainer.heightAnchor.constraint(equalTo: guideContainer.widthAnchor, multiplier: 1.3),

            bottomPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomPanel.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            stepLabel.topAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: 18),
            stepLabel.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            stepLabel.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),

            promptLabel.topAnchor.constraint(equalTo: stepLabel.bottomAnchor, constant: 6),
            promptLabel.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            promptLabel.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
            promptLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 56),

            hintLabel.topAnchor.constraint(equalTo: promptLabel.bottomAnchor, constant: 8),
            hintLabel.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            hintLabel.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),

            cancelButton.topAnchor.constraint(equalTo: hintLabel.bottomAnchor, constant: 16),
            cancelButton.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 16),
            cancelButton.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -16),
            cancelButton.heightAnchor.constraint(equalToConstant: 44),
            cancelButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
        ])
    }

    // MARK: - Camera

    private func checkCameraPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.startCamera()
                    } else {
                        self?.finish(failure: "CAMERA_PERMISSION_DENIED",
                                     message: "Camera permission is required for the liveness check.")
                    }
                }
            }
        default:
            finish(failure: "CAMERA_PERMISSION_DENIED",
                   message: "Camera permission is required for the liveness check.")
        }
    }

    private func startCamera() {
        let session = AVCaptureSession()
        session.sessionPreset = .hd1280x720

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device) else {
            finish(failure: "CAMERA_UNAVAILABLE", message: "Could not start the front camera.")
            return
        }

        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: videoQueue)
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        session.addOutput(output)

        // Keep the analysed buffers deterministic: never mirrored here. The mirroring the user
        // sees is the preview layer's, and the mirroring ML Kit sees comes from the explicit
        // .leftMirrored orientation below.
        if let connection = output.connection(with: .video), connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.insertSublayer(previewLayer, at: 0)
        self.previewLayer = previewLayer

        captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    // MARK: - Actions

    @objc private func cancelTapped() {
        finish(failure: "CANCELLED", message: "Liveness check cancelled")
    }

    // MARK: - UI updates

    private func render(_ update: LivenessDetector.Update) {
        let prompt = options.prompt(update.promptKey)
        let step = stepLabelText(for: update)
        let framed = update.state == .challenge || update.state == .capturing

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.promptLabel.text = prompt
            self.stepLabel.text = step
            if update.finished {
                self.guideOvalLayer.strokeColor = update.passed
                    ? UIColor(red: 0.30, green: 0.69, blue: 0.31, alpha: 1.0).cgColor
                    : UIColor(red: 0.90, green: 0.22, blue: 0.21, alpha: 1.0).cgColor
            } else {
                self.guideOvalLayer.strokeColor = framed
                    ? UIColor(red: 0.39, green: 0.71, blue: 0.96, alpha: 1.0).cgColor
                    : UIColor.white.cgColor
            }
        }
    }

    private func stepLabelText(for update: LivenessDetector.Update) -> String {
        guard update.state == .challenge,
              let challenge = update.challenge,
              let order = detector?.challengeOrder,
              let index = order.firstIndex(of: challenge) else {
            return ""
        }
        return "STEP \(index + 1) OF \(order.count)"
    }

    // MARK: - Completion

    private func finish(failure code: String, message: String) {
        guard !finished else { return }
        finished = true
        stopSession()
        // Frame state is only touched on the video queue, so clean up there — this method can be
        // called from either the main thread (Cancel) or the video queue (a failed challenge).
        videoQueue.async { [weak self] in
            self?.releaseFrames()
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.livenessCameraViewController(self, didFailWith: code, message: message)
        }
    }

    /// `stopRunning()` blocks until the session drains, and deadlocks if called from the
    /// sample-buffer delegate queue — always hop off it first.
    private func stopSession() {
        let session = captureSession
        DispatchQueue.global(qos: .userInitiated).async {
            session?.stopRunning()
        }
    }

    /// Builds the JS payload on the video queue: JPEG encoding a few frames is enough to stutter
    /// the UI if run on the main thread.
    private func finishWithSuccess() {
        guard !finished else { return }
        finished = true
        stopSession()

        // Already on the video queue (called from captureOutput), where the frame state lives.
        let payload = buildResult()
        releaseFrames()

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.livenessCameraViewController(self, didComplete: payload)
        }
    }

    private func buildResult() -> [String: Any] {
        var result: [String: Any] = [
            "passed": true,
            "capturedAt": ISO8601DateFormatter().string(from: Date())
        ]

        let imageOptions = options.imageOptions()

        if let portrait = portraitFrame,
           let compressed = ImageCompressor.compress(portrait, faceBox: portraitFaceBox, options: imageOptions) {
            result["faceImageBase64"] = compressed.base64
            result["faceImageMimeType"] = "image/jpeg"
            result["faceImageWidth"] = compressed.width
            result["faceImageHeight"] = compressed.height
            result["faceImageBytes"] = compressed.data.count
            result["faceImageJpegQuality"] = Int((compressed.quality * 100).rounded())

            if options.includeFullFrame {
                var fullFrameOptions = imageOptions
                fullFrameOptions.cropToFace = false
                if let fullFrame = ImageCompressor.compress(portrait, faceBox: nil, options: fullFrameOptions) {
                    result["fullFrameImageBase64"] = fullFrame.base64
                    result["fullFrameImageBytes"] = fullFrame.data.count
                }
            }
        } else {
            result["faceImageBase64"] = NSNull()
        }

        // ---- Per-challenge outcomes, for back-office scoring ----
        let challengeResults: [[String: Any]] = (detector?.results ?? []).map { entry in
            return [
                "type": entry.type.rawValue,
                "passed": entry.passed,
                "durationMs": Int(entry.durationMs)
            ]
        }
        result["challenges"] = challengeResults

        if options.includeChallengeFrames && !challengeFrames.isEmpty {
            var frames: [[String: Any]] = []
            for captured in challengeFrames {
                guard let compressed = ImageCompressor.compress(captured.image,
                                                                faceBox: captured.faceBox,
                                                                options: imageOptions) else { continue }
                frames.append([
                    "challenge": captured.challenge.rawValue,
                    "imageBase64": compressed.base64,
                    "imageBytes": compressed.data.count
                ])
            }
            result["challengeFrames"] = frames
        }

        // ---- Session signals: no image data, safe to log or forward for scoring ----
        if let detector = detector {
            result["signals"] = [
                "framesAnalysed": detector.framesAnalysed,
                "durationMs": Int(detector.elapsedMs(now: lastFrameTimestampMs)),
                "multiFaceFrames": detector.multiFaceFrames,
                "trackingIdChanges": detector.trackingIdChanges
            ]
        }

        result["sdk"] = [
            "provider": "mlkit",
            "feature": "face-detection",
            "platform": "ios",
            // ML Kit face detection provides no presentation-attack detection. This flag tells
            // the back office not to treat `passed` as proof of a live human.
            "presentationAttackDetection": false
        ]

        return result
    }

    private func releaseFrames() {
        portraitFrame = nil
        portraitFaceBox = nil
        challengeFrames.removeAll()
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension LivenessCameraViewController: AVCaptureVideoDataOutputSampleBufferDelegate {

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard !finished,
              let detector = detector,
              let faceDetector = faceDetector,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let visionImage = VisionImage(buffer: sampleBuffer)
        // Portrait device + front camera + un-mirrored buffer: this is the mapping ML Kit's own
        // front-camera samples use. It means ML Kit sees a mirrored image, which is why
        // LivenessDetector.yawSignUserLeft is negative on iOS.
        visionImage.orientation = .leftMirrored

        let faces: [Face]
        do {
            // Synchronous on the video queue — never call results(in:) on the main thread.
            faces = try faceDetector.results(in: visionImage)
        } catch {
            NSLog("[Liveness] Face detection error: %@", error.localizedDescription)
            return
        }

        // With .leftMirrored the reported coordinates are in the rotated (portrait) space, so
        // the axes swap relative to the raw buffer.
        let frameWidth = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        let frameHeight = CGFloat(CVPixelBufferGetWidth(pixelBuffer))

        let timestampMs = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 1000
        lastFrameTimestampMs = timestampMs

        let face = largestFace(faces)
        let observation = makeObservation(faceCount: faces.count,
                                          face: face,
                                          frameWidth: frameWidth,
                                          frameHeight: frameHeight,
                                          timestampMs: timestampMs)

        let update = detector.onFrame(observation)

        if update.capturePortrait, let face = face,
           let frame = orientedImage(from: pixelBuffer) {
            portraitFrame = frame
            portraitFaceBox = face.frame
        }

        if let challenge = update.captureChallengeFrame, options.includeChallengeFrames,
           let face = face, let frame = orientedImage(from: pixelBuffer) {
            challengeFrames.append((challenge: challenge, image: frame, faceBox: face.frame))
        }

        render(update)

        guard update.finished else { return }

        if update.passed {
            finishWithSuccess()
        } else {
            finish(failure: update.failureCode ?? "UNKNOWN",
                   message: Self.failureMessage(update.failureCode))
        }
    }

    private func largestFace(_ faces: [Face]) -> Face? {
        return faces.max { lhs, rhs in
            (lhs.frame.width * lhs.frame.height) < (rhs.frame.width * rhs.frame.height)
        }
    }

    private func makeObservation(faceCount: Int,
                                 face: Face?,
                                 frameWidth: CGFloat,
                                 frameHeight: CGFloat,
                                 timestampMs: Double) -> LivenessDetector.Observation {
        var obs = LivenessDetector.Observation()
        obs.timestampMs = timestampMs
        obs.faceCount = faceCount

        guard let face = face, frameWidth > 0, frameHeight > 0 else { return obs }

        let box = face.frame
        obs.areaRatio = Float((box.width * box.height) / (frameWidth * frameHeight))
        obs.centerOffsetX = Float((box.midX - frameWidth / 2) / frameWidth)
        obs.centerOffsetY = Float((box.midY - frameHeight / 2) / frameHeight)

        if face.hasHeadEulerAngleY { obs.yaw = Float(face.headEulerAngleY) }
        if face.hasHeadEulerAngleX { obs.pitch = Float(face.headEulerAngleX) }
        if face.hasHeadEulerAngleZ { obs.roll = Float(face.headEulerAngleZ) }
        if face.hasLeftEyeOpenProbability { obs.leftEyeOpen = Float(face.leftEyeOpenProbability) }
        if face.hasRightEyeOpenProbability { obs.rightEyeOpen = Float(face.rightEyeOpenProbability) }
        if face.hasSmilingProbability { obs.smiling = Float(face.smilingProbability) }
        if face.hasTrackingID { obs.trackingId = face.trackingID }

        return obs
    }

    /// Renders the frame into the same coordinate space ML Kit reported face boxes in, so the
    /// crop lines up. `ImageCompressor` flips the final output back to true orientation.
    private func orientedImage(from pixelBuffer: CVPixelBuffer) -> UIImage? {
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else {
            return nil
        }

        let oriented = UIImage(cgImage: cgImage, scale: 1, orientation: .leftMirrored)

        // Bake the orientation into the pixels: CGImage cropping ignores UIImage.imageOrientation.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: oriented.size, format: format)
        return renderer.image { _ in
            oriented.draw(in: CGRect(origin: .zero, size: oriented.size))
        }
    }

    static func failureMessage(_ code: String?) -> String {
        switch code {
        case "NO_FACE_TIMEOUT":
            return "We could not see your face. Please try again in better lighting."
        case "CHALLENGE_TIMEOUT":
            return "The on-screen instruction was not completed in time. Please try again."
        case "MULTIPLE_FACES":
            return "More than one face was visible. Please make sure you are alone in frame."
        case "FACE_CHANGED":
            return "The face in front of the camera changed during the check. Please try again."
        case "OVERALL_TIMEOUT":
            return "The liveness check took too long. Please try again."
        case "NO_USABLE_PORTRAIT":
            return "We could not capture a clear photo. Please try again in better lighting."
        default:
            return "The liveness check could not be completed. Please try again."
        }
    }
}
