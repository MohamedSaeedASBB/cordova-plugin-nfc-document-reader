import UIKit
import AVFoundation
import Vision

/// One page to photograph.
struct DocumentCaptureStep {
    let key: String
    let label: String
    let hint: String
}

protocol DocumentCaptureViewControllerDelegate: AnyObject {
    func documentCapture(_ controller: DocumentCaptureViewController, didFinish result: [String: Any])
    func documentCaptureDidCancel(_ controller: DocumentCaptureViewController)
}

/// Photographs a physical document, one step at a time. Mirrors DocumentCaptureActivity.
///
/// Distinct from MrzCameraViewController, which reads the machine-readable zone to derive chip
/// keys and throws the frame away. This keeps the picture: an ID card needs both sides, a passport
/// needs only its data page, and a proof of address is a single page of something with no fixed
/// shape at all. The steps are supplied by the caller, so all three are the same screen.
///
/// Each step is shot, checked, reviewed, and either kept or retaken before moving on. Review earns
/// its place twice over: nothing downstream can tell the operator that a photo is too blurry while
/// they can still redo it, and the evidence check's verdict is only useful in front of the person
/// holding the card.
class DocumentCaptureViewController: UIViewController {

    weak var delegate: DocumentCaptureViewControllerDelegate?

    var captureType: String = "document"
    var documentType: String?
    var steps: [DocumentCaptureStep] = []
    var runOcr = false
    var verifyDocument = true
    var requireDocument = false
    var expectedIdentifiers: [String] = []
    var imageOptions = ImageCompressor.Options()

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let photoOutput = AVCapturePhotoOutput()

    private var stepIndex = 0
    private var captured: [[String: Any]] = []
    private var order: [String] = []
    private var pendingImage: UIImage?
    private var pendingCheck: DocumentEvidenceCheck.Result?

    // UI
    private let previewContainer = UIView()
    private let reviewImageView = UIImageView()
    private let titleLabel = UILabel()
    private let stepLabel = UILabel()
    private let hintLabel = UILabel()
    private let guideFrame = UIView()
    private let captureButton = UIButton(type: .system)
    private let retakeButton = UIButton(type: .system)
    private let useButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)

    /// The two sides of an ID card, or the single data page of a passport. Mirrors
    /// DocumentCaptureOptions.stepsForDocumentType.
    static func steps(forDocumentType documentType: String) -> [DocumentCaptureStep] {
        if documentType.lowercased() == "passport" {
            return [DocumentCaptureStep(key: "front", label: "Passport photo page",
                                        hint: "Open the passport at the photo page and fill the frame")]
        }
        return [
            DocumentCaptureStep(key: "front", label: "Front of the card",
                                hint: "Place the front of the card flat and fill the frame"),
            DocumentCaptureStep(key: "back", label: "Back of the card",
                                hint: "Now turn the card over and photograph the back")
        ]
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupUI()
        showStep()
        requestCameraAccess()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = previewContainer.bounds
    }

    // MARK: - Camera

    private func requestCameraAccess() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    granted ? self?.setupCamera() : self?.cancel()
                }
            }
        default:
            cancel()
        }
    }

    private func setupCamera() {
        let session = AVCaptureSession()
        // Documents are read, not glanced at: the highest still quality the device offers.
        session.sessionPreset = .photo

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input), session.canAddOutput(photoOutput) else {
            cancel()
            return
        }
        session.addInput(input)
        session.addOutput(photoOutput)

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = previewContainer.bounds
        previewContainer.layer.insertSublayer(layer, at: 0)

        self.previewLayer = layer
        self.captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    // MARK: - Steps

    private func showStep() {
        guard stepIndex < steps.count else { return }
        let step = steps[stepIndex]
        hintLabel.text = step.hint
        stepLabel.text = steps.count > 1
            ? "Step \(stepIndex + 1) of \(steps.count) — \(step.label)"
            : step.label
        showCamera()
    }

    private func showCamera() {
        pendingImage = nil
        pendingCheck = nil
        reviewImageView.isHidden = true
        reviewImageView.image = nil
        previewContainer.isHidden = false
        guideFrame.isHidden = false
        captureButton.isHidden = false
        retakeButton.isHidden = true
        useButton.isHidden = true
    }

    private func showReview(_ image: UIImage) {
        reviewImageView.image = image
        reviewImageView.isHidden = false
        previewContainer.isHidden = true
        guideFrame.isHidden = true
        captureButton.isHidden = true
        retakeButton.isHidden = false

        let confirmed = pendingCheck?.isConfirmed ?? true
        if pendingCheck == nil {
            hintLabel.text = "Check the photo is sharp and the whole document is visible"
        } else if confirmed {
            hintLabel.text = "Document recognised — check it is sharp and complete"
        } else {
            hintLabel.text = requireDocument
                ? "This does not look like the document. Please retake it."
                : "We could not recognise the document in this photo. Retake it, or use it "
                  + "anyway if it is correct."
        }
        // In strict mode a shot that failed cannot be kept; the only way on is another attempt.
        useButton.isHidden = !confirmed && requireDocument
    }

    @objc private func captureTapped() {
        captureButton.isEnabled = false
        let settings = AVCapturePhotoSettings()
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    @objc private func retakeTapped() {
        showCamera()
    }

    @objc private func useTapped() {
        guard let image = pendingImage else { return }
        useButton.isEnabled = false

        let step = steps[stepIndex]
        let check = pendingCheck
        pendingImage = nil
        pendingCheck = nil

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            var options = self.imageOptions
            // Face-cropping a document would be an absurd failure; no face box is passed either.
            options.cropToFace = false
            options.mirrorHorizontally = false

            guard let compressed = ImageCompressor.compress(image, faceBox: nil, options: options) else {
                DispatchQueue.main.async {
                    self.useButton.isEnabled = true
                    self.hintLabel.text = "That photo could not be saved. Please retake it."
                    self.showCamera()
                }
                return
            }

            var entry: [String: Any] = [
                "key": step.key,
                "label": step.label,
                "imageBase64": compressed.base64,
                "imageMimeType": "image/jpeg",
                "imageBytes": compressed.data.count,
                "imageWidth": compressed.width,
                "imageHeight": compressed.height,
                "jpegQuality": Int(compressed.quality * 100)
            ]
            if let check = check { entry["documentCheck"] = check.dictionary }
            if self.runOcr { entry["ocr"] = DocumentOcr.recognise(image) }

            DispatchQueue.main.async {
                self.useButton.isEnabled = true
                self.captured.append(entry)
                self.order.append(step.key)
                self.stepIndex += 1
                if self.stepIndex < self.steps.count {
                    self.showStep()
                } else {
                    self.finish()
                }
            }
        }
    }

    private func finish() {
        var sides: [String: Any] = [:]
        for entry in captured {
            if let key = entry["key"] as? String { sides[key] = entry }
        }
        var result: [String: Any] = [
            "captureType": captureType,
            "sides": sides,
            "order": order,
            "capturedAt": Int(Date().timeIntervalSince1970 * 1000)
        ]
        if let documentType = documentType { result["documentType"] = documentType }

        captureSession?.stopRunning()
        delegate?.documentCapture(self, didFinish: result)
    }

    @objc private func cancel() {
        captureSession?.stopRunning()
        delegate?.documentCaptureDidCancel(self)
    }

    // MARK: - UI

    private func setupUI() {
        previewContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(previewContainer)

        reviewImageView.translatesAutoresizingMaskIntoConstraints = false
        reviewImageView.contentMode = .scaleAspectFit
        reviewImageView.backgroundColor = .black
        reviewImageView.isHidden = true
        view.addSubview(reviewImageView)

        let topBar = UIView()
        topBar.translatesAutoresizingMaskIntoConstraints = false
        topBar.backgroundColor = UIColor.black.withAlphaComponent(0.8)
        view.addSubview(topBar)

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.textColor = .white
        titleLabel.font = .boldSystemFont(ofSize: 18)
        titleLabel.text = title ?? "Capture document"
        topBar.addSubview(titleLabel)

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.setTitle("✕", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 22)
        closeButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        topBar.addSubview(closeButton)

        guideFrame.translatesAutoresizingMaskIntoConstraints = false
        guideFrame.layer.borderColor = UIColor.white.cgColor
        guideFrame.layer.borderWidth = 3
        guideFrame.layer.cornerRadius = 12
        view.addSubview(guideFrame)

        let bottomPanel = UIView()
        bottomPanel.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.backgroundColor = UIColor.black.withAlphaComponent(0.87)
        view.addSubview(bottomPanel)

        stepLabel.translatesAutoresizingMaskIntoConstraints = false
        stepLabel.textColor = UIColor(red: 0.73, green: 0.87, blue: 0.98, alpha: 1)
        stepLabel.font = .systemFont(ofSize: 13)
        stepLabel.textAlignment = .center
        bottomPanel.addSubview(stepLabel)

        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        hintLabel.textColor = .white
        hintLabel.font = .systemFont(ofSize: 15)
        hintLabel.textAlignment = .center
        hintLabel.numberOfLines = 0
        bottomPanel.addSubview(hintLabel)

        style(captureButton, title: "Capture", filled: true)
        captureButton.addTarget(self, action: #selector(captureTapped), for: .touchUpInside)
        bottomPanel.addSubview(captureButton)

        style(retakeButton, title: "Retake", filled: false)
        retakeButton.addTarget(self, action: #selector(retakeTapped), for: .touchUpInside)
        retakeButton.isHidden = true
        bottomPanel.addSubview(retakeButton)

        style(useButton, title: "Use photo", filled: true)
        useButton.addTarget(self, action: #selector(useTapped), for: .touchUpInside)
        useButton.isHidden = true
        bottomPanel.addSubview(useButton)

        style(cancelButton, title: "Cancel", filled: false)
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        bottomPanel.addSubview(cancelButton)

        let guides = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            previewContainer.topAnchor.constraint(equalTo: view.topAnchor),
            previewContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            previewContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            previewContainer.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            reviewImageView.topAnchor.constraint(equalTo: view.topAnchor),
            reviewImageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            reviewImageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            reviewImageView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            topBar.topAnchor.constraint(equalTo: view.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topBar.bottomAnchor.constraint(equalTo: guides.topAnchor, constant: 56),

            titleLabel.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 16),
            titleLabel.bottomAnchor.constraint(equalTo: topBar.bottomAnchor, constant: -14),

            closeButton.trailingAnchor.constraint(equalTo: topBar.trailingAnchor, constant: -16),
            closeButton.centerYAnchor.constraint(equalTo: titleLabel.centerYAnchor),

            guideFrame.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            guideFrame.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            guideFrame.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            guideFrame.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            guideFrame.heightAnchor.constraint(equalTo: guideFrame.widthAnchor, multiplier: 0.63),

            bottomPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomPanel.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bottomPanel.topAnchor.constraint(equalTo: stepLabel.topAnchor, constant: -16),

            stepLabel.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            stepLabel.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
            stepLabel.bottomAnchor.constraint(equalTo: hintLabel.topAnchor, constant: -4),

            hintLabel.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            hintLabel.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
            hintLabel.bottomAnchor.constraint(equalTo: captureButton.topAnchor, constant: -16),

            captureButton.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            captureButton.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
            captureButton.heightAnchor.constraint(equalToConstant: 52),
            captureButton.bottomAnchor.constraint(equalTo: cancelButton.topAnchor, constant: -8),

            retakeButton.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            retakeButton.trailingAnchor.constraint(equalTo: bottomPanel.centerXAnchor, constant: -4),
            retakeButton.heightAnchor.constraint(equalToConstant: 52),
            retakeButton.bottomAnchor.constraint(equalTo: cancelButton.topAnchor, constant: -8),

            useButton.leadingAnchor.constraint(equalTo: bottomPanel.centerXAnchor, constant: 4),
            useButton.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
            useButton.heightAnchor.constraint(equalToConstant: 52),
            useButton.bottomAnchor.constraint(equalTo: cancelButton.topAnchor, constant: -8),

            cancelButton.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
            cancelButton.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
            cancelButton.heightAnchor.constraint(equalToConstant: 44),
            cancelButton.bottomAnchor.constraint(equalTo: guides.bottomAnchor, constant: -8)
        ])
    }

    private func style(_ button: UIButton, title: String, filled: Bool) {
        button.translatesAutoresizingMaskIntoConstraints = false
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16)
        button.setTitleColor(filled ? .white : UIColor(red: 0.73, green: 0.87, blue: 0.98, alpha: 1),
                             for: .normal)
        button.backgroundColor = filled ? UIColor(red: 0.1, green: 0.46, blue: 0.82, alpha: 1) : .clear
        button.layer.cornerRadius = 8
    }
}

// MARK: - AVCapturePhotoCaptureDelegate

extension DocumentCaptureViewController: AVCapturePhotoCaptureDelegate {

    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation(),
              let image = UIImage(data: data) else {
            DispatchQueue.main.async {
                self.captureButton.isEnabled = true
                self.hintLabel.text = "The photo could not be taken. Please try again."
            }
            return
        }

        // Checked before the review screen appears, and on the full-resolution frame rather than
        // the compressed copy, so the verdict is in front of the person while they can still
        // retake it.
        DispatchQueue.main.async { self.hintLabel.text = "Checking the photo..." }
        DispatchQueue.global(qos: .userInitiated).async {
            let check = self.verifyDocument
                ? DocumentEvidenceCheck.inspect(image, expected: self.expectedIdentifiers)
                : nil
            DispatchQueue.main.async {
                self.captureButton.isEnabled = true
                self.pendingImage = image
                self.pendingCheck = check
                self.showReview(image)
            }
        }
    }
}

/// Recognised text for a page, for proof-of-address captures. Vision recognises Arabic on recent
/// iOS, which ML Kit on Android does not — the payload reports which engine ran so a backend can
/// tell "no Arabic on the page" from "no Arabic in this engine".
enum DocumentOcr {
    static func recognise(_ image: UIImage) -> [String: Any] {
        guard let cgImage = image.cgImage else {
            return ["text": "", "lines": [], "lineCount": 0, "engine": "apple-vision",
                    "error": "OCR_FAILED"]
        }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate          // Arabic is unavailable at .fast
        request.usesLanguageCorrection = false
        if let supported = try? request.supportedRecognitionLanguages() {
            request.recognitionLanguages = supported
        }
        do {
            try VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])
        } catch {
            return ["text": "", "lines": [], "lineCount": 0, "engine": "apple-vision",
                    "error": "OCR_FAILED"]
        }
        let lines = (request.results ?? []).compactMap {
            $0.topCandidates(1).first?.string
        }
        let languages = (try? request.supportedRecognitionLanguages()) ?? []
        return [
            "text": lines.joined(separator: "\n"),
            "lines": lines,
            "lineCount": lines.count,
            "engine": "apple-vision",
            "scripts": ["Latin"],
            "arabicSupported": languages.contains { $0.lowercased().hasPrefix("ar") }
        ]
    }
}
