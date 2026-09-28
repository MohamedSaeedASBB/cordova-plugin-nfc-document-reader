import Foundation
import AVFoundation
import CoreMedia

/// Records the liveness session to a file, from the same sample buffers the face detector is
/// already receiving.
///
/// WHY AN ASSET WRITER RATHER THAN A MOVIE FILE OUTPUT
/// `AVCaptureMovieFileOutput` would be less code, but it cannot coexist reliably with the
/// `AVCaptureVideoDataOutput` the liveness check needs on every supported iOS version, it offers no
/// control over bitrate, and it cannot skip the dead time between challenges. Writing the buffers
/// we already have costs one class and gives all three.
///
/// TRIMMING
/// Most of a liveness session is the customer reading a prompt and deciding to move. That footage
/// is the bulk of the file and carries none of the evidence. `pause()` and `resume()` bracket the
/// challenge windows, and the frames are retimed so the result plays as one continuous clip rather
/// than a video with frozen gaps — an asset writer keeps whatever presentation timestamps it is
/// given, so the gap has to be subtracted rather than simply not written.
final class LivenessVideoRecorder {

    struct Options {
        /// Longest edge of the recorded video. 480p keeps eyes and mouth legible, which is what
        /// the footage is for, at a quarter the pixels of 720p.
        var maxDimension: Int = 854
        /// Target bitrate. HEVC at this rate is visually clean for a talking head at 480p.
        var bitrate: Int = 900_000
        var frameRate: Int = 15
        /// HEVC roughly halves the size at equal quality. Falls back to H.264 where the device
        /// cannot encode it, which is every device before the A10.
        var preferHEVC: Bool = true
        /// Record only the challenge windows, not the whole session.
        var trimToChallenges: Bool = true
    }

    struct Output {
        let url: URL
        let bytes: Int
        let durationMs: Int
        let width: Int
        let height: Int
        let codec: String
        let trimmed: Bool
    }

    private let options: Options
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var codecUsed = "hevc"

    private var started = false
    private var recording = false
    private var failed = false

    /// Timeline bookkeeping for the retiming described above, all in the source clock.
    private var firstPTS: CMTime?
    private var lastPTS: CMTime = .zero
    private var pausedAt: CMTime?
    private var skipped: CMTime = .zero
    /// Frames are dropped down to `frameRate`; this is the last timestamp actually written.
    private var lastWrittenOutputPTS: CMTime?

    private var outputWidth = 0
    private var outputHeight = 0

    init(options: Options) {
        self.options = options
        // Trimming off means record everything, so start in the recording state.
        self.recording = !options.trimToChallenges
    }

    // MARK: - Session control

    /// Begins a challenge window. No-op when not trimming, because recording never stopped.
    func resume() {
        guard options.trimToChallenges, !recording else { return }
        recording = true
        // The gap is closed on the next frame, where a current timestamp is available.
    }

    /// Ends a challenge window.
    func pause() {
        guard options.trimToChallenges, recording else { return }
        recording = false
        pausedAt = lastPTS
    }

    // MARK: - Frames

    func append(_ sampleBuffer: CMSampleBuffer) {
        guard !failed else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        lastPTS = pts

        guard recording else { return }

        if let pausedAt = pausedAt {
            // Closing a gap: everything between the pause and now is removed from the timeline.
            skipped = CMTimeAdd(skipped, CMTimeSubtract(pts, pausedAt))
            self.pausedAt = nil
        }

        if !started {
            guard start(with: sampleBuffer, at: pts) else { return }
        }

        let outputPTS = CMTimeSubtract(CMTimeSubtract(pts, firstPTS ?? pts), skipped)

        // Decimate to the target frame rate. The detector wants every frame; the recording does
        // not, and dropping them here is the cheapest compression available.
        if let last = lastWrittenOutputPTS {
            let minimumGap = CMTime(value: 1, timescale: CMTimeScale(max(1, options.frameRate)))
            if CMTimeSubtract(outputPTS, last) < minimumGap { return }
        }

        guard let input = input, input.isReadyForMoreMediaData,
              let retimed = retime(sampleBuffer, to: outputPTS) else { return }

        if !input.append(retimed) {
            NSLog("[LivenessVideo] Writer rejected a frame: %@",
                  writer?.error?.localizedDescription ?? "unknown")
            failed = true
            return
        }
        lastWrittenOutputPTS = outputPTS
    }

    // MARK: - Finish

    /// Always calls back, with nil when nothing usable was recorded. A missing video must never
    /// fail a liveness check that otherwise passed.
    func finish(completion: @escaping (Output?) -> Void) {
        guard started, !failed, let writer = writer, let input = input else {
            completion(nil)
            return
        }
        input.markAsFinished()
        let duration = lastWrittenOutputPTS ?? .zero
        let width = outputWidth, height = outputHeight, codec = codecUsed
        let trimmed = options.trimToChallenges

        writer.finishWriting {
            guard writer.status == .completed else {
                NSLog("[LivenessVideo] Writing failed: %@",
                      writer.error?.localizedDescription ?? "unknown")
                completion(nil)
                return
            }
            let url = writer.outputURL
            let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
            let bytes = (attributes?[.size] as? NSNumber)?.intValue ?? 0
            completion(Output(url: url,
                              bytes: bytes,
                              durationMs: Int(CMTimeGetSeconds(duration) * 1000),
                              width: width, height: height,
                              codec: codec, trimmed: trimmed))
        }
    }

    /// Removes the file. The payload carries the bytes, so the copy on disk is temporary — and it
    /// is video of a customer's face, which should not outlive the call that produced it.
    static func discard(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - Setup

    private func start(with sampleBuffer: CMSampleBuffer, at pts: CMTime) -> Bool {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else {
            failed = true
            return false
        }
        let dimensions = CMVideoFormatDescriptionGetDimensions(formatDescription)
        // The buffer is landscape; the recording is portrait, so the transform below rotates it
        // and the output dimensions are the source's swapped.
        let sourceWidth = Int(dimensions.height)
        let sourceHeight = Int(dimensions.width)
        let scale = min(1.0, Double(options.maxDimension) / Double(max(sourceWidth, sourceHeight)))
        // H.264 and HEVC both want even dimensions.
        outputWidth = max(2, Int((Double(sourceWidth) * scale / 2).rounded()) * 2)
        outputHeight = max(2, Int((Double(sourceHeight) * scale / 2).rounded()) * 2)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("liveness-\(UUID().uuidString).mp4")

        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else {
            failed = true
            return false
        }

        let codec: AVVideoCodecType
        if options.preferHEVC, isHEVCEncodingAvailable() {
            codec = .hevc
            codecUsed = "hevc"
        } else {
            codec = .h264
            codecUsed = "h264"
        }

        let settings: [String: Any] = [
            AVVideoCodecKey: codec,
            AVVideoWidthKey: outputWidth,
            AVVideoHeightKey: outputHeight,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: options.bitrate,
                AVVideoExpectedSourceFrameRateKey: options.frameRate,
                AVVideoMaxKeyFrameIntervalKey: options.frameRate * 2
            ]
        ]
        guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
            failed = true
            return false
        }

        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = true
        // Front camera in portrait: rotate a quarter turn, then mirror, so the recording matches
        // what the customer saw rather than the raw sensor orientation.
        input.transform = CGAffineTransform(rotationAngle: .pi / 2).scaledBy(x: 1, y: -1)

        guard writer.canAdd(input) else {
            failed = true
            return false
        }
        writer.add(input)
        guard writer.startWriting() else {
            NSLog("[LivenessVideo] startWriting failed: %@",
                  writer.error?.localizedDescription ?? "unknown")
            failed = true
            return false
        }
        writer.startSession(atSourceTime: .zero)

        self.writer = writer
        self.input = input
        self.firstPTS = pts
        self.started = true
        return true
    }

    private func isHEVCEncodingAvailable() -> Bool {
        // A cheap probe: ask whether an asset writer would accept HEVC settings at all.
        let probe: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.hevc,
            AVVideoWidthKey: 320,
            AVVideoHeightKey: 240
        ]
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("hevc-probe-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        return writer.canApply(outputSettings: probe, forMediaType: .video)
    }

    private func retime(_ sampleBuffer: CMSampleBuffer, to pts: CMTime) -> CMSampleBuffer? {
        var timing = CMSampleTimingInfo(
            duration: CMSampleBufferGetDuration(sampleBuffer),
            presentationTimeStamp: pts,
            decodeTimeStamp: .invalid)
        var copy: CMSampleBuffer?
        let status = CMSampleBufferCreateCopyWithNewTiming(
            allocator: kCFAllocatorDefault,
            sampleBuffer: sampleBuffer,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleBufferOut: &copy)
        return status == noErr ? copy : nil
    }
}
