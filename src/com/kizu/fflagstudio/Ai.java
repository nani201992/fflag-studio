package com.kizu.fflagstudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Ask AI" backend. The key is the user's own (entered once, kept in private prefs) — an API key
 * shipped inside an APK is readable by anyone who unzips it, so none is bundled.
 *
 * The model is grounded: a fixed knowledge block about FastFlag semantics, plus the slice of the
 * user's current set that matches the question, and an explicit instruction to say "not sure"
 * rather than invent flag names.
 */
final class Ai {
    private static final String API = "https://opencode.ai/zen/v1/chat/completions";
    static final String MODEL = "deepseek-v4.1-flash";
    private static final String MODEL_FREE = "deepseek-v4.1-flash-free";
    private static final int MAX_TOKENS = 900;

    static final int PER_HOUR = 8;
    static final int PER_DAY = 30;
    private static final long MIN_GAP_MS = 5000L;
    private static final String PREFS = "fflagstudio";
    private static final String KEY = "ai_key";

    private static final String SYSTEM =
            "You are the FastFlag assistant inside FFlag Studio, an Android editor for Roblox's\n"
            + "FastFlag file. Reply in the user's language, short and concrete.\n"
            + "\n"
            + "Ground truth — do not contradict it:\n"
            + "- The Android client reads /data/local/tmp/ClientAppSettings.json once at startup;\n"
            + "  a change only takes effect after the client restarts.\n"
            + "- A key is prefix + base name. FFlag/DFFlag = boolean, FInt/DFInt = integer,\n"
            + "  FString/DFString = string, FLog/DFLog = integer log level. F = read once at startup,\n"
            + "  DF = can be re-read at runtime, SF = server-synchronised. \"DFIntX\": \"60\" and 60\n"
            + "  are the same value; the prefix decides the type.\n"
            + "- A key without a prefix never applies: the client looks up prefix + base name.\n"
            + "- Roblox's public Android cloud config and the strings inside libroblox.so are NOT\n"
            + "  validity lists: a flag can work without appearing in either, and appearing proves\n"
            + "  nothing about what it does.\n"
            + "- Values are typed, not scored. A high FPS cap is not automatically bad and a low one\n"
            + "  is not automatically safe.\n"
            + "- Deleting a key restores the engine default. There is no universal \"disable\" value.\n"
            + "- Roblox and Roblox VNG read the same file; the app's target picker only chooses which\n"
            + "  client gets restarted.\n"
            + "- Flags are hints to the client's own code. An undocumented flag can do nothing, do\n"
            + "  something unrelated to its name, or crash the client.\n"
            + "\n"
            + "Rules:\n"
            + "- Never invent flag names, effects or default values. If you are not sure that a flag\n"
            + "  exists or what it does, say you are not sure and what would confirm it.\n"
            + "- Explain what is known before what is guessed, and separate the two.\n"
            + "- If you propose flags, give the reason and the risk, and put them in one fenced json\n"
            + "  block containing only keys you are confident about.\n"
            + "- Never claim a flag is safe, undetectable or bannable without evidence.";

    private Ai() {
    }

    static String key(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
    }

    static void saveKey(Context c, String value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, value.trim()).apply();
    }

    static void forgetKey(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    /** True when the string looks like a key this endpoint accepts. */
    static boolean looksLikeKey(String value) {
        String k = value.trim();
        return k.length() > 20 && !k.contains(" ");
    }

    private static String day(long now) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
    }

    private static int countToday(SharedPreferences p, long now) {
        return day(now).equals(p.getString("ai_day", "")) ? p.getInt("ai_day_count", 0) : 0;
    }

    private static int countHour(SharedPreferences p, long now) {
        long hour = now / 3600000L;
        return hour == p.getLong("ai_hour", -1) ? p.getInt("ai_hour_count", 0) : 0;
    }

    /** Null when a request may go ahead, otherwise why it may not. */
    static String blocked(Context c, long now) {
        if (key(c).isEmpty()) return "add your own API key first";
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong("ai_last", 0);
        if (now - last < MIN_GAP_MS) {
            return "one question every " + (MIN_GAP_MS / 1000) + " s — wait a moment";
        }
        if (countHour(p, now) >= PER_HOUR) return "hourly limit reached (" + PER_HOUR + ")";
        if (countToday(p, now) >= PER_DAY) return "daily limit reached (" + PER_DAY + ")";
        return null;
    }

    static int leftToday(Context c, long now) {
        return Math.max(0, PER_DAY - countToday(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE), now));
    }

    private static void record(Context c, long now) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putString("ai_day", day(now))
                .putInt("ai_day_count", countToday(p, now) + 1)
                .putLong("ai_hour", now / 3600000L)
                .putInt("ai_hour_count", countHour(p, now) + 1)
                .putLong("ai_last", now)
                .apply();
    }

    /** @return {status code, body} */
    private static String[] send(JSONObject body, String apiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("User-Agent", "fflag-studio");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : read(in);
        c.disconnect();
        return new String[]{String.valueOf(code), text};
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String apiError(String body) {
        try {
            return new JSONObject(body).getJSONObject("error").optString("message", body);
        } catch (Throwable ignored) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private static String explain(String code, String body) {
        String detail = apiError(body);
        if ("401".equals(code) || "403".equals(code)) {
            return "API key rejected (" + code + ") — replace it in Key…\n" + detail;
        }
        if ("402".equals(code)) return "no credits on this key — top up or use a free model\n" + detail;
        if ("429".equals(code)) return "rate limited by the API — try again later\n" + detail;
        return "HTTP " + code + ": " + detail;
    }

    /**
     * @param question what the user typed
     * @param context  a short description of their current set (see MainActivity.flagContext)
     * @return the answer text
     */
    static String ask(Context c, String question, String context) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system")
                .put("content", SYSTEM + "\n\nWhat the user has right now:\n" + context));
        messages.put(new JSONObject().put("role", "user").put("content", question));

        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("max_tokens", MAX_TOKENS)
                .put("temperature", 0.2)
                .put("messages", messages);

        long start = System.currentTimeMillis();
        String[] res = send(body, key(c));
        if ("402".equals(res[0])) {
            // no credits on the paid model: the free tier of the same model still answers
            body.put("model", MODEL_FREE);
            res = send(body, key(c));
        }
        if (!"200".equals(res[0])) throw new IllegalStateException(explain(res[0], res[1]));

        JSONObject json = new JSONObject(res[1]);
        String text = json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
        if (text.trim().isEmpty()) throw new IllegalStateException("empty answer — try rephrasing");

        record(c, System.currentTimeMillis() > start ? System.currentTimeMillis() : start);
        return text.trim();
    }
}
