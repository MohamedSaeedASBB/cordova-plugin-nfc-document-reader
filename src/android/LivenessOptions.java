package com.nfcdocumentreader;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code checkLiveness} options, with defaults applied.
 *
 * Prompt strings are overridable so the host app can supply its own copy (including Arabic)
 * without the plugin shipping a localisation bundle. Unknown keys are ignored rather than
 * rejected, so the JS side can add options without breaking older native builds.
 */
public class LivenessOptions {

    private static final String TAG = "LivenessOptions";

    public List<LivenessDetector.Challenge> challenges;
    /**
     * Zero means "work it out from the challenge list" — see resolveOverallTimeout. A caller's
     * explicit value always wins.
     */
    public long overallTimeoutMs = 0L;
    public long perChallengeTimeoutMs = 15_000L;
    public long faceSearchTimeoutMs = 20_000L;

    public int maxImageDimension = 720;
    public int maxImageBytes = 200 * 1024;
    public int jpegQuality = 85;
    public boolean cropToFace = true;

    public boolean includeFullFrame = false;
    public boolean includeChallengeFrames = false;

    /**
     * How many challenges a session asks for when the caller does not say.
     *
     * Four, which with the compound challenges off is every single-action challenge there is —
     * blink, smile, turn left, turn right — in a random order. Four actions is materially harder to
     * pre-record than two while still being something a customer can do without being coached.
     */
    public static final int DEFAULT_CHALLENGE_COUNT = 4;

    /**
     * Widen the challenge pool to include the compound ones (a head turn and a smile or a blink at
     * the same moment).
     *
     * Off by default. The compound challenges ask the customer to hold two things at once, their
     * thresholds are not calibrated against real faces, and a challenge a genuine customer cannot
     * pass is a failed onboarding rather than a caught fraud. They remain available to a caller who
     * opts in, and to a future pilot that calibrates them.
     */
    public boolean includeCompoundChallenges = false;

    /** How long a pose must be held. See LivenessDetector.DEFAULT_POSE_HOLD_MS. */
    public long poseHoldMs = 600L;

    /** Record the session. On by default, at the bank's request. */
    public boolean recordVideo = true;
    public int videoBitrate = 900_000;
    /**
     * Record only the challenge windows. Most of a session is the customer reading a prompt; that
     * footage is the bulk of the file and carries none of the evidence.
     */
    public boolean videoTrimToChallenges = true;

    private final Map<String, String> prompts = defaultPrompts();

    public static LivenessOptions fromJson(String json) {
        LivenessOptions options = new LivenessOptions();
        options.challenges =
                LivenessDetector.randomChallenges(DEFAULT_CHALLENGE_COUNT, false);

        if (json == null || json.isEmpty()) {
            options.overallTimeoutMs = options.resolveOverallTimeout();
            return options;
        }

        try {
            JSONObject root = new JSONObject(json);

            // Read before the challenge block below, because it decides what the random pool holds.
            options.includeCompoundChallenges =
                    root.optBoolean("includeCompoundChallenges", options.includeCompoundChallenges);
            options.poseHoldMs = root.optLong("poseHoldMs", options.poseHoldMs);

            // ---- Video ----
            options.recordVideo = root.optBoolean("recordVideo", options.recordVideo);
            options.videoBitrate = root.optInt("videoBitrate", options.videoBitrate);
            options.videoTrimToChallenges =
                    root.optBoolean("videoTrimToChallenges", options.videoTrimToChallenges);

            // ---- Challenges ----
            // An explicit list is honoured as given; otherwise a random subset is used, which is
            // what stops an attacker pre-recording the expected actions in the expected order.
            if (root.has("challenges") && !root.isNull("challenges")) {
                JSONArray array = root.getJSONArray("challenges");
                List<LivenessDetector.Challenge> parsed = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    LivenessDetector.Challenge challenge = parseChallenge(array.getString(i));
                    if (challenge != null && !parsed.contains(challenge)) {
                        parsed.add(challenge);
                    }
                }
                if (!parsed.isEmpty()) {
                    options.challenges = parsed;
                }
            } else if (root.has("challengeCount")) {
                options.challenges = LivenessDetector.randomChallenges(
                        root.getInt("challengeCount"), options.includeCompoundChallenges);
            } else {
                // Default: four, drawn at random. With the compound challenges off that is every
                // single-action challenge there is, and the order still varies — so a recording of
                // one session does not predict the next.
                options.challenges = LivenessDetector.randomChallenges(
                        DEFAULT_CHALLENGE_COUNT, options.includeCompoundChallenges);
            }

            // ---- Timeouts ----
            options.overallTimeoutMs = root.optLong("overallTimeoutMs", options.overallTimeoutMs);
            options.perChallengeTimeoutMs =
                    root.optLong("perChallengeTimeoutMs", options.perChallengeTimeoutMs);
            options.faceSearchTimeoutMs =
                    root.optLong("faceSearchTimeoutMs", options.faceSearchTimeoutMs);

            // ---- Image output ----
            options.maxImageDimension = root.optInt("maxImageDimension", options.maxImageDimension);
            options.maxImageBytes = root.optInt("maxImageBytes", options.maxImageBytes);
            options.jpegQuality = root.optInt("jpegQuality", options.jpegQuality);
            options.cropToFace = root.optBoolean("cropToFace", options.cropToFace);
            options.includeFullFrame = root.optBoolean("includeFullFrame", options.includeFullFrame);
            options.includeChallengeFrames =
                    root.optBoolean("includeChallengeFrames", options.includeChallengeFrames);

            // ---- Prompt overrides ----
            if (root.has("prompts") && !root.isNull("prompts")) {
                JSONObject overrides = root.getJSONObject("prompts");
                for (String key : options.prompts.keySet()) {
                    if (overrides.has(key)) {
                        options.prompts.put(key, overrides.getString(key));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not parse liveness options, using defaults: " + e.getMessage());
        }

        options.overallTimeoutMs = options.resolveOverallTimeout();
        return options;
    }

    /**
     * The session ceiling, derived from the work the session actually has to do.
     *
     * A fixed 45 seconds was fine for two challenges and is wrong for eight: the per-challenge
     * timeouts alone can exceed it, so the overall timer would fire while the customer was still
     * being asked for challenge five and report CHALLENGE_TIMEOUT for something they had not been
     * given a chance to do. This is a ceiling, not an expected duration — a customer who follows
     * the prompts finishes in a fraction of it.
     */
    long resolveOverallTimeout() {
        if (overallTimeoutMs > 0) return overallTimeoutMs;     // an explicit value always wins
        int count = challenges != null ? challenges.size() : 1;
        return faceSearchTimeoutMs + (long) count * perChallengeTimeoutMs + 5_000L;
    }

    public LivenessVideoRecorder.Options videoOptions() {
        LivenessVideoRecorder.Options video = new LivenessVideoRecorder.Options();
        video.bitrate = videoBitrate;
        video.trimToChallenges = videoTrimToChallenges;
        return video;
    }

    public ImageCompressor.Options imageOptions() {
        ImageCompressor.Options imageOptions = new ImageCompressor.Options();
        imageOptions.maxDimension = maxImageDimension;
        imageOptions.maxBytes = maxImageBytes;
        imageOptions.initialQuality = jpegQuality;
        imageOptions.cropToFace = cropToFace;
        return imageOptions;
    }

    public String prompt(String key) {
        String value = prompts.get(key);
        return value != null ? value : "";
    }

    private static LivenessDetector.Challenge parseChallenge(String name) {
        if (name == null) return null;
        switch (name.trim()) {
            case "blink": return LivenessDetector.Challenge.BLINK;
            case "smile": return LivenessDetector.Challenge.SMILE;
            case "turnLeft": return LivenessDetector.Challenge.TURN_LEFT;
            case "turnRight": return LivenessDetector.Challenge.TURN_RIGHT;
            case "turnLeftSmile": return LivenessDetector.Challenge.TURN_LEFT_SMILE;
            case "turnRightSmile": return LivenessDetector.Challenge.TURN_RIGHT_SMILE;
            case "turnLeftBlink": return LivenessDetector.Challenge.TURN_LEFT_BLINK;
            case "turnRightBlink": return LivenessDetector.Challenge.TURN_RIGHT_BLINK;
            default:
                Log.w(TAG, "Unknown challenge ignored: " + name);
                return null;
        }
    }

    private static Map<String, String> defaultPrompts() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("findFace", "Position your face inside the oval");
        defaults.put("center", "Centre your face in the oval");
        defaults.put("tooFar", "Move a little closer");
        defaults.put("tooClose", "Move a little further away");
        defaults.put("multipleFaces", "Only one person should be in frame");
        defaults.put("blink", "Blink slowly");
        defaults.put("smile", "Smile");
        defaults.put("turnLeft", "Slowly turn your head to your left");
        defaults.put("turnRight", "Slowly turn your head to your right");
        defaults.put("turnLeftSmile", "Turn your head to your left and smile");
        defaults.put("turnRightSmile", "Turn your head to your right and smile");
        defaults.put("turnLeftBlink", "Turn your head to your left, then blink");
        defaults.put("turnRightBlink", "Turn your head to your right, then blink");
        defaults.put("hold", "Hold still and look at the camera");
        defaults.put("success", "Done");
        defaults.put("failed", "Liveness check failed");
        defaults.put("hint", "Hold your phone at eye level in even lighting");
        return defaults;
    }
}
