package com.nfcdocumentreader;

import android.util.Log;
import android.view.View;

import org.json.JSONObject;

import java.util.Map;

/**
 * The text every plugin screen shows, supplied by the host application.
 *
 * The bundle arrives complete: resolveLabels in NfcDocumentReader.js fills every key from its
 * English defaults before the call crosses the bridge, so there are no defaults here and no
 * fallback chain. That is deliberate. A second copy of this text in Java, and a third in Swift,
 * would drift — this project has already watched smaller strings drift between the platforms — and
 * a label that drifts in a language nobody on the team reads goes unnoticed until a customer
 * reports it.
 *
 * A key that is missing renders as the key itself rather than as English. Silent English in an
 * Arabic screen is the failure that reaches production; a visible {@code mrz.title} is the one that
 * gets fixed on the first run.
 *
 * Mirrors Labels.swift.
 */
final class Labels {

    private static final String TAG = "Labels";

    private final JSONObject values;
    private final boolean rightToLeft;

    private Labels(JSONObject values, boolean rightToLeft) {
        this.values = values != null ? values : new JSONObject();
        this.rightToLeft = rightToLeft;
    }

    /** An empty bundle. Every key renders as itself, which is what a missing bundle should look like. */
    static Labels empty() {
        return new Labels(null, false);
    }

    /**
     * @param json      the bundle as the plugin received it, serialised for the Intent
     * @param direction "rtl" or anything else
     */
    static Labels fromJson(String json, String direction) {
        JSONObject parsed = null;
        if (json != null && !json.isEmpty()) {
            try {
                parsed = new JSONObject(json);
            } catch (Exception malformed) {
                Log.w(TAG, "Labels could not be parsed; keys will render as themselves: "
                        + malformed.getClass().getSimpleName());
            }
        }
        return new Labels(parsed, "rtl".equals(direction));
    }

    /**
     * Right-to-left layout, set by the caller rather than guessed from the characters in the
     * bundle. Translating without it produces a screen that reads correctly and behaves backwards:
     * the back arrow, the step counter and the buttons all sit where the reader finishes rather
     * than where they start.
     */
    boolean isRightToLeft() {
        return rightToLeft;
    }

    /** The text for a key. */
    String text(String key) {
        String value = values.optString(key, null);
        return value != null && !value.isEmpty() ? value : key;
    }

    /**
     * The text for a key with <code>{name}</code> placeholders replaced.
     *
     * A translation may reorder the placeholders — Arabic frequently does — so they are matched by
     * name and not by position. One that a translation dropped simply does not appear, which is the
     * translator's decision to make; one it invented is left in place, visibly, rather than blanked.
     */
    String text(String key, Map<String, String> substitutions) {
        String out = text(key);
        if (substitutions != null) {
            for (Map.Entry<String, String> entry : substitutions.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return out;
    }

    /**
     * Applies the reading direction to a screen. Android mirrors the whole subtree from here, so
     * one call on the root view is enough for layouts, padding and system controls alike.
     */
    void applyDirection(View root) {
        if (root == null) return;
        root.setLayoutDirection(rightToLeft ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }
}
