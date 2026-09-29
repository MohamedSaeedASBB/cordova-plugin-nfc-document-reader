package com.nfcdocumentreader;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Challenge-response liveness state machine.
 *
 * Platform-neutral by design: it consumes normalised {@link Observation} values derived from
 * ML Kit face detection and drives the prompt/state sequence. LivenessDetector.swift mirrors
 * this logic and these thresholds — keep the two in sync when tuning.
 *
 * WHAT THIS PROVES, AND WHAT IT DOES NOT:
 * ML Kit face detection has no presentation-attack detection (PAD). A challenge-response
 * sequence defeats a held-up still photo or a printed face, because a print cannot blink,
 * smile or turn on demand. It does NOT defeat a replayed video, an injected/deepfake camera
 * feed, or a 3D mask. Treat the outcome as one signal only: the authoritative check is the
 * back office matching the returned portrait against the document chip portrait (DG2).
 */
public class LivenessDetector {

    // ==================== Tuning ====================

    /** Face box area as a fraction of the frame — below this the user is too far away. */
    private static final float MIN_FACE_AREA_RATIO = 0.06f;
    /** Above this the face is clipped or too close to yield a usable portrait. */
    private static final float MAX_FACE_AREA_RATIO = 0.60f;
    /** Max normalised distance from face centre to frame centre. */
    private static final float MAX_CENTER_OFFSET = 0.22f;

    /** "Looking straight at the camera" tolerances, in degrees. */
    private static final float NEUTRAL_MAX_YAW = 14f;
    private static final float NEUTRAL_MAX_PITCH = 16f;
    private static final float NEUTRAL_MAX_ROLL = 14f;

    private static final float EYE_OPEN_PROB = 0.55f;
    private static final float EYE_CLOSED_PROB = 0.25f;
    private static final float SMILE_PROB = 0.72f;
    private static final float TURN_YAW_DEGREES = 25f;

    /**
     * Sign of ML Kit's headEulerAngleY that corresponds to the user turning their own head LEFT.
     *
     * ML Kit documents positive euler Y as "the face turns toward the right side of the image
     * being processed". CameraX hands the analyser the raw front-camera sensor frame, which is
     * NOT mirrored (PreviewView mirrors for display only), so the right-hand side of the image
     * is the user's own left — hence +1 here.
     *
     * LivenessDetector.swift uses -1, because the iOS pipeline hands ML Kit a mirrored image
     * (.leftMirrored, the orientation ML Kit's own front-camera samples use). If the turn
     * prompts ever read reversed on a device, this single constant is the fix.
     */
    public static final float DEFAULT_YAW_SIGN_USER_LEFT = 1f;

    /**
     * How far the face may sit from the guide while a turn is being asked for.
     *
     * Turning your head moves your face: the nose swings sideways and the far cheek disappears, so
     * the box both shifts and narrows. Holding it to the same tolerance as a head-on pose asks the
     * customer to turn without turning, and every frame that trips the gate resets the pose
     * progress they had built up. Wider, but still bounded, so a face leaving the frame is caught.
     */
    private static final float TURNING_CENTER_OFFSET = 0.34f;

    /**
     * Off-axis thresholds, used only by the compound challenges.
     *
     * ML Kit's smile and eye-open classifiers are trained on faces looking at the camera and both
     * degrade as the head turns: at the 25 degrees a plain TURN challenge asks for, a genuine
     * smile often scores below the 0.72 a frontal one clears. Holding the frontal thresholds for a
     * compound challenge would mean asking the customer to do something the detector cannot see,
     * so the turn is shallower and the smile bar lower.
     *
     * That is not a weaker check. A compound challenge requires two independent muscle groups at
     * the same instant, which is the part a spliced or replayed clip cannot fake — not the
     * magnitude of either one.
     *
     * These numbers have not been calibrated against real faces. They are a starting point, and
     * they need the same on-device calibration as the face-match threshold before the compound
     * challenges are enabled for customers.
     */
    private static final float COMPOUND_TURN_YAW_DEGREES = 18f;
    private static final float COMPOUND_SMILE_PROB = 0.55f;

    /** Consecutive qualifying frames required to accept a sustained pose (smile, head turn). */
    private static final int POSE_CONFIRM_FRAMES = 2;

    /**
     * How long a pose must be held, in milliseconds, on top of the frame count.
     *
     * The frame count alone is frame-rate dependent, and that is why the check runs visibly faster
     * on Android than on iOS: two frames is about 66ms at 30fps and about 130ms at 15fps, so the
     * same person doing the same thing passes at different speeds on different handsets. A
     * duration is the same everywhere, which is what makes the two platforms agree.
     */
    private static final long DEFAULT_POSE_HOLD_MS = 600L;
    /** Consecutive frames with >1 face before the session is abandoned. */
    private static final int MULTI_FACE_ABORT_FRAMES = 15;
    /** How long we collect neutral candidates before settling on the best portrait. */
    private static final long CAPTURE_WINDOW_MS = 1200L;

    // ==================== Types ====================

    /**
     * The single-action challenges, then the compound ones that require a head turn and a facial
     * action at the same moment. A compound challenge is satisfied only while both hold together:
     * turning, then smiling, does not pass.
     */
    public enum Challenge {
        BLINK, SMILE, TURN_LEFT, TURN_RIGHT,
        TURN_LEFT_SMILE, TURN_RIGHT_SMILE, TURN_LEFT_BLINK, TURN_RIGHT_BLINK;

        public boolean isCompound() {
            return this == TURN_LEFT_SMILE || this == TURN_RIGHT_SMILE
                    || this == TURN_LEFT_BLINK || this == TURN_RIGHT_BLINK;
        }

        /** True when the compound challenge turns the head to the user's own left. */
        boolean turnsUserLeft() {
            return this == TURN_LEFT || this == TURN_LEFT_SMILE || this == TURN_LEFT_BLINK;
        }
    }

    public enum State { WAITING_FOR_FACE, CENTERING, CHALLENGE, CAPTURING, PASSED, FAILED }

    /** One frame of ML Kit output, normalised to the frame size and free of platform types. */
    public static class Observation {
        public int faceCount;
        /** Face box area / frame area. */
        public float areaRatio;
        /** Face box centre offset from frame centre, normalised to frame width/height. */
        public float centerOffsetX;
        public float centerOffsetY;
        public float yaw;
        public float pitch;
        public float roll;
        /** Null when ML Kit did not compute the classification for this frame. */
        public Float leftEyeOpen;
        public Float rightEyeOpen;
        public Float smiling;
        /** ML Kit tracking id; a change mid-session means a different face took over. */
        public Integer trackingId;
        public long timestampMs;
    }

    /** Outcome of a single challenge, surfaced to the back office for scoring. */
    public static class ChallengeResult {
        public final Challenge type;
        public boolean passed;
        public long durationMs;

        ChallengeResult(Challenge type) {
            this.type = type;
        }
    }

    /** What the caller should do with the frame it just handed in. */
    public static class Update {
        public State state;
        public Challenge challenge;
        public String promptKey;
        /** This frame is the best portrait candidate so far — grab it. */
        public boolean capturePortrait;
        /** This frame is the moment a challenge was satisfied — grab it if evidence is wanted. */
        public Challenge captureChallengeFrame;
        public boolean finished;
        public boolean passed;
        public String failureCode;
    }

    /** Caller-supplied session settings. */
    public static class Config {
        public List<Challenge> challenges = Arrays.asList(Challenge.BLINK, Challenge.SMILE);
        public long overallTimeoutMs = 45_000L;
        public long perChallengeTimeoutMs = 15_000L;
        public long faceSearchTimeoutMs = 20_000L;
        /** See DEFAULT_POSE_HOLD_MS. Raise it to slow the check down, lower it to speed it up. */
        public long poseHoldMs = DEFAULT_POSE_HOLD_MS;
        /**
         * Sign of the platform's reported yaw that means the user turned their own head LEFT.
         *
         * A constant until iOS proved it is not one: the value depends on whether the vision
         * library was handed a mirrored image, which on iOS is not knowable before the first frame.
         * See LivenessCameraViewController.swift.
         */
        public float yawSignUserLeft = DEFAULT_YAW_SIGN_USER_LEFT;
    }

    // ==================== Session state ====================

    private final Config config;
    private final List<ChallengeResult> results = new ArrayList<>();

    private State state = State.WAITING_FOR_FACE;
    private int challengeIndex = 0;
    private long sessionStartMs = -1L;
    private long stateEnteredMs = -1L;
    private long captureWindowStartMs = -1L;
    private String failureCode;

    // Per-challenge progress
    private int consecutivePoseFrames = 0;
    private float maxYawObserved = 0f;
    private float minYawObserved = 0f;
    private long poseHoldStartMs = -1L;
    private boolean eyesWereOpen = false;
    private boolean eyesWentClosed = false;

    // Continuity / quality signals
    private Integer lockedTrackingId;
    private int trackingIdChanges = 0;
    private int consecutiveMultiFaceFrames = 0;
    private int multiFaceFrames = 0;
    private int framesAnalysed = 0;
    private float bestPortraitScore = -1f;
    private boolean havePortrait = false;

    public LivenessDetector(Config config) {
        this.config = config;
        for (Challenge c : config.challenges) {
            results.add(new ChallengeResult(c));
        }
    }

    /**
     * Pick a random subset of challenges. Randomising per session is what stops an attacker
     * pre-recording a single clip of the expected actions in the expected order.
     */
    public static List<Challenge> randomChallenges(int count) {
        return randomChallenges(count, false);
    }

    /**
     * Every challenge, in a random order.
     *
     * The order is still shuffled even though the set is fixed. Knowing which challenges are
     * coming is not the same as knowing when, and a pre-recorded clip still has to match the
     * sequence it is actually asked for, in the order it is asked.
     */
    public static List<Challenge> allChallenges() {
        List<Challenge> all = new ArrayList<>(Arrays.asList(Challenge.values()));
        Collections.shuffle(all, new SecureRandom());
        return all;
    }

    /**
     * A random subset, which is what stops an attacker pre-recording the expected actions in the
     * expected order.
     *
     * Compound challenges are excluded unless asked for. They are harder to pass, their thresholds
     * are uncalibrated, and turning them on silently would change the experience of every customer
     * of an app that upgrades this plugin without changing a line of its own code. Opting in is one
     * flag; opting out after a wave of failed checks in a branch is not.
     */
    public static List<Challenge> randomChallenges(int count, boolean includeCompound) {
        List<Challenge> pool = new ArrayList<>();
        for (Challenge challenge : Challenge.values()) {
            if (includeCompound || !challenge.isCompound()) {
                pool.add(challenge);
            }
        }
        Collections.shuffle(pool, new SecureRandom());
        int n = Math.max(1, Math.min(count, pool.size()));
        return new ArrayList<>(pool.subList(0, n));
    }

    // ==================== Frame processing ====================

    /**
     * Feed one analysed frame. Returns what the caller should render and capture.
     * Safe to keep calling after the session finishes — it returns the terminal state unchanged.
     */
    public Update onFrame(Observation obs) {
        Update update = new Update();

        if (state == State.PASSED || state == State.FAILED) {
            return terminal(update);
        }

        framesAnalysed++;
        if (obs.yaw > maxYawObserved) maxYawObserved = obs.yaw;
        if (obs.yaw < minYawObserved) minYawObserved = obs.yaw;
        if (sessionStartMs < 0) {
            sessionStartMs = obs.timestampMs;
            stateEnteredMs = obs.timestampMs;
        }

        if (obs.timestampMs - sessionStartMs > config.overallTimeoutMs) {
            return fail(update, "OVERALL_TIMEOUT");
        }

        // ---- Multiple faces: transient is fine, sustained is not ----
        if (obs.faceCount > 1) {
            multiFaceFrames++;
            consecutiveMultiFaceFrames++;
            if (consecutiveMultiFaceFrames >= MULTI_FACE_ABORT_FRAMES) {
                return fail(update, "MULTIPLE_FACES");
            }
            return progress(update, "multipleFaces");
        }
        consecutiveMultiFaceFrames = 0;

        // ---- No face ----
        if (obs.faceCount == 0) {
            if (state == State.WAITING_FOR_FACE
                    && obs.timestampMs - sessionStartMs > config.faceSearchTimeoutMs) {
                return fail(update, "NO_FACE_TIMEOUT");
            }
            // Losing the face mid-challenge resets that challenge's progress but not the session.
            resetPoseProgress();
            if (state == State.CHALLENGE && timedOutInState(obs)) {
                return fail(update, "CHALLENGE_TIMEOUT");
            }
            return progress(update, "findFace");
        }

        // ---- Face continuity: the same person must be present throughout ----
        if (obs.trackingId != null) {
            if (lockedTrackingId == null) {
                lockedTrackingId = obs.trackingId;
            } else if (!lockedTrackingId.equals(obs.trackingId)) {
                trackingIdChanges++;
                // A tracking id change after the first challenge means the face in front of the
                // camera was swapped mid-session — abandon rather than silently continue.
                if (challengeIndex > 0 || state == State.CAPTURING) {
                    return fail(update, "FACE_CHANGED");
                }
                lockedTrackingId = obs.trackingId;
            }
        }

        // ---- Framing gates, applied in every state ----
        if (obs.areaRatio < MIN_FACE_AREA_RATIO) {
            resetPoseProgress();
            return framingProgress(update, "tooFar", obs);
        }
        if (obs.areaRatio > MAX_FACE_AREA_RATIO) {
            resetPoseProgress();
            return framingProgress(update, "tooClose", obs);
        }
        float centerTolerance = askingForATurn() ? TURNING_CENTER_OFFSET : MAX_CENTER_OFFSET;
        if (Math.abs(obs.centerOffsetX) > centerTolerance
                || Math.abs(obs.centerOffsetY) > centerTolerance) {
            resetPoseProgress();
            return framingProgress(update, "center", obs);
        }

        switch (state) {
            case WAITING_FOR_FACE:
            case CENTERING:
                // Face is present and well framed — begin (or resume) the challenge sequence.
                enterState(State.CHALLENGE, obs);
                return challengeFrame(update, obs);
            case CHALLENGE:
                return challengeFrame(update, obs);
            case CAPTURING:
                return captureFrame(update, obs);
            default:
                return terminal(update);
        }
    }

    // ==================== Challenge evaluation ====================

    private Update challengeFrame(Update update, Observation obs) {
        if (challengeIndex >= results.size()) {
            enterState(State.CAPTURING, obs);
            return captureFrame(update, obs);
        }

        ChallengeResult current = results.get(challengeIndex);

        if (timedOutInState(obs)) {
            current.passed = false;
            current.durationMs = obs.timestampMs - stateEnteredMs;
            return fail(update, "CHALLENGE_TIMEOUT");
        }

        boolean satisfied;
        switch (current.type) {
            case BLINK:
                satisfied = evaluateBlink(obs, false, false);
                break;
            case SMILE:
                satisfied = sustained(isSmiling(obs), obs);
                break;
            case TURN_LEFT:
                satisfied = sustained(isTurned(obs, true), obs);
                break;
            case TURN_RIGHT:
                satisfied = sustained(isTurned(obs, false), obs);
                break;
            case TURN_LEFT_SMILE:
            case TURN_RIGHT_SMILE: {
                boolean userLeft = current.type.turnsUserLeft();
                // Both at once. isSmilingOffAxis drops the look-at-the-camera requirement that
                // the plain SMILE challenge keeps, because here the head is deliberately turned.
                satisfied = sustained(isTurnedForCompound(obs, userLeft) && isSmilingOffAxis(obs),
                                      obs);
                break;
            }
            case TURN_LEFT_BLINK:
            case TURN_RIGHT_BLINK: {
                boolean userLeft = current.type.turnsUserLeft();
                if (!isTurnedForCompound(obs, userLeft)) {
                    // The blink has to happen while turned, so losing the turn restarts the arc
                    // rather than letting a blink from before it count.
                    eyesWereOpen = false;
                    eyesWentClosed = false;
                    satisfied = false;
                } else {
                    satisfied = evaluateBlink(obs, true, userLeft);
                }
                break;
            }
            default:
                satisfied = false;
        }

        if (!satisfied) {
            update.challenge = current.type;
            return progress(update, promptKeyFor(current.type));
        }

        current.passed = true;
        current.durationMs = obs.timestampMs - stateEnteredMs;
        update.captureChallengeFrame = current.type;

        challengeIndex++;
        resetPoseProgress();

        if (challengeIndex >= results.size()) {
            enterState(State.CAPTURING, obs);
            update.state = State.CAPTURING;
            update.promptKey = "hold";
            return update;
        }

        stateEnteredMs = obs.timestampMs;
        update.state = State.CHALLENGE;
        update.challenge = results.get(challengeIndex).type;
        update.promptKey = promptKeyFor(update.challenge);
        return update;
    }

    /**
     * A blink is a transition, not a pose: we require eyes clearly open, then clearly closed,
     * then clearly open again. Requiring the full arc is what a printed photo cannot produce.
     */
    private boolean evaluateBlink(Observation obs, boolean nearEyeOnly, boolean userTurnedLeft) {
        Float left = obs.leftEyeOpen;
        Float right = obs.rightEyeOpen;

        if (nearEyeOnly) {
            // A turned head hides the far eye, and ML Kit still reports a probability for it —
            // an unreliable one. Turning to your own left rotates your left cheek away from the
            // camera, so the eye it can still see is your right one. Judging the hidden eye is
            // how a compound blink challenge becomes impossible to pass.
            Float nearEye = userTurnedLeft ? right : left;
            left = nearEye;
            right = nearEye;
        }

        if (left == null || right == null) {
            return false;
        }

        boolean open = left > EYE_OPEN_PROB && right > EYE_OPEN_PROB;
        boolean closed = left < EYE_CLOSED_PROB && right < EYE_CLOSED_PROB;

        if (open && !eyesWentClosed) {
            eyesWereOpen = true;
            return false;
        }
        if (closed && eyesWereOpen) {
            eyesWentClosed = true;
            return false;
        }
        return open && eyesWentClosed;
    }

    private boolean isSmiling(Observation obs) {
        return obs.smiling != null && obs.smiling > SMILE_PROB && isNeutralPose(obs);
    }

    /** A smile judged on a head that is deliberately turned, so pose is not part of the test. */
    private boolean isSmilingOffAxis(Observation obs) {
        return obs.smiling != null && obs.smiling > COMPOUND_SMILE_PROB;
    }

    /** The shallower turn a compound challenge asks for. See COMPOUND_TURN_YAW_DEGREES. */
    private boolean isTurnedForCompound(Observation obs, boolean userLeft) {
        float signed = obs.yaw * config.yawSignUserLeft * (userLeft ? 1f : -1f);
        return signed > COMPOUND_TURN_YAW_DEGREES;
    }

    private boolean isTurned(Observation obs, boolean userLeft) {
        float signed = obs.yaw * config.yawSignUserLeft * (userLeft ? 1f : -1f);
        return signed > TURN_YAW_DEGREES;
    }

    /** True while the current challenge asks the customer to turn their head. */
    private boolean askingForATurn() {
        if (state != State.CHALLENGE || challengeIndex >= results.size()) return false;
        Challenge current = results.get(challengeIndex).type;
        return current != Challenge.BLINK && current != Challenge.SMILE;
    }

    /**
     * The largest turn seen this session, in each direction, as the platform reported it.
     *
     * Reported so a turn challenge that fails can be diagnosed from the payload rather than from a
     * cable. A session where the customer plainly turned but the values stayed near zero means the
     * angle is not reaching the detector; one where they are large but the challenge still failed
     * means the sign is inverted for this platform.
     */
    public float getMaxYawObserved() { return maxYawObserved; }
    public float getMinYawObserved() { return minYawObserved; }

    /**
     * Accept a pose only once it has been held for POSE_CONFIRM_FRAMES consecutive qualifying
     * frames AND for config.poseHoldMs. The frame count rejects a single noisy detection; the
     * duration is what makes a fast Android handset and an iPhone agree, because it does not
     * depend on how many frames per second the camera happens to deliver.
     */
    private boolean sustained(boolean qualifies, Observation obs) {
        if (!qualifies) {
            consecutivePoseFrames = 0;
            poseHoldStartMs = -1L;
            return false;
        }
        consecutivePoseFrames++;
        if (poseHoldStartMs < 0) {
            poseHoldStartMs = obs.timestampMs;
        }
        long heldMs = obs.timestampMs - poseHoldStartMs;
        return consecutivePoseFrames >= POSE_CONFIRM_FRAMES && heldMs >= config.poseHoldMs;
    }

    // ==================== Portrait capture ====================

    /**
     * Final step: hold still and look at the camera while we pick the best neutral frame.
     *
     * The returned portrait is deliberately taken from the same analysed frame stream that
     * satisfied the challenges, rather than from a separate still capture afterwards. That
     * closes the gap where a subject could pass the challenges and then swap what the camera
     * sees before the photo is taken.
     */
    private Update captureFrame(Update update, Observation obs) {
        if (captureWindowStartMs < 0) {
            captureWindowStartMs = obs.timestampMs;
        }

        if (isNeutralPose(obs) && eyesOpen(obs)) {
            float score = portraitScore(obs);
            if (score > bestPortraitScore) {
                bestPortraitScore = score;
                havePortrait = true;
                update.capturePortrait = true;
            }
        }

        boolean windowElapsed = obs.timestampMs - captureWindowStartMs >= CAPTURE_WINDOW_MS;
        if (windowElapsed && havePortrait) {
            state = State.PASSED;
            update.state = State.PASSED;
            update.promptKey = "success";
            update.finished = true;
            update.passed = true;
            return update;
        }
        if (timedOutInState(obs)) {
            return fail(update, havePortrait ? "CHALLENGE_TIMEOUT" : "NO_USABLE_PORTRAIT");
        }

        update.state = State.CAPTURING;
        update.promptKey = "hold";
        return update;
    }

    /** Prefer a large, square-on, well-lit-looking face — bigger and straighter scores higher. */
    private float portraitScore(Observation obs) {
        float straightness = 1f
                - (Math.abs(obs.yaw) / NEUTRAL_MAX_YAW) * 0.3f
                - (Math.abs(obs.pitch) / NEUTRAL_MAX_PITCH) * 0.2f
                - (Math.abs(obs.roll) / NEUTRAL_MAX_ROLL) * 0.2f;
        return obs.areaRatio * Math.max(straightness, 0.1f);
    }

    private boolean isNeutralPose(Observation obs) {
        return Math.abs(obs.yaw) <= NEUTRAL_MAX_YAW
                && Math.abs(obs.pitch) <= NEUTRAL_MAX_PITCH
                && Math.abs(obs.roll) <= NEUTRAL_MAX_ROLL;
    }

    private boolean eyesOpen(Observation obs) {
        return obs.leftEyeOpen != null && obs.rightEyeOpen != null
                && obs.leftEyeOpen > EYE_OPEN_PROB && obs.rightEyeOpen > EYE_OPEN_PROB;
    }

    // ==================== State helpers ====================

    private void enterState(State next, Observation obs) {
        state = next;
        stateEnteredMs = obs.timestampMs;
        resetPoseProgress();
    }

    private void resetPoseProgress() {
        consecutivePoseFrames = 0;
        poseHoldStartMs = -1L;
        eyesWereOpen = false;
        eyesWentClosed = false;
    }

    private boolean timedOutInState(Observation obs) {
        return stateEnteredMs > 0 && obs.timestampMs - stateEnteredMs > config.perChallengeTimeoutMs;
    }

    private Update progress(Update update, String promptKey) {
        update.state = state;
        update.promptKey = promptKey;
        return update;
    }

    /** Framing feedback keeps the current state; only the prompt changes. */
    private Update framingProgress(Update update, String promptKey, Observation obs) {
        if (state == State.WAITING_FOR_FACE) {
            state = State.CENTERING;
            stateEnteredMs = obs.timestampMs;
        }
        return progress(update, promptKey);
    }

    private Update fail(Update update, String code) {
        state = State.FAILED;
        failureCode = code;
        update.state = State.FAILED;
        update.promptKey = "failed";
        update.finished = true;
        update.passed = false;
        update.failureCode = code;
        return update;
    }

    private Update terminal(Update update) {
        update.state = state;
        update.finished = true;
        update.passed = state == State.PASSED;
        update.failureCode = failureCode;
        return update;
    }

    private static String promptKeyFor(Challenge challenge) {
        switch (challenge) {
            case BLINK: return "blink";
            case SMILE: return "smile";
            case TURN_LEFT: return "turnLeft";
            case TURN_RIGHT: return "turnRight";
            case TURN_LEFT_SMILE: return "turnLeftSmile";
            case TURN_RIGHT_SMILE: return "turnRightSmile";
            case TURN_LEFT_BLINK: return "turnLeftBlink";
            case TURN_RIGHT_BLINK: return "turnRightBlink";
            default: return "findFace";
        }
    }

    // ==================== Accessors ====================

    public State getState() { return state; }
    public String getFailureCode() { return failureCode; }
    public List<ChallengeResult> getResults() { return results; }
    public int getFramesAnalysed() { return framesAnalysed; }
    public int getMultiFaceFrames() { return multiFaceFrames; }
    public int getTrackingIdChanges() { return trackingIdChanges; }

    public long getElapsedMs(long nowMs) {
        return sessionStartMs < 0 ? 0L : nowMs - sessionStartMs;
    }

    /** Snapshot of the challenge list actually used, in the order it was presented. */
    public List<Challenge> getChallengeOrder() {
        List<Challenge> order = new ArrayList<>();
        for (ChallengeResult r : results) {
            order.add(r.type);
        }
        return order;
    }
}
