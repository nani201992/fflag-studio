package com.kizu.fflagstudio;

import org.json.JSONObject;

/** JNI bridge to the Rust flag engine (libfflagcore.so). */
final class Core {
    static {
        System.loadLibrary("fflagcore");
    }

    private Core() {}

    private static native String normalize(String json);
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
