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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * "Ask AI" backend. Endpoint, model and key are the user's own (entered once, kept in private
 * prefs) — an API key shipped inside an APK is readable by anyone who unzips it, so none is bundled.
 *
 * The model is grounded: a fixed brief on how FastFlags actually work, plus the slice of the
 * user's current set that matches the question, and an explicit instruction to say "not sure"
 * rather than invent a flag.
 */
final class Ai {
    /** OpenCode Zen; any OpenAI-compatible base URL works instead. */
    static final String DEFAULT_ENDPOINT = "https://opencode.ai/zen/v1";
    static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    private static final int MAX_TOKENS = 900;
    private static final int MAX_MODELS = 400;

    static final int PER_HOUR = 20;
    static final int PER_DAY = 100;
    private static final long MIN_GAP_MS = 5000L;
    private static final String PREFS = "fflagstudio";
    private static final String KEY = "ai_key";

    private static final String SYSTEM =
            "You are the FastFlag assistant inside FFlag Studio, an Android editor for Roblox's\n"
            + "FastFlag file. Reply in the user's language, short and concrete: what is known, then\n"
            + "what to try.\n"
            + "\n"
            + "HOW FASTFLAGS WORK — ground truth, never contradict it:\n"
            + "- The Android client reads /data/local/tmp/ClientAppSettings.json once at startup; a\n"
            + "  change takes effect only after that client restarts. FFlag Studio writes the file and\n"
            + "  restarts the chosen client.\n"
            + "- The file is one flat JSON object, key to value. Nothing nests; an empty object means\n"
            + "  no overrides at all.\n"
            + "- A key is prefix + base name, and the prefix decides the type:\n"
            + "  FFlag / DFFlag = boolean, FInt / DFInt = integer, FString / DFString = string,\n"
            + "  FLog / DFLog = integer log level.\n"
            + "- The first letter is the lifetime: F* is read once at startup, DF* can be re-read while\n"
            + "  running, SF* is server-synchronised and normally cannot be set client-side.\n"
            + "- The client looks up \"prefix + base name\". A key with no prefix (\"SomeName\") never\n"
            + "  applies. FFlagX and DFFlagX are different keys to the client.\n"
            + "- Values are typed, not scored: 60 and \"60\" are the same integer for an Int key; true and\n"
            + "  \"true\" the same for a Boolean. A large value is not automatically bad and a small one\n"
            + "  is not automatically safe.\n"
            + "- Deleting a key restores the engine default. There is no universal \"off\" value: for a\n"
            + "  boolean, false is a value, not a removal.\n"
            + "- Roblox and Roblox VNG read the same file; the app's target picker only chooses which\n"
            + "  client gets restarted.\n"
            + "- Roblox's public Android cloud config and the strings inside libroblox.so are NOT\n"
            + "  validity lists: a flag can work without appearing in either, and appearing proves\n"
            + "  nothing about what it does.\n"
            + "- Flags are hints to the client's own code. An undocumented flag can do nothing, do\n"
            + "  something unrelated to its name, or crash the client. Enforcement is server-side as\n"
            + "  well: a client flag cannot make the server accept something it refuses.\n"
            + "- FFlag Studio's engine checks format only (prefix, type, name). A key that passes proves\n"
            + "  it is well-formed, not that the flag exists or does anything.\n"
            + "\n"
            + "HOW TO ANSWER:\n"
            + "- Never invent a flag name, an effect, a default value or a recommended value. If you are\n"
            + "  not sure, answer \"not sure\", say what you do know, and say what would confirm it.\n"
            + "- Keep documented facts and community reports apart; label a guess as a guess instead of\n"
            + "  dressing it up as fact.\n"
            + "- For each flag you propose, give: the claimed effect, the risk, and how the user can\n"
            + "  tell whether it worked. Recommend changing one thing at a time, and say that deleting\n"
            + "  the key is the undo.\n"
            + "- Never claim a flag is \"safe\", \"undetectable\" or \"bannable\" without evidence.\n"
            + "- When you propose flags, put them in one fenced json block containing only keys you can\n"
            + "  justify, with values typed for their prefix.\n"
            + "- \"I don't know\" is a correct answer. Guessing is not.";

    private Ai() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String key(Context c) {
        return prefs(c).getString(KEY, "");
    }

    static String endpoint(Context c) {
        String v = prefs(c).getString("ai_endpoint", DEFAULT_ENDPOINT);
        return v == null || v.trim().isEmpty() ? DEFAULT_ENDPOINT : v.trim();
    }

    static String model(Context c) {
        String v = prefs(c).getString("ai_model", DEFAULT_MODEL);
        return v == null || v.trim().isEmpty() ? DEFAULT_MODEL : v.trim();
    }

    static void saveSettings(Context c, String endpoint, String model, String key) {
        prefs(c).edit()
                .putString("ai_endpoint", endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim())
                .putString("ai_model", model.trim().isEmpty() ? DEFAULT_MODEL : model.trim())
                .putString(KEY, key.trim())
                .apply();
    }

    /** Opencode needs a key; a local OpenAI-compatible server usually does not. */
    static boolean ready(Context c) {
        return !key(c).isEmpty() || !chatUrl(endpoint(c)).equals(chatUrl(DEFAULT_ENDPOINT));
    }

    static boolean looksLikeKey(String value) {
        String k = value.trim();
        return k.isEmpty() || !k.contains(" ");
    }

    /** Accepts a base URL, a /v1 URL or a full chat-completions URL; returns the latter. */
    static String chatUrl(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.isEmpty()) b = DEFAULT_ENDPOINT;
        if (b.endsWith("/chat/completions")) return b;
        if (b.endsWith("/v1") || b.contains("/v1/")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }

    static String modelsUrl(String base) {
        String chat = chatUrl(base);
        return chat.substring(0, chat.length() - "/chat/completions".length()) + "/models";
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
        if (!ready(c)) return "add your API key in Setup first";
        SharedPreferences p = prefs(c);
        long last = p.getLong("ai_last", 0);
        if (now - last < MIN_GAP_MS) {
            return "one question every " + (MIN_GAP_MS / 1000) + " s — wait a moment";
        }
        if (countHour(p, now) >= PER_HOUR) return "hourly limit reached (" + PER_HOUR + ")";
        if (countToday(p, now) >= PER_DAY) return "daily limit reached (" + PER_DAY + ")";
        return null;
    }

    static int leftToday(Context c, long now) {
        return Math.max(0, PER_DAY - countToday(prefs(c), now));
    }

    private static void record(Context c, long now) {
        SharedPreferences p = prefs(c);
        p.edit()
                .putString("ai_day", day(now))
                .putInt("ai_day_count", countToday(p, now) + 1)
                .putLong("ai_hour", now / 3600000L)
                .putInt("ai_hour_count", countHour(p, now) + 1)
                .putLong("ai_last", now)
                .apply();
    }

    /** @return {status code, body}; a null body sends GET, otherwise a JSON POST. */
    private static String[] request(String url, String body, String apiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setRequestMethod(body == null ? "GET" : "POST");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "fflag-studio");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
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
            JSONObject json = new JSONObject(body);
            Object err = json.opt("error");
            if (err instanceof JSONObject) {
                return ((JSONObject) err).optString("message", body);
            }
            if (err instanceof String) return (String) err;
            return json.optString("message", body);
        } catch (Throwable ignored) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private static String explain(String code, String body) {
        String detail = apiError(body);
        if ("401".equals(code) || "403".equals(code)) {
            return "API key rejected (" + code + ") — replace it in Setup\n" + detail;
        }
        if ("402".equals(code)) return "no credits on this key — top up or pick another model\n" + detail;
        if ("404".equals(code)) return "endpoint or model not found (404) — check Setup\n" + detail;
        if ("429".equals(code)) return "rate limited by the API — try again later\n" + detail;
        return "HTTP " + code + ": " + detail;
    }

    /** Model ids the endpoint advertises (OpenAI's `data[].id`, or a plain list). */
    static List<String> models(String endpoint, String apiKey) throws Exception {
        String[] res = request(modelsUrl(endpoint), null, apiKey);
        if (!"200".equals(res[0])) throw new IllegalStateException(explain(res[0], res[1]));
        JSONObject json = new JSONObject(res[1]);
        JSONArray arr = json.optJSONArray("data");
        if (arr == null) arr = json.optJSONArray("models");
        if (arr == null) throw new IllegalStateException("this endpoint returned no model list");
        TreeSet<String> ids = new TreeSet<>();
        for (int i = 0; i < arr.length(); i++) {
            Object entry = arr.get(i);
            String id = entry instanceof JSONObject
                    ? ((JSONObject) entry).optString("id",
                            ((JSONObject) entry).optString("name", ""))
                    : String.valueOf(entry);
            if (!id.isEmpty()) ids.add(id);
        }
        // shortcut: first 400 ids only; the model field still accepts any id typed by hand
        return new ArrayList<>(ids).subList(0, Math.min(ids.size(), MAX_MODELS));
    }

    /**
     * @param question what the user typed
     * @param context  a short description of their current set (see MainActivity.flagContext)
     * @return the answer text
     */
    static String ask(Context c, String question, String context) throws Exception {
        String model = model(c);
        String chat = chatUrl(endpoint(c));
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system")
                .put("content", SYSTEM + "\n\nWhat the user has right now:\n" + context));
        messages.put(new JSONObject().put("role", "user").put("content", question));

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", MAX_TOKENS)
                .put("temperature", 0.2)
                .put("messages", messages);

        String[] res = request(chat, body.toString(), key(c));
        // opencode zen: no credits on the paid model still answers from its free twin
        if ("402".equals(res[0]) && chat.equals(chatUrl(DEFAULT_ENDPOINT))
                && DEFAULT_MODEL.equals(model)) {
            body.put("model", DEFAULT_MODEL + "-free");
            res = request(chat, body.toString(), key(c));
        }
        if (!"200".equals(res[0])) throw new IllegalStateException(explain(res[0], res[1]));

        JSONObject json = new JSONObject(res[1]);
        JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
        String text = choice.optJSONObject("message") != null
                ? choice.getJSONObject("message").optString("content", "")
                : choice.optString("text", "");
        if (text.trim().isEmpty()) throw new IllegalStateException("empty answer — try rephrasing");

        record(c, System.currentTimeMillis());
        return text.trim();
    }
}
