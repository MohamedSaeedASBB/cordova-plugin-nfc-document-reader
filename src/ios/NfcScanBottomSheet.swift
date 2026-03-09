import UIKit

/// Bottom sheet view controller that shows NFC scanning progress.
class NfcScanBottomSheet: UIViewController {

    var onCancel: (() -> Void)?

    // UI elements
    private let containerView = UIView()
    private let dragHandle = UIView()
    private let iconLabel = UILabel()
    private let titleLabel = UILabel()
    private let descriptionLabel = UILabel()
    private let progressBar = UIProgressView(progressViewStyle: .default)
    private let statusLabel = UILabel()
    private let cancelButton = UIButton(type: .system)

    private var containerBottomConstraint: NSLayoutConstraint?

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        setupUI()

        // Tap on dimmed area to cancel
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(dimmedAreaTapped))
        tapGesture.delegate = self
        view.addGestureRecognizer(tapGesture)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        animateIn()
    }

    // MARK: - UI Setup

    private func setupUI() {
        // Dimmed background
        let dimmedView = UIView()
        dimmedView.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        dimmedView.translatesAutoresizingMaskIntoConstraints = false
        dimmedView.tag = 100
        view.addSubview(dimmedView)

        // Container
        containerView.backgroundColor = UIColor.systemBackground
        containerView.layer.cornerRadius = 20
        containerView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        containerView.layer.shadowColor = UIColor.black.cgColor
        containerView.layer.shadowOpacity = 0.15
        containerView.layer.shadowRadius = 10
        containerView.layer.shadowOffset = CGSize(width: 0, height: -3)
        containerView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(containerView)

        // Drag handle
        dragHandle.backgroundColor = UIColor.systemGray3
        dragHandle.layer.cornerRadius = 2.5
        dragHandle.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(dragHandle)

        // Icon
        iconLabel.text = "\u{1F4F3}" // NFC emoji
        iconLabel.font = .systemFont(ofSize: 48)
        iconLabel.textAlignment = .center
        iconLabel.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(iconLabel)

        // Title
        titleLabel.text = "Ready to Scan"
        titleLabel.font = .boldSystemFont(ofSize: 20)
        titleLabel.textAlignment = .center
        titleLabel.textColor = .label
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(titleLabel)

        // Description
        descriptionLabel.text = "Hold your document against the back of your phone and keep it still."
        descriptionLabel.font = .systemFont(ofSize: 15)
        descriptionLabel.textAlignment = .center
        descriptionLabel.textColor = .secondaryLabel
        descriptionLabel.numberOfLines = 0
        descriptionLabel.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(descriptionLabel)

        // Progress bar (hidden initially)
        progressBar.progressTintColor = UIColor.systemBlue
        progressBar.trackTintColor = UIColor.systemGray5
        progressBar.progress = 0
        progressBar.isHidden = true
        progressBar.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(progressBar)

        // Status
        statusLabel.text = "Waiting for document..."
        statusLabel.font = .systemFont(ofSize: 13)
        statusLabel.textAlignment = .center
        statusLabel.textColor = .tertiaryLabel
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(statusLabel)

        // Cancel button
        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.setTitleColor(.systemRed, for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        cancelButton.backgroundColor = UIColor.systemGray6
        cancelButton.layer.cornerRadius = 12
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        containerView.addSubview(cancelButton)

        // Start off-screen
        let bottomConstraint = containerView.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: 400)
        containerBottomConstraint = bottomConstraint

        NSLayoutConstraint.activate([
            // Dimmed view
            dimmedView.topAnchor.constraint(equalTo: view.topAnchor),
            dimmedView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            dimmedView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            dimmedView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            // Container
            containerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            containerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomConstraint,

            // Drag handle
            dragHandle.topAnchor.constraint(equalTo: containerView.topAnchor, constant: 10),
            dragHandle.centerXAnchor.constraint(equalTo: containerView.centerXAnchor),
            dragHandle.widthAnchor.constraint(equalToConstant: 40),
            dragHandle.heightAnchor.constraint(equalToConstant: 5),

            // Icon
            iconLabel.topAnchor.constraint(equalTo: dragHandle.bottomAnchor, constant: 20),
            iconLabel.centerXAnchor.constraint(equalTo: containerView.centerXAnchor),

            // Title
            titleLabel.topAnchor.constraint(equalTo: iconLabel.bottomAnchor, constant: 12),
            titleLabel.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 24),
            titleLabel.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -24),

            // Description
            descriptionLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 8),
            descriptionLabel.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 24),
            descriptionLabel.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -24),

            // Progress bar
            progressBar.topAnchor.constraint(equalTo: descriptionLabel.bottomAnchor, constant: 20),
            progressBar.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 24),
            progressBar.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -24),
            progressBar.heightAnchor.constraint(equalToConstant: 4),

            // Status
            statusLabel.topAnchor.constraint(equalTo: progressBar.bottomAnchor, constant: 10),
            statusLabel.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -24),

            // Cancel button
            cancelButton.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 20),
            cancelButton.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 24),
            cancelButton.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -24),
            cancelButton.heightAnchor.constraint(equalToConstant: 48),
            cancelButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
        ])
    }

    // MARK: - Animation

    private func animateIn() {
        view.layoutIfNeeded()
        containerBottomConstraint?.constant = 0
        UIView.animate(withDuration: 0.35, delay: 0, usingSpringWithDamping: 0.85, initialSpringVelocity: 0.5) {
            self.view.layoutIfNeeded()
        }
    }

    func animateOut(completion: (() -> Void)? = nil) {
        containerBottomConstraint?.constant = 400
        UIView.animate(withDuration: 0.25, animations: {
            self.view.layoutIfNeeded()
            self.view.backgroundColor = .clear
        }, completion: { _ in
            self.dismiss(animated: false, completion: completion)
        })
    }

    // MARK: - State Updates

    func updateState(title: String, description: String, icon: String, status: String, progress: Float?, showProgress: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.iconLabel.text = icon
            self.titleLabel.text = title
            self.descriptionLabel.text = description
            self.statusLabel.text = status
            self.progressBar.isHidden = !showProgress
            if let progress = progress {
                self.progressBar.setProgress(progress, animated: true)
            }
        }
    }

    func showWaiting() {
        updateState(
            title: "Ready to Scan",
            description: "Hold your document against the back of your phone and keep it still.",
            icon: "\u{1F4F3}",
            status: "Waiting for document...",
            progress: nil,
            showProgress: false
        )
    }

    func showConnecting() {
        updateState(
            title: "Connecting...",
            description: "Document detected. Establishing secure connection.",
            icon: "\u{1F4F3}",
            status: "Connecting to document chip...",
            progress: 0.05,
            showProgress: true
        )
    }

    func showAuthenticating() {
        updateState(
            title: "Reading Document",
            description: "Keep your document still against the back of your phone.",
            icon: "\u{1F4F3}",
            status: "Authenticating...",
            progress: 0.15,
            showProgress: true
        )
    }

    func showReadingDataGroup(dgNumber: Int, dgName: String, progress: Float) {
        updateState(
            title: "Reading Document",
            description: "Keep your document still against the back of your phone.",
            icon: "\u{1F4F3}",
            status: "Reading \(dgName)...",
            progress: progress,
            showProgress: true
        )
    }

    func showSuccess() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.iconLabel.text = "\u{2705}"
            self.titleLabel.text = "Success"
            self.descriptionLabel.text = "Document data read successfully."
            self.statusLabel.text = "Complete"
            self.progressBar.setProgress(1.0, animated: true)
            self.progressBar.progressTintColor = UIColor.systemGreen
            self.cancelButton.isHidden = true
        }
    }

    func showError(message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.iconLabel.text = "\u{274C}"
            self.titleLabel.text = "Error"
            self.descriptionLabel.text = message
            self.statusLabel.text = "Failed"
            self.progressBar.isHidden = true
            self.cancelButton.setTitle("Close", for: .normal)
        }
    }

    // MARK: - Actions

    @objc private func cancelTapped() {
        onCancel?()
    }

    @objc private func dimmedAreaTapped() {
        onCancel?()
    }
}

// MARK: - UIGestureRecognizerDelegate

extension NfcScanBottomSheet: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        // Only handle taps on the dimmed area, not the container
        let location = touch.location(in: view)
        return !containerView.frame.contains(location)
    }
}
