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
    /// Iterations of the quality search. Six halvings resolve the 0.30-0.92 range to about 0.01.
    private static let qualitySearchSteps = 6

    struct Options {
        /// Longest edge of the output image, in pixels.
        var maxDimension: Int = 720
        /// Hard budget for the encoded JPEG, in bytes.
        var maxBytes: Int = 200 * 1024
        var initialQuality: CGFloat = 0.85
        var minQuality: CGFloat = 0.45

        /// Search for the lowest JPEG quality that still meets `minPSNR`, instead of encoding at
        /// `initialQuality` and only reducing if the byte budget is exceeded.
        ///
        /// A fixed quality spends the same bits on a plain background as on a detailed face, so an
        /// easy image is stored far larger than it needs to be while a hard one may be pushed
        /// below what a face matcher can use. Measuring the result and stopping at the threshold
        /// makes the *quality* the constant and lets the size fall where it falls.
        var useQualitySearch: Bool = true

        /// Peak signal-to-noise ratio, in dB, against the uncompressed render. 38 dB is where JPEG
        /// artefacts stop being visible on a face at these dimensions; the usual "visually
        /// lossless" range quoted for photographs is 36-40.
        ///
        /// Raise it for more fidelity and larger files. Below about 34 the eye and mouth detail a
        /// face matcher relies on starts to go, so that is the floor worth defending.
        var minPSNR: Double = 38.0
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
        var data: Data

        if options.useQualitySearch,
           let found = searchQuality(rendered, options: options) {
            quality = found.quality
            data = found.data
        } else {
            guard let encoded = rendered.jpegData(compressionQuality: quality) else { return nil }
            data = encoded
        }

        // The byte budget is a hard cap and outranks the quality floor: a payload that will not
        // fit through the backend is worse than one that is slightly soft.
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

    // MARK: - Quality search

    /// Binary-searches for the lowest quality whose PSNR against the uncompressed render still
    /// clears `minPSNR`. Six encode/measure rounds on a 720px image cost a fraction of the time
    /// the face detector has already spent on the same frame.
    private static func searchQuality(_ rendered: UIImage,
                                      options: Options) -> (data: Data, quality: CGFloat)? {
        guard let reference = rendered.cgImage,
              let referenceGray = grayscale(reference) else { return nil }

        var low = options.minQuality
        var high = min(max(options.initialQuality, options.minQuality), 1.0)

        // If even the top of the range cannot meet the floor, there is nothing to search for:
        // take it and let the byte-budget loop below have the last word.
        guard let highData = rendered.jpegData(compressionQuality: high) else { return nil }
        guard measurePSNR(highData, against: referenceGray, size: reference) ?? 0 >= options.minPSNR
        else {
            return (highData, high)
        }

        var best = (data: highData, quality: high)
        for _ in 0..<qualitySearchSteps {
            let mid = (low + high) / 2
            guard let data = rendered.jpegData(compressionQuality: mid),
                  let psnr = measurePSNR(data, against: referenceGray, size: reference) else { break }
            if psnr >= options.minPSNR {
                best = (data, mid)      // good enough — try smaller
                high = mid
            } else {
                low = mid               // too lossy — back off
            }
        }
        return best
    }

    /// PSNR on luminance. Chroma subsampling makes the colour planes a poor guide to how a JPEG
    /// looks, and luminance is what carries the features a face matcher reads.
    private static func measurePSNR(_ jpeg: Data, against reference: [UInt8],
                                    size: CGImage) -> Double? {
        guard let decoded = UIImage(data: jpeg)?.cgImage,
              decoded.width == size.width, decoded.height == size.height,
              let candidate = grayscale(decoded),
              candidate.count == reference.count, !reference.isEmpty else { return nil }

        var squaredError = 0.0
        for i in 0..<reference.count {
            let d = Double(reference[i]) - Double(candidate[i])
            squaredError += d * d
        }
        let mse = squaredError / Double(reference.count)
        guard mse > 0 else { return Double.infinity }
        return 10 * log10(255.0 * 255.0 / mse)
    }

    private static func grayscale(_ image: CGImage) -> [UInt8]? {
        let width = image.width, height = image.height
        guard width > 0, height > 0 else { return nil }
        var pixels = [UInt8](repeating: 0, count: width * height)
        guard let context = CGContext(data: &pixels, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: width,
                                      space: CGColorSpaceCreateDeviceGray(),
                                      bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return pixels
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
