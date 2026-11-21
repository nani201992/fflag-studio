package com.kizu.fflagstudio;

import org.json.JSONObject;

/** JNI bridge to the Rust flag engine (libfflagcore.so). */
final class Core {
    static {
        System.loadLibrary("fflagcore");
    }

    private Core() {}

    private static native String normalize(String json);
    private static native String importFlags(String json);
    private static native String optimizeFlags(String json, String tier);
    private static native String normalizeOne(String key, String value);
    private static native String preset(String name);

    /** @return canonical JSON object, or throws with the engine's message. */
    static String canonical(String json) {
        return unwrap(normalize(json));
    }

    /** @return a one-key canonical JSON object, or throws. */
    static String one(String key, String value) {
        return unwrap(normalizeOne(key, value));
    }

    static String presetJson(String name) {
        return unwrap(preset(name));
    }

    /**
     * Lenient import: keeps every valid flag, drops bare names that have a prefixed twin,
     * expands orphan bare names to both F and DF variants, and reports all of it.
     *
     * @return {"flags":{…},"aliased":[…],"inferred":[…],"skipped":[…]}
     */
    static JSONObject importJson(String json) {
        try {
            return new JSONObject(unwrap(importFlags(json)));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("engine reply unreadable");
        }
    }

    /**
     * Fits a flag set to the device tier: caps values only a desktop can carry, drops
     * PC-only keys, and switches off the heaviest features on the low tier.
     *
     * @return {"flags":{…},"changes":[…],"removed":[…]}
     */
    static JSONObject optimizeJson(String flags, String tier) {
        try {
            return new JSONObject(unwrap(optimizeFlags(flags, tier)));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("engine reply unreadable");
        }
    }

    private static String unwrap(String reply) {
        try {
            JSONObject r = new JSONObject(reply);
            if (!r.getBoolean("ok")) throw new IllegalArgumentException(r.getString("result"));
            return r.getString("result");
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("engine reply unreadable: " + reply);
        }
    }
}
