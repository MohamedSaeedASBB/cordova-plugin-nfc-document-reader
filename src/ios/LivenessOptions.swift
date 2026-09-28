import UIKit

/// Parsed `checkLiveness` options, with defaults applied.
///
/// iOS mirror of LivenessOptions.java. Prompt strings are overridable so the host app can
/// supply its own copy (including Arabic) without the plugin shipping a localisation bundle.
/// Unknown keys are ignored rather than rejected, so the JS side can add options without
/// breaking older native builds.
struct LivenessOptions {

    /// How many challenges a session asks for when the caller does not say.
    ///
    /// Four, which with the compound challenges off is every single-action challenge there is —
    /// blink, smile, turn left, turn right — in a random order. Four actions is materially harder
    /// to pre-record than two while still being something a customer can do without being coached.
    static let defaultChallengeCount = 4

    var challenges: [LivenessDetector.Challenge] =
        LivenessDetector.randomChallenges(count: LivenessOptions.defaultChallengeCount)
    /// Zero means "work it out from the challenge list" — see `resolveOverallTimeout`. A caller's
    /// explicit value always wins.
    var overallTimeoutMs: Double = 0
    var perChallengeTimeoutMs: Double = 15_000
    var faceSearchTimeoutMs: Double = 20_000

    var maxImageDimension: Int = 720
    var maxImageBytes: Int = 200 * 1024
    /// 1-100, matching the Android option; converted to 0-1 for `jpegData(compressionQuality:)`.
    var jpegQuality: Int = 85
    var cropToFace: Bool = true

    var includeFullFrame: Bool = false
    var includeChallengeFrames: Bool = false

    /// Widen the challenge pool to include the compound ones (a head turn and a smile or a blink
    /// at the same moment).
    ///
    /// Off by default. The compound challenges ask the customer to hold two things at once, their
    /// thresholds are not calibrated against real faces, and a challenge a genuine customer cannot
    /// pass is a failed onboarding rather than a caught fraud. They remain available to a caller
    /// who opts in, and to a future pilot that calibrates them.
    var includeCompoundChallenges: Bool = false

    /// How long a pose must be held. See LivenessDetector.defaultPoseHoldMs.
    var poseHoldMs: Double = LivenessDetector.defaultPoseHoldMs

    /// Record the session. On by default, at the bank's request.
    var recordVideo: Bool = true
    var videoMaxDimension: Int = 854
    var videoBitrate: Int = 900_000
    var videoFrameRate: Int = 15
    var videoPreferHEVC: Bool = true
    /// Record only the challenge windows. Most of a session is the customer reading a prompt;
    /// that footage is the bulk of the file and carries none of the evidence.
    var videoTrimToChallenges: Bool = true

    var prompts: [String: String] = LivenessOptions.defaultPrompts

    static func from(_ dict: [String: Any]) -> LivenessOptions {
        var options = LivenessOptions()

        // Read before the challenge block below, because it decides what the random pool holds.
        if let value = dict["includeCompoundChallenges"] as? Bool {
            options.includeCompoundChallenges = value
        }
        if let value = dict["poseHoldMs"] as? Double { options.poseHoldMs = value }

        // ---- Video ----
        if let value = dict["recordVideo"] as? Bool { options.recordVideo = value }
        if let value = dict["videoMaxDimension"] as? Int { options.videoMaxDimension = value }
        if let value = dict["videoBitrate"] as? Int { options.videoBitrate = value }
        if let value = dict["videoFrameRate"] as? Int { options.videoFrameRate = value }
        if let value = dict["videoPreferHEVC"] as? Bool { options.videoPreferHEVC = value }
        if let value = dict["videoTrimToChallenges"] as? Bool {
            options.videoTrimToChallenges = value
        }

        // ---- Challenges ----
        // An explicit list is honoured as given; otherwise a random subset is used, which is
        // what stops an attacker pre-recording the expected actions in the expected order.
        if let names = dict["challenges"] as? [String] {
            var parsed: [LivenessDetector.Challenge] = []
            for name in names {
                guard let challenge = LivenessDetector.Challenge(rawValue: name) else {
                    NSLog("[Liveness] Unknown challenge ignored: %@", name)
                    continue
                }
                if !parsed.contains(challenge) {
                    parsed.append(challenge)
                }
            }
            if !parsed.isEmpty {
                options.challenges = parsed
            }
        } else if let count = dict["challengeCount"] as? Int {
            options.challenges = LivenessDetector.randomChallenges(
                count: count, includeCompound: options.includeCompoundChallenges)
        } else {
            // Default: four, drawn at random. With the compound challenges off that is every
            // single-action challenge there is, and the order still varies — so a recording of one
            // session does not predict the next.
            options.challenges = LivenessDetector.randomChallenges(
                count: defaultChallengeCount, includeCompound: options.includeCompoundChallenges)
        }

        // ---- Timeouts ----
        if let value = dict["overallTimeoutMs"] as? Double { options.overallTimeoutMs = value }
        if let value = dict["perChallengeTimeoutMs"] as? Double { options.perChallengeTimeoutMs = value }
        if let value = dict["faceSearchTimeoutMs"] as? Double { options.faceSearchTimeoutMs = value }

        // ---- Image output ----
        if let value = dict["maxImageDimension"] as? Int { options.maxImageDimension = value }
        if let value = dict["maxImageBytes"] as? Int { options.maxImageBytes = value }
        if let value = dict["jpegQuality"] as? Int { options.jpegQuality = value }
        if let value = dict["cropToFace"] as? Bool { options.cropToFace = value }
        if let value = dict["includeFullFrame"] as? Bool { options.includeFullFrame = value }
        if let value = dict["includeChallengeFrames"] as? Bool { options.includeChallengeFrames = value }

        // ---- Prompt overrides ----
        if let overrides = dict["prompts"] as? [String: String] {
            for (key, value) in overrides where options.prompts[key] != nil {
                options.prompts[key] = value
            }
        }

        options.overallTimeoutMs = options.resolveOverallTimeout()
        return options
    }

    /// The session ceiling, derived from the work the session actually has to do.
    ///
    /// A fixed 45 seconds was fine for two challenges and is wrong for eight: the per-challenge
    /// timeouts alone can exceed it, so the overall timer would fire while the customer was still
    /// being asked for challenge five and report CHALLENGE_TIMEOUT for something they had not been
    /// given a chance to do. This is a ceiling, not an expected duration.
    func resolveOverallTimeout() -> Double {
        if overallTimeoutMs > 0 { return overallTimeoutMs }   // an explicit value always wins
        let count = Double(max(challenges.count, 1))
        return faceSearchTimeoutMs + count * perChallengeTimeoutMs + 5_000
    }

    func prompt(_ key: String) -> String {
        return prompts[key] ?? ""
    }

    func detectorConfig() -> LivenessDetector.Config {
        var config = LivenessDetector.Config()
        config.challenges = challenges
        config.overallTimeoutMs = overallTimeoutMs
        config.perChallengeTimeoutMs = perChallengeTimeoutMs
        config.faceSearchTimeoutMs = faceSearchTimeoutMs
        config.poseHoldMs = poseHoldMs
        return config
    }

    func videoOptions() -> LivenessVideoRecorder.Options {
        var video = LivenessVideoRecorder.Options()
        video.maxDimension = videoMaxDimension
        video.bitrate = videoBitrate
        video.frameRate = videoFrameRate
        video.preferHEVC = videoPreferHEVC
        video.trimToChallenges = videoTrimToChallenges
        return video
    }

    func imageOptions() -> ImageCompressor.Options {
        var imageOptions = ImageCompressor.Options()
        imageOptions.maxDimension = maxImageDimension
        imageOptions.maxBytes = maxImageBytes
        imageOptions.initialQuality = CGFloat(jpegQuality) / 100.0
        imageOptions.cropToFace = cropToFace
        return imageOptions
    }

    static let defaultPrompts: [String: String] = [
        "findFace": "Position your face inside the oval",
        "center": "Centre your face in the oval",
        "tooFar": "Move a little closer",
        "tooClose": "Move a little further away",
        "multipleFaces": "Only one person should be in frame",
        "blink": "Blink slowly",
        "smile": "Smile",
        "turnLeft": "Slowly turn your head to your left",
        "turnRight": "Slowly turn your head to your right",
        "turnLeftSmile": "Turn your head to your left and smile",
        "turnRightSmile": "Turn your head to your right and smile",
        "turnLeftBlink": "Turn your head to your left, then blink",
        "turnRightBlink": "Turn your head to your right, then blink",
        "hold": "Hold still and look at the camera",
        "success": "Done",
        "failed": "Liveness check failed",
        "hint": "Hold your phone at eye level in even lighting"
    ]
}
