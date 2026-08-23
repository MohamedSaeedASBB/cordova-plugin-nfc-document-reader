import UIKit

/// Parsed `checkLiveness` options, with defaults applied.
///
/// iOS mirror of LivenessOptions.java. Prompt strings are overridable so the host app can
/// supply its own copy (including Arabic) without the plugin shipping a localisation bundle.
/// Unknown keys are ignored rather than rejected, so the JS side can add options without
/// breaking older native builds.
struct LivenessOptions {

    var challenges: [LivenessDetector.Challenge] = LivenessDetector.randomChallenges(count: 2)
    var overallTimeoutMs: Double = 45_000
    var perChallengeTimeoutMs: Double = 15_000
    var faceSearchTimeoutMs: Double = 20_000

    var maxImageDimension: Int = 720
    var maxImageBytes: Int = 200 * 1024
    /// 1-100, matching the Android option; converted to 0-1 for `jpegData(compressionQuality:)`.
    var jpegQuality: Int = 85
    var cropToFace: Bool = true

    var includeFullFrame: Bool = false
    var includeChallengeFrames: Bool = false

    var prompts: [String: String] = LivenessOptions.defaultPrompts

    static func from(_ dict: [String: Any]) -> LivenessOptions {
        var options = LivenessOptions()

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
            options.challenges = LivenessDetector.randomChallenges(count: count)
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

        return options
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
        return config
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
        "hold": "Hold still and look at the camera",
        "success": "Done",
        "failed": "Liveness check failed",
        "hint": "Hold your phone at eye level in even lighting"
    ]
}
