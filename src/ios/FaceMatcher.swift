import UIKit
import TensorFlowLite

/// On-device 1:1 face verification: chip portrait (DG2) against the liveness portrait.
///
/// WHY A MODEL FILE IS NEEDED
/// ML Kit provides face detection only — it has no recognition or embedding API, and iOS exposes
/// no public face-recognition API either. On-device verification therefore runs a face-embedding
/// model through TensorFlow Lite: each face is mapped to a vector, and the two vectors are
/// compared by cosine similarity against a decision threshold.
///
/// The model is NOT bundled with this plugin. The bank supplies it, because the choice of model
/// and threshold is a biometric-governance decision: the threshold fixes the false-accept and
/// false-reject rates of an identity check, and those rates have to be measured on a
/// representative population and signed off, not inherited from a plugin default.
///
/// The defaults here target a MobileFaceNet-family model: 112x112 input, 192-dimension embedding,
/// (x - 127.5) / 128 preprocessing. A different model family needs matching inputSize,
/// embeddingSize and — if it was trained with different normalisation — a change to the two
/// pixel constants below.
///
/// Wiring a model in:
///   1. Add the .tflite file to the app bundle as a resource.
///   2. Pass `faceMatch: { modelAsset, inputSize, embeddingSize, threshold }` to readNFC.
///   3. Derive the threshold before enabling it in production: run the pair through this matcher
///      over a labelled set of genuine and impostor pairs representative of the customer
///      population and the capture conditions (chip portraits are often low-resolution and years
///      old), sweep the cosine-similarity threshold, and pick the operating point that meets the
///      bank's false-accept target. Record the resulting FAR/FRR — those numbers, not the
///      threshold alone, are what a reviewer needs.
///
/// Deliberately no default threshold is shipped: a plausible-looking constant here would become
/// the bank's de facto identity-decision boundary without anyone having measured it.
///
/// Until a model is installed, the match is reported as deferred ("MODEL_NOT_INSTALLED") rather
/// than silently passing — an un-provisioned matcher is not a failed one. A model the caller asked
/// for by name but that is absent from the bundle is an error ("MODEL_NOT_FOUND").
/// FaceMatcher.java mirrors this type.
final class FaceMatcher {

    /// Standard preprocessing for MobileFaceNet/FaceNet-family models.
    private static let pixelMean: Float = 127.5
    private static let pixelScale: Float = 128.0

    /// Default asset name, auto-installed by plugin.xml. See src/models/README.md.
    static let defaultModelAsset = "mobilefacenet.tflite"

    struct Config {
        /// Bundle resource name of the .tflite model. nil disables on-device matching.
        var modelAsset: String? = FaceMatcher.defaultModelAsset
        /// True when the caller supplied `faceMatch.modelAsset` itself, false when `modelAsset`
        /// is still the plugin default. A missing file means different things in the two cases —
        /// see `match(documentPortrait:documentFaceBox:livenessPortrait:livenessFaceBox:)`.
        var modelAssetExplicit = false
        /// Square input edge the model expects (112 for MobileFaceNet, 160 for FaceNet).
        var inputSize: Int = 112
        /// Embedding length the model emits (192 for MobileFaceNet, 128/512 for FaceNet).
        var embeddingSize: Int = 192
        /// Cosine-similarity threshold at or above which the pair is reported as a match.
        /// Deliberately has no safe default — it must come from the bank's own validation.
        var threshold: Double?
        /// Crop padding applied around the detected face before embedding.
        var cropPadding: CGFloat = 0.25

        static func from(_ dict: [String: Any]?) -> Config {
            var config = Config()
            guard let dict = dict else { return config }
            if dict.index(forKey: "modelAsset") != nil {
                // Mirrors the Android parser: an explicit null clears the model and disables
                // matching, which a plain `as? String` cast would have ignored.
                config.modelAssetExplicit = true
                config.modelAsset = dict["modelAsset"] as? String
            }
            if let value = dict["inputSize"] as? Int { config.inputSize = value }
            if let value = dict["embeddingSize"] as? Int { config.embeddingSize = value }
            if let value = dict["threshold"] as? Double { config.threshold = value }
            return config
        }
    }

    struct MatchResult {
        /// "matched", "notMatched", "review", "deferred" or "error".
        var status: String
        /// Cosine similarity in [-1, 1], or nil when no comparison ran.
        var similarity: Double?
        var threshold: Double?
        var reason: String?

        var dictionary: [String: Any] {
            return [
                "status": status,
                "similarity": similarity ?? NSNull(),
                "threshold": threshold ?? NSNull(),
                "reason": reason ?? NSNull(),
                "onDevice": true
            ]
        }
    }

    private let config: Config

    init(config: Config) {
        self.config = config
    }

    /// A model is enough to run the comparison. The threshold is separate: without it we still
    /// compute and report the similarity, but refuse to turn it into a pass/fail.
    var isConfigured: Bool {
        guard let asset = config.modelAsset, !asset.isEmpty else { return false }
        return true
    }

    /// Compares two already-detected faces. Call off the main thread.
    func match(documentPortrait: UIImage?, documentFaceBox: CGRect?,
               livenessPortrait: UIImage?, livenessFaceBox: CGRect?) -> MatchResult {
        guard isConfigured else {
            return MatchResult(status: "deferred", similarity: nil,
                               threshold: config.threshold, reason: "NO_MODEL_CONFIGURED")
        }
        guard let documentPortrait = documentPortrait, let livenessPortrait = livenessPortrait else {
            return MatchResult(status: "error", similarity: nil, threshold: config.threshold,
                               reason: "MISSING_PORTRAIT")
        }
        guard let modelPath = resolveModelPath() else {
            // Two different situations, and reporting both as "error" sends people debugging
            // inference that never ran:
            //
            //   default asset absent  - the bank has not installed a model yet. Nothing is
            //                           broken; on-device matching simply is not provisioned,
            //                           which is exactly what "deferred" means. Installing the
            //                           model is a governance step (src/models/README.md), so a
            //                           build without it is an expected state, not a fault.
            //   explicit asset absent - the app asked for a specific model and it is not in the
            //                           bundle. That is a real misconfiguration.
            //
            // Neither is ever reported as a pass.
            guard config.modelAssetExplicit else {
                NSLog("[FaceMatcher] No face match model installed at the default resource name"
                      + " (%@); reporting the match as deferred. See src/models/README.md.",
                      config.modelAsset ?? "nil")
                return MatchResult(status: "deferred", similarity: nil,
                                   threshold: config.threshold, reason: "MODEL_NOT_INSTALLED")
            }
            NSLog("[FaceMatcher] Configured face match model not found in the bundle: %@",
                  config.modelAsset ?? "nil")
            return MatchResult(status: "error", similarity: nil, threshold: config.threshold,
                               reason: "MODEL_NOT_FOUND")
        }

        do {
            let interpreter = try Interpreter(modelPath: modelPath)
            try interpreter.allocateTensors()

            let documentEmbedding = try embed(interpreter: interpreter,
                                              portrait: documentPortrait,
                                              faceBox: documentFaceBox)
            let livenessEmbedding = try embed(interpreter: interpreter,
                                              portrait: livenessPortrait,
                                              faceBox: livenessFaceBox)

            let similarity = try cosineSimilarity(documentEmbedding, livenessEmbedding)
            let rounded = (similarity * 10000).rounded() / 10000

            // Score only — never log the embeddings or the images themselves.
            NSLog("[FaceMatcher] On-device face match: similarity=%.4f", rounded)

            guard let threshold = config.threshold else {
                // The comparison ran on-device and the score is real, but turning a score into a
                // pass/fail needs a threshold measured on this bank's population. Reporting
                // "review" keeps the decision with a human instead of inventing a boundary.
                return MatchResult(status: "review", similarity: rounded,
                                   threshold: nil, reason: "NO_THRESHOLD_CONFIGURED")
            }

            return MatchResult(status: similarity >= threshold ? "matched" : "notMatched",
                               similarity: rounded,
                               threshold: threshold,
                               reason: nil)
        } catch MatcherError.embeddingLengthMismatch(let lhs, let rhs) {
            // Almost always a config error rather than a broken model: the .tflite emits a
            // different vector length than embeddingSize claims.
            NSLog("[FaceMatcher] Model output does not match configuration: %d vs %d", lhs, rhs)
            return MatchResult(status: "error", similarity: nil, threshold: config.threshold,
                               reason: "EMBEDDING_LENGTH_MISMATCH")
        } catch {
            // A matcher failure is never reported as a pass.
            NSLog("[FaceMatcher] On-device face match failed: %@", String(describing: error))
            return MatchResult(status: "error", similarity: nil, threshold: config.threshold,
                              reason: "MATCHER_FAILED")
        }
    }

    // MARK: - Inference

    private func embed(interpreter: Interpreter,
                       portrait: UIImage,
                       faceBox: CGRect?) throws -> [Float] {
        guard let face = cropAndResize(portrait, faceBox: faceBox, inputSize: config.inputSize),
              let input = normalisedData(from: face, inputSize: config.inputSize) else {
            throw MatcherError.preprocessingFailed
        }

        try interpreter.copy(input, toInputAt: 0)
        try interpreter.invoke()

        let output = try interpreter.output(at: 0)
        let embedding = output.data.withUnsafeBytes { raw -> [Float] in
            return Array(raw.bindMemory(to: Float.self))
        }
        return l2Normalise(embedding)
    }

    private func cropAndResize(_ source: UIImage, faceBox: CGRect?, inputSize: Int) -> UIImage? {
        guard let cgImage = source.cgImage else { return nil }

        var working = cgImage
        if let faceBox = faceBox {
            let padX = faceBox.width * config.cropPadding
            let padY = faceBox.height * config.cropPadding
            let bounds = CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height)
            let expanded = faceBox.insetBy(dx: -padX, dy: -padY).intersection(bounds).integral
            if !expanded.isNull, expanded.width >= 1, expanded.height >= 1,
               let cropped = cgImage.cropping(to: expanded) {
                working = cropped
            }
        }

        let size = CGSize(width: inputSize, height: inputSize)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            UIImage(cgImage: working).draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// NHWC float32 RGB, normalised the same way as the Android path.
    private func normalisedData(from image: UIImage, inputSize: Int) -> Data? {
        guard let cgImage = image.cgImage else { return nil }

        let pixelCount = inputSize * inputSize
        var rgba = [UInt8](repeating: 0, count: pixelCount * 4)

        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: &rgba,
                                      width: inputSize,
                                      height: inputSize,
                                      bitsPerComponent: 8,
                                      bytesPerRow: inputSize * 4,
                                      space: colorSpace,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return nil
        }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: inputSize, height: inputSize))

        var floats = [Float](repeating: 0, count: pixelCount * 3)
        for index in 0..<pixelCount {
            floats[index * 3] = (Float(rgba[index * 4]) - Self.pixelMean) / Self.pixelScale
            floats[index * 3 + 1] = (Float(rgba[index * 4 + 1]) - Self.pixelMean) / Self.pixelScale
            floats[index * 3 + 2] = (Float(rgba[index * 4 + 2]) - Self.pixelMean) / Self.pixelScale
        }

        return floats.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    private func resolveModelPath() -> String? {
        guard let asset = config.modelAsset else { return nil }

        // Accept "model.tflite", "assets/model.tflite" or a full path, so the same option value
        // works whether the app ships the model as a bundle resource or under www/.
        if FileManager.default.fileExists(atPath: asset) {
            return asset
        }
        let name = (asset as NSString).deletingPathExtension
        let ext = (asset as NSString).pathExtension.isEmpty
            ? "tflite"
            : (asset as NSString).pathExtension
        if let path = Bundle.main.path(forResource: name, ofType: ext) {
            return path
        }
        // Cordova stages web assets under www/, which is where an app-supplied model often lands.
        return Bundle.main.path(forResource: (asset as NSString).lastPathComponent,
                                ofType: nil,
                                inDirectory: "www")
    }

    // MARK: - Vector maths

    private func l2Normalise(_ vector: [Float]) -> [Float] {
        let norm = sqrt(vector.reduce(0) { $0 + Double($1) * Double($1) })
        guard norm > 0 else { return vector }
        return vector.map { Float(Double($0) / norm) }
    }

    /// Both vectors are L2-normalised, so this is a plain dot product.
    private func cosineSimilarity(_ lhs: [Float], _ rhs: [Float]) throws -> Double {
        guard lhs.count == rhs.count, !lhs.isEmpty else {
            throw MatcherError.embeddingLengthMismatch(lhs.count, rhs.count)
        }
        var dot = 0.0
        for index in 0..<lhs.count {
            dot += Double(lhs[index]) * Double(rhs[index])
        }
        return dot
    }

    private enum MatcherError: Error {
        case preprocessingFailed
        case embeddingLengthMismatch(Int, Int)
    }
}
