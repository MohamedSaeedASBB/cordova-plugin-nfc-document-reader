import UIKit
import AVFoundation
import Vision

/// Delegate protocol for MRZ camera scan results.
protocol MrzCameraViewControllerDelegate: AnyObject {
    func mrzCameraViewController(_ controller: MrzCameraViewController, didDetectMRZ result: MrzCameraResult)
    func mrzCameraViewControllerDidCancel(_ controller: MrzCameraViewController)
}

/// Camera view controller for scanning MRZ lines using Vision framework OCR.
class MrzCameraViewController: UIViewController {

    weak var delegate: MrzCameraViewControllerDelegate?
    var documentType: String = "id"

    /// Supplied by the plugin before presentation; the bundle always arrives complete.
    var labels = Labels(nil, direction: nil)

    /// How the scanner paces itself, and how sure it has to be before accepting a read.
    /// Mirrors MrzCameraActivity: a single frame is a weak basis for a decision the rest of the
    /// flow depends on, so requiredMatches frames must agree on the complete MRZ, sampled at most
    /// every frameIntervalMs. See the note there for the misread that prompted it.
    var frameIntervalMs: Double = 250
    var requiredMatches: Int = 2
    private var lastProcessedAt: TimeInterval = 0
    private var candidateKey: String?
    private var candidateMatches = 0

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let mrzProcessor = MrzOcrProcessor()
    private var mrzDetected = false

    // UI elements
    private let topBar = UIView()
    private let titleLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let guideFrame = UIView()
    private let guideLabel = UILabel()
    private let bottomPanel = UIView()
    private let statusLabel = UILabel()
    private let resultCard = UIView()
    private let resultHeading = UILabel()
    private let resultLabel = UILabel()
    private let confirmButton = UIButton(type: .system)
    private let rescanButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private var detectedResult: MrzCameraResult?

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        labels.applyDirection(to: view)
        view.backgroundColor = .black
        setupUI()
        checkCameraPermissionAndStart()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    override var prefersStatusBarHidden: Bool { return true }

    // MARK: - UI Setup

    private func setupUI() {
        // ---- Top Bar ----
        topBar.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        titleLabel.text = labels.text("mrz.title")
        titleLabel.textColor = .white
        titleLabel.font = .boldSystemFont(ofSize: 18)
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(titleLabel)

        closeButton.setTitle("\u{2715}", for: .normal)
        closeButton.accessibilityLabel = labels.text("mrz.button.close")
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 22)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        topBar.addSubview(closeButton)

        // ---- Guide Frame ----
        guideFrame.backgroundColor = .clear
        guideFrame.layer.borderColor = UIColor.white.cgColor
        guideFrame.layer.borderWidth = 2
        guideFrame.layer.cornerRadius = 8
        guideFrame.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(guideFrame)

        guideLabel.text = labels.text(documentType == "passport"
            ? "mrz.hint.passport" : "mrz.hint.id")
        guideLabel.textColor = .white
        guideLabel.font = .systemFont(ofSize: 13)
        guideLabel.textAlignment = .center
        guideLabel.numberOfLines = 0
        guideLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(guideLabel)

        // ---- Bottom Panel ----
        bottomPanel.backgroundColor = UIColor.black.withAlphaComponent(0.85)
        bottomPanel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomPanel)

        // Status label
        statusLabel.text = labels.text("mrz.status.scanning")
        statusLabel.textColor = UIColor.white.withAlphaComponent(0.8)
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.textAlignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.addSubview(statusLabel)

        // Result card (hidden initially)
        resultCard.backgroundColor = UIColor.white.withAlphaComponent(0.1)
        resultCard.layer.cornerRadius = 10
        resultCard.isHidden = true
        resultCard.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.addSubview(resultCard)

        resultHeading.text = labels.text("mrz.result.title")
        resultHeading.textColor = UIColor(red: 0.30, green: 0.69, blue: 0.31, alpha: 1.0)
        resultHeading.font = .boldSystemFont(ofSize: 16)
        resultHeading.textAlignment = .center
        resultHeading.numberOfLines = 0
        resultHeading.translatesAutoresizingMaskIntoConstraints = false
        resultCard.addSubview(resultHeading)

        resultLabel.textColor = .white
        resultLabel.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
        resultLabel.textAlignment = .center
        resultLabel.numberOfLines = 0
        resultLabel.translatesAutoresizingMaskIntoConstraints = false
        resultCard.addSubview(resultLabel)

        // Confirm button
        confirmButton.setTitle("\u{2713}  " + labels.text("mrz.button.confirm"), for: .normal)
        confirmButton.backgroundColor = UIColor(red: 0.2, green: 0.7, blue: 0.3, alpha: 1.0)
        confirmButton.setTitleColor(.white, for: .normal)
        confirmButton.titleLabel?.font = .boldSystemFont(ofSize: 16)
        confirmButton.layer.cornerRadius = 10
        confirmButton.isHidden = true
        confirmButton.translatesAutoresizingMaskIntoConstraints = false
        confirmButton.addTarget(self, action: #selector(confirmTapped), for: .touchUpInside)
        bottomPanel.addSubview(confirmButton)

        // Re-scan button
        rescanButton.setTitle("\u{21BB}  " + labels.text("mrz.button.rescan"), for: .normal)
        rescanButton.backgroundColor = UIColor.white.withAlphaComponent(0.15)
        rescanButton.setTitleColor(.white, for: .normal)
        rescanButton.titleLabel?.font = .boldSystemFont(ofSize: 16)
        rescanButton.layer.cornerRadius = 10
        rescanButton.layer.borderColor = UIColor.white.withAlphaComponent(0.3).cgColor
        rescanButton.layer.borderWidth = 1
        rescanButton.isHidden = true
        rescanButton.translatesAutoresizingMaskIntoConstraints = false
        rescanButton.addTarget(self, action: #selector(rescanTapped), for: .touchUpInside)
        bottomPanel.addSubview(rescanButton)

        // Cancel button
        cancelButton.setTitle(labels.text("mrz.button.cancel"), for: .normal)
        cancelButton.setTitleColor(UIColor.white.withAlphaComponent(0.7), for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 15)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        bottomPanel.addSubview(cancelButton)

        // ---- Constraints ----
        NSLayoutConstraint.activate([
            // Top bar
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

            // Guide frame (centered, landscape rectangle)
            guideFrame.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            guideFrame.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -40),
            guideFrame.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.85),
            guideFrame.heightAnchor.constraint(equalToConstant: documentType == "passport" ? 80 : 120),

            guideLabel.topAnchor.constraint(equalTo: guideFrame.bottomAnchor, constant: 12),
            guideLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            guideLabel.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.8),

            // Bottom panel
            bottomPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomPanel.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            statusLabel.topAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: 16),
            statusLabel.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),

            // Result card
            resultCard.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 12),
            resultCard.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 16),
            resultCard.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -16),

            resultHeading.topAnchor.constraint(equalTo: resultCard.topAnchor, constant: 12),
            resultHeading.leadingAnchor.constraint(equalTo: resultCard.leadingAnchor, constant: 12),
            resultHeading.trailingAnchor.constraint(equalTo: resultCard.trailingAnchor, constant: -12),

            resultLabel.topAnchor.constraint(equalTo: resultHeading.bottomAnchor, constant: 8),
            resultLabel.leadingAnchor.constraint(equalTo: resultCard.leadingAnchor, constant: 12),
            resultLabel.trailingAnchor.constraint(equalTo: resultCard.trailingAnchor, constant: -12),
            resultLabel.bottomAnchor.constraint(equalTo: resultCard.bottomAnchor, constant: -12),

            // Confirm button
            confirmButton.topAnchor.constraint(equalTo: resultCard.bottomAnchor, constant: 12),
            confirmButton.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 16),
            confirmButton.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -16),
            confirmButton.heightAnchor.constraint(equalToConstant: 48),

            // Re-scan button
            rescanButton.topAnchor.constraint(equalTo: confirmButton.bottomAnchor, constant: 8),
            rescanButton.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 16),
            rescanButton.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -16),
            rescanButton.heightAnchor.constraint(equalToConstant: 48),

            // Cancel button
            cancelButton.topAnchor.constraint(equalTo: rescanButton.bottomAnchor, constant: 8),
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
                        self?.showPermissionDenied()
                    }
                }
            }
        default:
            showPermissionDenied()
        }
    }

    private func startCamera() {
        let session = AVCaptureSession()
        session.sessionPreset = .hd1280x720

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            statusLabel.text = labels.text("mrz.status.cameraUnavailable")
            return
        }

        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "mrz.camera.queue"))
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        session.addOutput(output)

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

    private func showPermissionDenied() {
        statusLabel.text = labels.text("mrz.status.permissionRequired")
    }

    // MARK: - Actions

    @objc private func confirmTapped() {
        guard let result = detectedResult else { return }
        delegate?.mrzCameraViewController(self, didDetectMRZ: result)
    }

    @objc private func rescanTapped() {
        // Reset state
        mrzDetected = false
        detectedResult = nil
        candidateKey = nil
        candidateMatches = 0

        // Reset UI
        guideFrame.layer.borderColor = UIColor.white.cgColor
        statusLabel.text = labels.text("mrz.status.scanning")
        resultCard.isHidden = true
        confirmButton.isHidden = true
        rescanButton.isHidden = true
    }

    @objc private func cancelTapped() {
        delegate?.mrzCameraViewControllerDidCancel(self)
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension MrzCameraViewController: AVCaptureVideoDataOutputSampleBufferDelegate {

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard !mrzDetected else { return }

        // Dropped without recognising it: the camera delivers frames far faster than a document
        // changes, and recognising every one spends battery and heat taking repeated looks at the
        // same blur.
        let now = Date().timeIntervalSince1970 * 1000
        guard now - lastProcessedAt >= frameIntervalMs else { return }
        lastProcessedAt = now

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let request = VNRecognizeTextRequest { [weak self] request, error in
            guard let self = self, !self.mrzDetected else { return }

            guard let observations = request.results as? [VNRecognizedTextObservation] else { return }

            var lines: [String] = []
            for observation in observations {
                guard let candidate = observation.topCandidates(1).first else { continue }
                lines.append(candidate.string)
            }

            if let result = self.mrzProcessor.processLines(lines) {
                guard self.agreesWithCandidate(result) else {
                    let seen = self.candidateMatches
                    DispatchQueue.main.async {
                        self.statusLabel.text = self.labels.text("mrz.status.confirming",
                            ["seen": String(seen), "required": String(self.requiredMatches)])
                    }
                    return
                }
                self.mrzDetected = true
                self.detectedResult = result
                DispatchQueue.main.async {
                    self.showResult(result)
                }
            }
        }

        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        try? handler.perform([request])
    }

    /// Counts how many consecutive reads agree. The whole MRZ is the key, not just the fields the
    /// chip needs: the name line carries no check digit, so a misread there is invisible until
    /// something downstream compares the print against the chip.
    private func agreesWithCandidate(_ result: MrzCameraResult) -> Bool {
        let candidate = result.rawLines.joined(separator: "\n")
        if candidate == candidateKey {
            candidateMatches += 1
        } else {
            candidateKey = candidate
            candidateMatches = 1
        }
        return candidateMatches >= requiredMatches
    }

    private func showResult(_ result: MrzCameraResult) {
        // Update guide frame to green
        guideFrame.layer.borderColor = UIColor(red: 0.2, green: 0.8, blue: 0.3, alpha: 1.0).cgColor

        // Update status
        statusLabel.text = labels.text("mrz.status.detected", ["format": result.format])

        // Show result card
        resultLabel.text = labels.text("mrz.result.documentNumber") + ": \(result.documentNumber)\n"
            + labels.text("mrz.result.dateOfBirth") + ": \(result.dateOfBirth)\n"
            + labels.text("mrz.result.dateOfExpiry") + ": \(result.dateOfExpiry)"
        resultCard.isHidden = false

        // Show buttons
        confirmButton.isHidden = false
        rescanButton.isHidden = false
    }
}
