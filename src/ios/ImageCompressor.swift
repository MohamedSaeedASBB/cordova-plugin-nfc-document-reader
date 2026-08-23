import UIKit

/// Downscales and JPEG-compresses captured selfie frames to a byte budget before they leave
/// the native layer.
///
/// Liveness frames are raw camera frames; handing one to the WebView as base64 and then on to
/// the back office is wasteful and slow. We crop to the face, cap the long edge, then step JPEG
/// quality down until the payload fits the budget.
///
/// ImageCompressor.java mirrors this behaviour — keep the two in sync.
final class ImageCompressor {

    /// Quality step used when walking down towards the byte budget.
    private static let qualityStep: CGFloat = 0.08

    struct Options {
        /// Longest edge of the output image, in pixels.
        var maxDimension: Int = 720
        /// Hard budget for the encoded JPEG, in bytes.
        var maxBytes: Int = 200 * 1024
        var initialQuality: CGFloat = 0.85
        var minQuality: CGFloat = 0.45
        /// Crop to the face box, expanded by `faceCropPadding` of the box on each side.
        var cropToFace: Bool = true
        var faceCropPadding: CGFloat = 0.55
        /// Flip the output horizontally.
        ///
        /// Detection runs on a mirrored image (see LivenessDetector.swift), so face boxes align
        /// with a mirrored frame — but the portrait we hand to the back office should be in true
        /// orientation to match the document portrait. We therefore crop in mirrored space and
        /// flip once at the end. Android needs no flip: its analysis frames are never mirrored.
        var mirrorHorizontally: Bool = true
    }

    struct Result {
        let data: Data
        let width: Int
        let height: Int
        let quality: CGFloat

        /// Base64 without line wrapping, matching the faceImageBase64 field from the NFC chip.
        var base64: String {
            return data.base64EncodedString()
        }
    }

    /// - Parameters:
    ///   - image: the captured frame, already upright in the same space ML Kit reported boxes in
    ///   - faceBox: face bounds in `image` pixel coordinates, or nil for no crop
    static func compress(_ image: UIImage, faceBox: CGRect?, options: Options) -> Result? {
        guard let source = image.cgImage else {
            NSLog("[ImageCompressor] Frame has no backing CGImage — skipping")
            return nil
        }

        var working = source
        if options.cropToFace, let faceBox = faceBox,
           let cropped = crop(source, to: faceBox, padding: options.faceCropPadding) {
            working = cropped
        }

        let targetSize = scaledSize(width: working.width,
                                    height: working.height,
                                    maxDimension: options.maxDimension)

        guard let rendered = render(working,
                                    to: targetSize,
                                    mirrored: options.mirrorHorizontally) else {
            return nil
        }

        var quality = min(max(options.initialQuality, options.minQuality), 1.0)
        guard var data = rendered.jpegData(compressionQuality: quality) else {
            return nil
        }

        while data.count > options.maxBytes && quality > options.minQuality {
            quality = max(options.minQuality, quality - qualityStep)
            guard let next = rendered.jpegData(compressionQuality: quality) else { break }
            data = next
        }

        // Size only — never log image bytes or base64: this is biometric PII.
        NSLog("[ImageCompressor] Compressed selfie: %dx%d q=%.2f bytes=%d%@",
              Int(targetSize.width), Int(targetSize.height), Double(quality), data.count,
              data.count > options.maxBytes ? " (over budget at min quality)" : "")

        return Result(data: data,
                      width: Int(targetSize.width),
                      height: Int(targetSize.height),
                      quality: quality)
    }

    // MARK: - Steps

    /// Expands the ML Kit face box outwards so the crop keeps hair, chin and some background —
    /// face matchers do better with that context than with a box cropped tight to the features.
    private static func crop(_ source: CGImage, to faceBox: CGRect, padding: CGFloat) -> CGImage? {
        let padX = faceBox.width * padding
        let padY = faceBox.height * padding

        let expanded = faceBox.insetBy(dx: -padX, dy: -padY)
        let bounds = CGRect(x: 0, y: 0, width: source.width, height: source.height)
        let clamped = expanded.intersection(bounds).integral

        guard !clamped.isNull, clamped.width >= 1, clamped.height >= 1 else {
            NSLog("[ImageCompressor] Face box outside frame bounds — skipping crop")
            return nil
        }
        return source.cropping(to: clamped)
    }

    private static func scaledSize(width: Int, height: Int, maxDimension: Int) -> CGSize {
        let longEdge = max(width, height)
        guard maxDimension > 0, longEdge > maxDimension else {
            return CGSize(width: width, height: height)
        }
        let scale = CGFloat(maxDimension) / CGFloat(longEdge)
        return CGSize(width: max(1, (CGFloat(width) * scale).rounded()),
                      height: max(1, (CGFloat(height) * scale).rounded()))
    }

    private static func render(_ source: CGImage, to size: CGSize, mirrored: Bool) -> UIImage? {
        let format = UIGraphicsImageRendererFormat()
        // Pixel-for-pixel output: the caller asked for a specific pixel budget, not points.
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { context in
            if mirrored {
                context.cgContext.translateBy(x: size.width, y: 0)
                context.cgContext.scaleBy(x: -1, y: 1)
            }
            UIImage(cgImage: source).draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
