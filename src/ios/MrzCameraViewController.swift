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

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let mrzProcessor = MrzOcrProcessor()
    private var mrzDetected = false

    private let statusLabel = UILabel()
    private let resultLabel = UILabel()
    private let confirmButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let resultContainerView = UIView()
    private var detectedResult: MrzCameraResult?

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
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

    // MARK: - UI Setup

    private func setupUI() {
        // Status label
        statusLabel.textColor = .white
        statusLabel.textAlignment = .center
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.numberOfLines = 0
        if documentType == "passport" {
            statusLabel.text = "Point camera at the MRZ lines on your passport data page"
        } else {
            statusLabel.text = "Point camera at the MRZ lines on the back of your ID card"
        }
        statusLabel.translatesAutoresizingMaskIntoConstraints = false

        // Result container
        resultContainerView.isHidden = true
        resultContainerView.translatesAutoresizingMaskIntoConstraints = false

        // Result label
        resultLabel.textColor = .white
        resultLabel.textAlignment = .center
        resultLabel.font = .systemFont(ofSize: 12)
        resultLabel.numberOfLines = 0
        resultLabel.translatesAutoresizingMaskIntoConstraints = false

        // Confirm button
        confirmButton.setTitle("Use This MRZ Data", for: .normal)
        confirmButton.backgroundColor = .systemBlue
        confirmButton.setTitleColor(.white, for: .normal)
        confirmButton.layer.cornerRadius = 8
        confirmButton.translatesAutoresizingMaskIntoConstraints = false
        confirmButton.addTarget(self, action: #selector(confirmTapped), for: .touchUpInside)

        // Cancel button
        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        // Bottom overlay
        let overlayView = UIView()
        overlayView.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        overlayView.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(overlayView)
        overlayView.addSubview(statusLabel)
        overlayView.addSubview(resultContainerView)
        resultContainerView.addSubview(resultLabel)
        resultContainerView.addSubview(confirmButton)
        overlayView.addSubview(cancelButton)

        NSLayoutConstraint.activate([
            overlayView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlayView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlayView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            statusLabel.topAnchor.constraint(equalTo: overlayView.topAnchor, constant: 16),
            statusLabel.leadingAnchor.constraint(equalTo: overlayView.leadingAnchor, constant: 16),
            statusLabel.trailingAnchor.constraint(equalTo: overlayView.trailingAnchor, constant: -16),

            resultContainerView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 8),
            resultContainerView.leadingAnchor.constraint(equalTo: overlayView.leadingAnchor, constant: 16),
            resultContainerView.trailingAnchor.constraint(equalTo: overlayView.trailingAnchor, constant: -16),

            resultLabel.topAnchor.constraint(equalTo: resultContainerView.topAnchor),
            resultLabel.leadingAnchor.constraint(equalTo: resultContainerView.leadingAnchor),
            resultLabel.trailingAnchor.constraint(equalTo: resultContainerView.trailingAnchor),

            confirmButton.topAnchor.constraint(equalTo: resultLabel.bottomAnchor, constant: 8),
            confirmButton.leadingAnchor.constraint(equalTo: resultContainerView.leadingAnchor),
            confirmButton.trailingAnchor.constraint(equalTo: resultContainerView.trailingAnchor),
            confirmButton.heightAnchor.constraint(equalToConstant: 44),
            confirmButton.bottomAnchor.constraint(equalTo: resultContainerView.bottomAnchor),

            cancelButton.topAnchor.constraint(equalTo: resultContainerView.bottomAnchor, constant: 8),
            cancelButton.leadingAnchor.constraint(equalTo: overlayView.leadingAnchor, constant: 16),
            cancelButton.trailingAnchor.constraint(equalTo: overlayView.trailingAnchor, constant: -16),
            cancelButton.heightAnchor.constraint(equalToConstant: 44),
            cancelButton.bottomAnchor.constraint(equalTo: overlayView.safeAreaLayoutGuide.bottomAnchor, constant: -8),
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
            statusLabel.text = "Camera not available"
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
        statusLabel.text = "Camera permission is required to scan MRZ"
    }

    // MARK: - Actions

    @objc private func confirmTapped() {
        guard let result = detectedResult else { return }
        delegate?.mrzCameraViewController(self, didDetectMRZ: result)
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
                self.mrzDetected = true
                self.detectedResult = result
                DispatchQueue.main.async {
                    self.showResult(result)
                }
            } else if !lines.isEmpty {
                DispatchQueue.main.async {
                    self.statusLabel.text = "Detecting... (\(lines.count) lines found)"
                }
            }
        }

        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        try? handler.perform([request])
    }

    private func showResult(_ result: MrzCameraResult) {
        statusLabel.text = "MRZ detected (\(result.format))!"
        resultContainerView.isHidden = false
        resultLabel.text = "Doc: \(result.documentNumber)\nDOB: \(result.dateOfBirth) | Exp: \(result.dateOfExpiry)"
    }
}
