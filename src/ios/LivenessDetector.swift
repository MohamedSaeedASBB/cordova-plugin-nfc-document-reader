import Foundation

/// Challenge-response liveness state machine.
///
/// This is the iOS mirror of LivenessDetector.java — same states, same thresholds, same
/// sequencing. Keep the two in sync when tuning, and note the one deliberate difference:
/// `yawSignUserLeft` (see below).
///
/// WHAT THIS PROVES, AND WHAT IT DOES NOT:
/// ML Kit face detection has no presentation-attack detection (PAD). A challenge-response
/// sequence defeats a held-up still photo or a printed face, because a print cannot blink,
/// smile or turn on demand. It does NOT defeat a replayed video, an injected/deepfake camera
/// feed, or a 3D mask. Treat the outcome as one signal only: the authoritative check is the
/// back office matching the returned portrait against the document chip portrait (DG2).
final class LivenessDetector {

    // MARK: - Tuning

    /// Face box area as a fraction of the frame — below this the user is too far away.
    private static let minFaceAreaRatio: Float = 0.06
    /// Above this the face is clipped or too close to yield a usable portrait.
    private static let maxFaceAreaRatio: Float = 0.60
    /// Max normalised distance from face centre to frame centre.
    private static let maxCenterOffset: Float = 0.22

    /// "Looking straight at the camera" tolerances, in degrees.
    private static let neutralMaxYaw: Float = 14
    private static let neutralMaxPitch: Float = 16
    private static let neutralMaxRoll: Float = 14

    private static let eyeOpenProbability: Float = 0.55
    private static let eyeClosedProbability: Float = 0.25
    private static let smileProbability: Float = 0.72
    private static let turnYawDegrees: Float = 25

    /// Sign of ML Kit's `headEulerAngleY` that corresponds to the user turning their own head LEFT.
    ///
    /// ML Kit documents positive euler Y as "the face turns toward the right side of the image
    /// being processed". `LivenessCameraViewController` hands ML Kit a `.leftMirrored` image —
    /// the orientation ML Kit's own front-camera samples use — so the image is mirrored and its
    /// right-hand side is the user's own right. Hence -1 here.
    ///
    /// LivenessDetector.java uses +1, because CameraX hands the analyser an un-mirrored sensor
    /// frame. If the turn prompts ever read reversed on a device, this single constant is the fix.
    private static let yawSignUserLeft: Float = -1

    /// Consecutive qualifying frames required to accept a sustained pose (smile, head turn).
    private static let poseConfirmFrames = 2
    /// Consecutive frames with >1 face before the session is abandoned.
    private static let multiFaceAbortFrames = 15
    /// How long we collect neutral candidates before settling on the best portrait.
    private static let captureWindowMs: Double = 1200

    // MARK: - Types

    enum Challenge: String, CaseIterable {
        case blink
        case smile
        case turnLeft
        case turnRight
    }

    enum State {
        case waitingForFace
        case centering
        case challenge
        case capturing
        case passed
        case failed
    }

    /// One frame of ML Kit output, normalised to the frame size.
    struct Observation {
        var faceCount: Int = 0
        /// Face box area / frame area.
        var areaRatio: Float = 0
        /// Face box centre offset from frame centre, normalised to frame width/height.
        var centerOffsetX: Float = 0
        var centerOffsetY: Float = 0
        var yaw: Float = 0
        var pitch: Float = 0
        var roll: Float = 0
        /// nil when ML Kit did not compute the classification for this frame.
        var leftEyeOpen: Float?
        var rightEyeOpen: Float?
        var smiling: Float?
        /// ML Kit tracking id; a change mid-session means a different face took over.
        var trackingId: Int?
        /// Milliseconds, from the frame presentation timestamp (monotonic).
        var timestampMs: Double = 0
    }

    /// Outcome of a single challenge, surfaced to the back office for scoring.
    final class ChallengeResult {
        let type: Challenge
        var passed = false
        var durationMs: Double = 0

        init(type: Challenge) {
            self.type = type
        }
    }

    /// What the caller should do with the frame it just handed in.
    struct Update {
        var state: State
        var challenge: Challenge?
        var promptKey: String
        /// This frame is the best portrait candidate so far — grab it.
        var capturePortrait = false
        /// This frame is the moment a challenge was satisfied — grab it if evidence is wanted.
        var captureChallengeFrame: Challenge?
        var finished = false
        var passed = false
        var failureCode: String?
    }

    struct Config {
        var challenges: [Challenge] = [.blink, .smile]
        var overallTimeoutMs: Double = 45_000
        var perChallengeTimeoutMs: Double = 15_000
        var faceSearchTimeoutMs: Double = 20_000
    }

    // MARK: - Session state

    private let config: Config
    private(set) var results: [ChallengeResult]

    private(set) var state: State = .waitingForFace
    private(set) var failureCode: String?
    private var challengeIndex = 0
    private var sessionStartMs: Double?
    private var stateEnteredMs: Double = 0
    private var captureWindowStartMs: Double?

    // Per-challenge progress
    private var consecutivePoseFrames = 0
    private var eyesWereOpen = false
    private var eyesWentClosed = false

    // Continuity / quality signals
    private var lockedTrackingId: Int?
    private(set) var trackingIdChanges = 0
    private var consecutiveMultiFaceFrames = 0
    private(set) var multiFaceFrames = 0
    private(set) var framesAnalysed = 0
    private var bestPortraitScore: Float = -1
    private var havePortrait = false

    init(config: Config) {
        self.config = config
        self.results = config.challenges.map { ChallengeResult(type: $0) }
    }

    /// Pick a random subset of challenges. Randomising per session is what stops an attacker
    /// pre-recording a single clip of the expected actions in the expected order.
    static func randomChallenges(count: Int) -> [Challenge] {
        let pool = Challenge.allCases.shuffled()
        let n = max(1, min(count, pool.count))
        return Array(pool.prefix(n))
    }

    var challengeOrder: [Challenge] {
        return results.map { $0.type }
    }

    func elapsedMs(now: Double) -> Double {
        guard let start = sessionStartMs else { return 0 }
        return now - start
    }

    // MARK: - Frame processing

    /// Feed one analysed frame. Returns what the caller should render and capture.
    /// Safe to keep calling after the session finishes — it returns the terminal state unchanged.
    func onFrame(_ obs: Observation) -> Update {
        if state == .passed || state == .failed {
            return terminalUpdate()
        }

        framesAnalysed += 1
        if sessionStartMs == nil {
            sessionStartMs = obs.timestampMs
            stateEnteredMs = obs.timestampMs
        }

        if let start = sessionStartMs, obs.timestampMs - start > config.overallTimeoutMs {
            return fail("OVERALL_TIMEOUT")
        }

        // ---- Multiple faces: transient is fine, sustained is not ----
        if obs.faceCount > 1 {
            multiFaceFrames += 1
            consecutiveMultiFaceFrames += 1
            if consecutiveMultiFaceFrames >= Self.multiFaceAbortFrames {
                return fail("MULTIPLE_FACES")
            }
            return progress("multipleFaces")
        }
        consecutiveMultiFaceFrames = 0

        // ---- No face ----
        if obs.faceCount == 0 {
            if state == .waitingForFace,
               let start = sessionStartMs,
               obs.timestampMs - start > config.faceSearchTimeoutMs {
                return fail("NO_FACE_TIMEOUT")
            }
            // Losing the face mid-challenge resets that challenge's progress but not the session.
            resetPoseProgress()
            if state == .challenge && timedOutInState(obs) {
                return fail("CHALLENGE_TIMEOUT")
            }
            return progress("findFace")
        }

        // ---- Face continuity: the same person must be present throughout ----
        if let trackingId = obs.trackingId {
            if lockedTrackingId == nil {
                lockedTrackingId = trackingId
            } else if lockedTrackingId != trackingId {
                trackingIdChanges += 1
                // A tracking id change after the first challenge means the face in front of the
                // camera was swapped mid-session — abandon rather than silently continue.
                if challengeIndex > 0 || state == .capturing {
                    return fail("FACE_CHANGED")
                }
                lockedTrackingId = trackingId
            }
        }

        // ---- Framing gates, applied in every state ----
        if obs.areaRatio < Self.minFaceAreaRatio {
            resetPoseProgress()
            return framingProgress("tooFar", obs)
        }
        if obs.areaRatio > Self.maxFaceAreaRatio {
            resetPoseProgress()
            return framingProgress("tooClose", obs)
        }
        if abs(obs.centerOffsetX) > Self.maxCenterOffset || abs(obs.centerOffsetY) > Self.maxCenterOffset {
            resetPoseProgress()
            return framingProgress("center", obs)
        }

        switch state {
        case .waitingForFace, .centering:
            // Face is present and well framed — begin (or resume) the challenge sequence.
            enterState(.challenge, obs)
            return challengeFrame(obs)
        case .challenge:
            return challengeFrame(obs)
        case .capturing:
            return captureFrame(obs)
        default:
            return terminalUpdate()
        }
    }

    // MARK: - Challenge evaluation

    private func challengeFrame(_ obs: Observation) -> Update {
        guard challengeIndex < results.count else {
            enterState(.capturing, obs)
            return captureFrame(obs)
        }

        let current = results[challengeIndex]

        if timedOutInState(obs) {
            current.passed = false
            current.durationMs = obs.timestampMs - stateEnteredMs
            return fail("CHALLENGE_TIMEOUT")
        }

        let satisfied: Bool
        switch current.type {
        case .blink:
            satisfied = evaluateBlink(obs)
        case .smile:
            satisfied = sustained(isSmiling(obs))
        case .turnLeft:
            satisfied = sustained(isTurned(obs, userLeft: true))
        case .turnRight:
            satisfied = sustained(isTurned(obs, userLeft: false))
        }

        if !satisfied {
            var update = progress(promptKey(for: current.type))
            update.challenge = current.type
            return update
        }

        current.passed = true
        current.durationMs = obs.timestampMs - stateEnteredMs

        challengeIndex += 1
        resetPoseProgress()

        if challengeIndex >= results.count {
            enterState(.capturing, obs)
            var update = Update(state: .capturing, challenge: nil, promptKey: "hold")
            update.captureChallengeFrame = current.type
            return update
        }

        stateEnteredMs = obs.timestampMs
        let next = results[challengeIndex].type
        var update = Update(state: .challenge, challenge: next, promptKey: promptKey(for: next))
        update.captureChallengeFrame = current.type
        return update
    }

    /// A blink is a transition, not a pose: we require eyes clearly open, then clearly closed,
    /// then clearly open again. Requiring the full arc is what a printed photo cannot produce.
    private func evaluateBlink(_ obs: Observation) -> Bool {
        guard let left = obs.leftEyeOpen, let right = obs.rightEyeOpen else {
            return false
        }

        let open = left > Self.eyeOpenProbability && right > Self.eyeOpenProbability
        let closed = left < Self.eyeClosedProbability && right < Self.eyeClosedProbability

        if open && !eyesWentClosed {
            eyesWereOpen = true
            return false
        }
        if closed && eyesWereOpen {
            eyesWentClosed = true
            return false
        }
        return open && eyesWentClosed
    }

    private func isSmiling(_ obs: Observation) -> Bool {
        guard let smiling = obs.smiling else { return false }
        return smiling > Self.smileProbability && isNeutralPose(obs)
    }

    private func isTurned(_ obs: Observation, userLeft: Bool) -> Bool {
        let signed = obs.yaw * Self.yawSignUserLeft * (userLeft ? 1 : -1)
        return signed > Self.turnYawDegrees
    }

    /// Sustained-pose helper: only accept after `poseConfirmFrames` consecutive qualifying frames.
    private func sustained(_ qualifies: Bool) -> Bool {
        guard qualifies else {
            consecutivePoseFrames = 0
            return false
        }
        consecutivePoseFrames += 1
        return consecutivePoseFrames >= Self.poseConfirmFrames
    }

    // MARK: - Portrait capture

    /// Final step: hold still and look at the camera while we pick the best neutral frame.
    ///
    /// The returned portrait is deliberately taken from the same analysed frame stream that
    /// satisfied the challenges, rather than from a separate still capture afterwards. That
    /// closes the gap where a subject could pass the challenges and then swap what the camera
    /// sees before the photo is taken.
    private func captureFrame(_ obs: Observation) -> Update {
        if captureWindowStartMs == nil {
            captureWindowStartMs = obs.timestampMs
        }

        var update = Update(state: .capturing, challenge: nil, promptKey: "hold")

        if isNeutralPose(obs) && eyesOpen(obs) {
            let score = portraitScore(obs)
            if score > bestPortraitScore {
                bestPortraitScore = score
                havePortrait = true
                update.capturePortrait = true
            }
        }

        let windowElapsed = obs.timestampMs - (captureWindowStartMs ?? obs.timestampMs) >= Self.captureWindowMs
        if windowElapsed && havePortrait {
            state = .passed
            update.state = .passed
            update.promptKey = "success"
            update.finished = true
            update.passed = true
            return update
        }
        if timedOutInState(obs) {
            return fail(havePortrait ? "CHALLENGE_TIMEOUT" : "NO_USABLE_PORTRAIT")
        }

        return update
    }

    /// Prefer a large, square-on face — bigger and straighter scores higher.
    private func portraitScore(_ obs: Observation) -> Float {
        let straightness = 1
            - (abs(obs.yaw) / Self.neutralMaxYaw) * 0.3
            - (abs(obs.pitch) / Self.neutralMaxPitch) * 0.2
            - (abs(obs.roll) / Self.neutralMaxRoll) * 0.2
        return obs.areaRatio * max(straightness, 0.1)
    }

    private func isNeutralPose(_ obs: Observation) -> Bool {
        return abs(obs.yaw) <= Self.neutralMaxYaw
            && abs(obs.pitch) <= Self.neutralMaxPitch
            && abs(obs.roll) <= Self.neutralMaxRoll
    }

    private func eyesOpen(_ obs: Observation) -> Bool {
        guard let left = obs.leftEyeOpen, let right = obs.rightEyeOpen else { return false }
        return left > Self.eyeOpenProbability && right > Self.eyeOpenProbability
    }

    // MARK: - State helpers

    private func enterState(_ next: State, _ obs: Observation) {
        state = next
        stateEnteredMs = obs.timestampMs
        resetPoseProgress()
    }

    private func resetPoseProgress() {
        consecutivePoseFrames = 0
        eyesWereOpen = false
        eyesWentClosed = false
    }

    private func timedOutInState(_ obs: Observation) -> Bool {
        return stateEnteredMs > 0 && obs.timestampMs - stateEnteredMs > config.perChallengeTimeoutMs
    }

    private func progress(_ promptKey: String) -> Update {
        return Update(state: state, challenge: nil, promptKey: promptKey)
    }

    /// Framing feedback keeps the current state; only the prompt changes.
    private func framingProgress(_ promptKey: String, _ obs: Observation) -> Update {
        if state == .waitingForFace {
            state = .centering
            stateEnteredMs = obs.timestampMs
        }
        return progress(promptKey)
    }

    private func fail(_ code: String) -> Update {
        state = .failed
        failureCode = code
        var update = Update(state: .failed, challenge: nil, promptKey: "failed")
        update.finished = true
        update.passed = false
        update.failureCode = code
        return update
    }

    private func terminalUpdate() -> Update {
        var update = Update(state: state, challenge: nil, promptKey: state == .passed ? "success" : "failed")
        update.finished = true
        update.passed = state == .passed
        update.failureCode = failureCode
        return update
    }

    private func promptKey(for challenge: Challenge) -> String {
        return challenge.rawValue
    }
}
