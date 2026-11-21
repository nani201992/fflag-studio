package com.kizu.fflagstudio;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Self-update from the repo's latest release. The APK is downloaded with the normal network
 * stack, handed to the shell as base64 (stdin is text), then installed with `pm install -r`
 * as uid 2000 — no "unknown sources" prompt and no FileProvider.
 */
final class Updater {
    static final String API =
            "https://api.github.com/repos/nani201992/fflag-studio/releases/latest";
    private static final String TMP = "/data/local/tmp/fflag-update.apk";

    static final class Release {
        final String tag;
        final String apkUrl;

        Release(String tag, String apkUrl) {
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    private Updater() {}

    static Release latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "fflag-studio");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("github: HTTP " + code);
            JSONObject json = new JSONObject(text(c.getInputStream()));
            JSONArray assets = json.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").endsWith(".apk")) {
                    return new Release(json.getString("tag_name"),
                            asset.getString("browser_download_url"));
                }
            }
            throw new IllegalStateException("latest release has no .apk asset");
        } finally {
            c.disconnect();
        }
    }

    /** "v0.10" is newer than "0.9": compare numbers, not strings. */
    static boolean newer(String tag, String current) {
        int[] latest = numbers(tag);
        int[] mine = numbers(current);
        for (int i = 0; i < Math.max(latest.length, mine.length); i++) {
            int a = i < latest.length ? latest[i] : 0;
            int b = i < mine.length ? mine[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] numbers(String version) {
        String[] parts = version.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Downloads and installs; returns the installer output ("Success"). */
    static String install(String apkUrl) throws Exception {
        byte[] apk = bytes(new URL(apkUrl).openStream());
        if (apk.length < 10_000) {
            throw new IllegalStateException("download too small: " + apk.length + " bytes");
        }
        ShizukuShell.runWithInput("base64 -d > " + TMP, Base64.encodeToString(apk, Base64.NO_WRAP));
        String size = ShizukuShell.run("stat -c %s " + TMP).trim();
        if (!size.equals(String.valueOf(apk.length))) {
            ShizukuShell.run("rm -f " + TMP);
            throw new IllegalStateException("download truncated: " + size + " of " + apk.length);
        }
        // one shell command: replacing our own package kills this process before we could
        // clean up, but the remote shell (uid 2000) keeps running
        String out = ShizukuShell.run("out=$(pm install -r " + TMP
                + " 2>&1); rm -f " + TMP + "; printf '%s' \"$out\"");
        if (!out.contains("Success")) throw new IllegalStateException(out.trim());
        return out.trim();
    }

    private static String text(InputStream in) throws Exception {
        return new String(bytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(InputStream in) throws Exception {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
