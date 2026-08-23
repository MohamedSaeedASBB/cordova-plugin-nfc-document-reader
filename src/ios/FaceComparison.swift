import UIKit
import MLKitFaceDetection
import MLKitVision

/// Pairs the chip portrait (DG2) with the liveness portrait and screens the pair before a
/// biometric 1:1 match is attempted.
///
/// SCOPE — READ THIS BEFORE CHANGING ANYTHING HERE:
/// ML Kit ships face *detection* and face mesh. It has no face recognition or embedding API, so
/// there is no ML Kit call that answers "are these two images the same person". Nothing in this
/// type attempts to answer that question, and `screening.passed` must never be presented to a
/// user or an approver as an identity match.
///
/// What this type does do, all of which ML Kit genuinely supports:
///   1. Confirms the chip portrait contains exactly one detectable, reasonably frontal face.
///   2. Confirms the same of the liveness portrait.
///   3. Emits per-image quality signals a matcher needs to interpret its own score.
///   4. Produces the aligned, compressed pair for whichever matcher does the real comparison.
///
/// The authoritative decision comes from `match`, populated by `FaceMatcher`.
/// FaceComparison.java mirrors this type.
final class FaceComparison {

    /// A face smaller than this fraction of the image is too small to match reliably.
    private static let minFaceAreaRatio: Float = 0.03
    /// Beyond these angles a portrait is too off-axis for a dependable 1:1 comparison.
    private static let maxAbsYaw: Float = 22
    private static let maxAbsPitch: Float = 22
    private static let maxAbsRoll: Float = 22

    /// Per-image detection outcome plus the quality signals a matcher needs.
    struct FaceAnalysis {
        var detected = false
        var faceCount = 0
        var areaRatio: Float = 0
        var yaw: Float = 0
        var pitch: Float = 0
        var roll: Float = 0
        var imageWidth = 0
        var imageHeight = 0
        var box: CGRect?

        // Qualified: a nested type does not see the enclosing type's statics unqualified.
        var isFrontal: Bool {
            return abs(yaw) <= FaceComparison.maxAbsYaw
                && abs(pitch) <= FaceComparison.maxAbsPitch
                && abs(roll) <= FaceComparison.maxAbsRoll
        }

        var isLargeEnough: Bool {
            return areaRatio >= FaceComparison.minFaceAreaRatio
        }

        var dictionary: [String: Any] {
            var json: [String: Any] = [
                "faceDetected": detected,
                "faceCount": faceCount,
                "imageWidth": imageWidth,
                "imageHeight": imageHeight
            ]
            if detected {
                json["faceAreaRatio"] = rounded(areaRatio)
                json["yaw"] = rounded(yaw)
                json["pitch"] = rounded(pitch)
                json["roll"] = rounded(roll)
                json["frontal"] = isFrontal
                json["largeEnough"] = isLargeEnough
            }
            return json
        }

        private func rounded(_ value: Float) -> Double {
            return (Double(value) * 1000).rounded() / 1000
        }
    }

    /// Detection results plus the decoded pair, so the matcher does not repeat the work.
    struct Outcome {
        var json: [String: Any] = [:]
        var documentFaceBox: CGRect?
        var livenessPortrait: UIImage?
        var livenessFaceBox: CGRect?
    }

    /// Runs detection over both portraits and assembles the comparison block.
    ///
    /// Must be called off the main thread: ML Kit's `results(in:)` raises if called on it.
    ///
    /// - Parameters:
    ///   - documentPortrait: the DG2 portrait decoded from the chip
    ///   - livenessFaceBase64: the compressed liveness crop from LivenessCameraViewController
    ///   - imageOptions: compression settings for the document crop that goes to the matcher
    static func compare(documentPortrait: UIImage?,
                        livenessFaceBase64: String?,
                        imageOptions: ImageCompressor.Options) -> Outcome {
        var outcome = Outcome()

        let detectorOptions = FaceDetectorOptions()
        // Stills, not a video stream: .accurate is the right trade here.
        detectorOptions.performanceMode = .accurate
        detectorOptions.classificationMode = .none
        detectorOptions.landmarkMode = .none
        detectorOptions.contourMode = .none
        detectorOptions.minFaceSize = 0.10
        let detector = FaceDetector.faceDetector(options: detectorOptions)

        let documentAnalysis = analyse(detector: detector, image: documentPortrait)

        let livenessPortrait = decodeBase64(livenessFaceBase64)
        let livenessAnalysis = analyse(detector: detector, image: livenessPortrait)

        outcome.documentFaceBox = documentAnalysis.box
        outcome.livenessPortrait = livenessPortrait
        outcome.livenessFaceBox = livenessAnalysis.box

        var json: [String: Any] = [
            "documentPortrait": documentAnalysis.dictionary,
            "livenessPortrait": livenessAnalysis.dictionary
        ]

        // ---- Screening: a pre-flight gate, NOT an identity decision ----
        var reasons: [String] = []
        if !documentAnalysis.detected {
            reasons.append("NO_FACE_IN_DOCUMENT_PORTRAIT")
        } else {
            if documentAnalysis.faceCount > 1 { reasons.append("MULTIPLE_FACES_IN_DOCUMENT_PORTRAIT") }
            if !documentAnalysis.isLargeEnough { reasons.append("DOCUMENT_FACE_TOO_SMALL") }
            if !documentAnalysis.isFrontal { reasons.append("DOCUMENT_FACE_NOT_FRONTAL") }
        }
        if !livenessAnalysis.detected {
            reasons.append("NO_FACE_IN_LIVENESS_PORTRAIT")
        } else {
            if livenessAnalysis.faceCount > 1 { reasons.append("MULTIPLE_FACES_IN_LIVENESS_PORTRAIT") }
            if !livenessAnalysis.isLargeEnough { reasons.append("LIVENESS_FACE_TOO_SMALL") }
            if !livenessAnalysis.isFrontal { reasons.append("LIVENESS_FACE_NOT_FRONTAL") }
        }

        json["screening"] = [
            "passed": reasons.isEmpty,
            "reasons": reasons,
            // Spelled out in the payload so no downstream consumer can mistake this for a match.
            "note": "Both images contain one usable frontal face. This is a quality gate only "
                + "and is NOT a biometric identity match."
        ]

        // ---- The document-side crop the matcher will consume ----
        if documentAnalysis.detected, let portrait = documentPortrait {
            // The chip portrait is not mirrored, unlike the liveness frames.
            var documentImageOptions = imageOptions
            documentImageOptions.mirrorHorizontally = false
            if let crop = ImageCompressor.compress(portrait,
                                                   faceBox: documentAnalysis.box,
                                                   options: documentImageOptions) {
                json["documentFaceImageBase64"] = crop.base64
                json["documentFaceImageBytes"] = crop.data.count
                json["documentFaceImageWidth"] = crop.width
                json["documentFaceImageHeight"] = crop.height
            }
        }

        outcome.json = json
        return outcome
    }

    // MARK: - Detection

    private static func analyse(detector: FaceDetector, image: UIImage?) -> FaceAnalysis {
        var analysis = FaceAnalysis()
        guard let image = image else { return analysis }

        analysis.imageWidth = Int(image.size.width)
        analysis.imageHeight = Int(image.size.height)

        let visionImage = VisionImage(image: image)
        visionImage.orientation = image.imageOrientation

        let faces: [Face]
        do {
            faces = try detector.results(in: visionImage)
        } catch {
            // Detection failure is reported as "no face", never as a pass.
            NSLog("[FaceComparison] Face detection on still image failed: %@",
                  error.localizedDescription)
            return analysis
        }

        analysis.faceCount = faces.count
        guard let largest = faces.max(by: {
            ($0.frame.width * $0.frame.height) < ($1.frame.width * $1.frame.height)
        }) else {
            return analysis
        }

        let box = largest.frame
        analysis.detected = true
        analysis.box = box
        analysis.areaRatio = Float((box.width * box.height)
            / (image.size.width * image.size.height))
        if largest.hasHeadEulerAngleY { analysis.yaw = Float(largest.headEulerAngleY) }
        if largest.hasHeadEulerAngleX { analysis.pitch = Float(largest.headEulerAngleX) }
        if largest.hasHeadEulerAngleZ { analysis.roll = Float(largest.headEulerAngleZ) }

        return analysis
    }

    private static func decodeBase64(_ base64: String?) -> UIImage? {
        guard let base64 = base64, !base64.isEmpty,
              let data = Data(base64Encoded: base64) else {
            return nil
        }
        return UIImage(data: data)
    }
}
