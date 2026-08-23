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
    public long overallTimeoutMs = 45_000L;
    public long perChallengeTimeoutMs = 15_000L;
    public long faceSearchTimeoutMs = 20_000L;

    public int maxImageDimension = 720;
    public int maxImageBytes = 200 * 1024;
    public int jpegQuality = 85;
    public boolean cropToFace = true;

    public boolean includeFullFrame = false;
    public boolean includeChallengeFrames = false;

    private final Map<String, String> prompts = defaultPrompts();

    public static LivenessOptions fromJson(String json) {
        LivenessOptions options = new LivenessOptions();
        options.challenges = LivenessDetector.randomChallenges(2);

        if (json == null || json.isEmpty()) {
            return options;
        }

        try {
            JSONObject root = new JSONObject(json);

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
                options.challenges = LivenessDetector.randomChallenges(root.getInt("challengeCount"));
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

        return options;
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
        defaults.put("hold", "Hold still and look at the camera");
        defaults.put("success", "Done");
        defaults.put("failed", "Liveness check failed");
        defaults.put("hint", "Hold your phone at eye level in even lighting");
        return defaults;
    }
}
