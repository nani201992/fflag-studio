package com.kizu.fflagstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements FlagAdapter.Host {
    private static final String FLAG_FILE = "/data/local/tmp/ClientAppSettings.json";
    private static final String[] PKGS = {"com.roblox.client", "com.roblox.client.vnggames"};
    private static final int REQ_SHIZUKU = 42;
    private static final int REQ_JSON = 7;
    /** a flag set is a few KB; anything near this is not a flag file */
    private static final int MAX_JSON_BYTES = 512 * 1024;
    /** bump when the draft layout changes; older drafts are ignored and deleted */
    private static final int DRAFT_VERSION = 2;
    private static final String PREFS = "fflagstudio";
    private static final String PRESETS = "presets";
    /** the two clients; both read the same flag file, the pick decides what gets restarted */
    private static final String[] TARGET_KEYS = {
            "com.roblox.client", "com.roblox.client.vnggames"};

    private static final int BG = 0xFF101014, CARD = 0xFF1B1B22, TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFF9A9AA5, ACCENT = 0xFF7C4DFF, VALUE = 0xFFFFD54F;

    private final TreeMap<String, String> flags = new TreeMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView list;
    private TextView empty;
    private FlagAdapter adapter;
    private final List<String> visibleKeys = new ArrayList<>();
    private TextView status;
    private EditText search;
    private TextView count;
    private TextView tabRoblox;
    private TextView tabVng;
    private TextView updateBanner;
    private int target = 1;
    private AlertDialog jsonDialog;
    private EditText jsonBox;
    private String channelState = "";
    private DeviceProfile profile;
    private TextView footer;
    /** newest release tag when it is ahead of this build, else null */
    private String pendingUpdate;
    private String pendingUpdateUrl;
    /** device content as last read or written; null until we know it */
    private TreeMap<String, String> baseline;
    private boolean dirty;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> refreshChannel();
    private final Shizuku.OnBinderReceivedListener binderListener = this::refreshChannel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        profile = DeviceProfile.of(this);
        getWindow().setStatusBarColor(Style.BG);
        getWindow().setNavigationBarColor(Style.BG);
        setContentView(buildUi());
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        search.clearFocus();
        Shizuku.addRequestPermissionResultListener(permListener);
        // the binder arrives asynchronously when Shizuku is (re)started
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        refreshChannel();
        restoreOrLoad();
        checkUpdate(false);
        if (!Core.available()) {
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "?";
            setStatus("no flag engine for this CPU (" + abi + ") — this APK was built without that ABI");
        }
    }

    @Override
    protected void onPause() {
        // autosave only what differs from the device: a draft is "unapplied edits"
        commitFocused();
        if (dirty || baseline == null || !flags.equals(baseline)) saveDraft();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChannel();
        if (ShizukuShell.granted() && flags.isEmpty()) restoreOrLoad();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------- UI ----------

    @Override
    public int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BG);
        root.setPadding(dp(14), dp(18), dp(14), dp(6));

        root.addView(headerCard());

        GridLayout actions = new GridLayout(this);
        actions.setColumnCount(3);
        actions.setRowCount(2);
        actions.setUseDefaultMargins(false);
        String[][] labels = {{"Add", "Preset", "JSON"}, {"Clear all", "Update", "Apply"}};
        View.OnClickListener[][] clicks = {
                {v -> addDialog(), v -> presetDialog(), v -> jsonDialog()},
                {v -> clearAll(), v -> checkUpdate(true), v -> apply()}};
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 3; c++) {
                TextView b = Style.pill(this, labels[r][c], r == 1 && c == 2);
                b.setOnClickListener(clicks[r][c]);
                GridLayout.LayoutParams lp =
                        new GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(c, 1f));
                lp.width = 0;
                lp.height = dp(42);
                int m = dp(3);
                lp.setMargins(m, m, m, m);
                b.setLayoutParams(lp);
                actions.addView(b);
            }
        }
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateBanner = Style.label(this, "", 12.5f, 0xFFFFFFFF, true);
        updateBanner.setBackground(Style.ripple(this, Style.round(this, Style.ACCENT, 13), 13));
        updateBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> installUpdate());
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.topMargin = dp(6);
        root.addView(updateBanner, bannerLp);

        search = new EditText(this);
        search.setHint("Search flags");
        search.setHintTextColor(Style.DIM);
        search.setTextColor(Style.TEXT);
        search.setTextSize(13);
        search.setSingleLine(true);
        search.setIncludeFontPadding(false);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(Style.round(this, Style.SURFACE_HI, 12, Style.STROKE));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setOnEditorActionListener((v, a, e) -> {
            render();
            return false;
        });
        search.setOnKeyListener((v, code, e) -> {
            render();
            return false;
        });
        LinearLayout.LayoutParams searchLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp(12);
        root.addView(search, searchLp);

        root.addView(metaRow());

        empty = Style.label(this, "", 12.5f, Style.DIM, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(34), 0, dp(8));
        root.addView(empty);

        list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Style.BG));
        list.setDividerHeight(dp(6));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, dp(2), 0, dp(10));
        adapter = new FlagAdapter(this, this);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(6);
        root.addView(list, listLp);

        footer = Style.label(this, "", 10.5f, 0xFF6C6C7A, false);
        footer.setPadding(0, dp(6), 0, 0);
        footer.setOnClickListener(v -> {
            if (pendingUpdate != null) installUpdate();
        });
        root.addView(footer);
        footerRefresh();
        return root;
    }

    /** Logo, title, live status line and the build chip. */
    private View headerCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Style.round(this, Style.SURFACE, 18, Style.STROKE));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        card.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        texts.addView(Style.label(this, "FFlag Studio", 19, Style.TEXT, true));
        status = Style.label(this, "", 11.5f, Style.STATUS, false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(3);
        texts.addView(status, statusLp);
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = Style.label(this, "v" + versionName(), 10.5f, Style.ACCENT_TEXT, true);
        chip.setBackground(Style.round(this, Style.ACCENT_SOFT, 9));
        chip.setPadding(dp(9), dp(5), dp(9), dp(5));
        card.addView(chip);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        card.setLayoutParams(lp);
        return card;
    }

    /** apply-to segmented control on the left, visible/total counter on the right. */
    private View metaRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(2));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        target = prefs.getInt("target", 1);
        if (target < 1 || target > 2) target = 1;

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Style.round(this, Style.SURFACE_HI, 11, Style.STROKE));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabRoblox = tab("Roblox", 1);
        tabVng = tab("VNG", 2);
        seg.addView(tabRoblox);
        seg.addView(tabVng);
        row.addView(seg);

        count = Style.label(this, "", 11, Style.DIM, false);
        count.setGravity(Gravity.END);
        row.addView(count, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        styleTabs();
        return row;
    }

    private TextView tab(String text, int which) {
        TextView t = Style.label(this, text, 11.5f, Style.DIM, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(15), dp(6), dp(15), dp(6));
        t.setOnClickListener(v -> {
            target = which;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("target", which).apply();
            styleTabs();
        });
        return t;
    }

    private void styleTabs() {
        TextView[] tabs = {tabRoblox, tabVng};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = target == i + 1;
            tabs[i].setTextColor(on ? 0xFFFFFFFF : Style.DIM);
            tabs[i].setBackground(Style.round(this, on ? Style.ACCENT : 0x00000000, 9));
        }
    }

    /** Shown in the footer and in every failure report, so a screenshot identifies the build. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** flags survive a restart even if they were never applied */
    private File draftFile() {
        return new File(getFilesDir(), "draft.json");
    }

    private void saveDraft() {
        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("v", DRAFT_VERSION);
            wrapper.put("flags", toJsonObject(flags));
            java.nio.file.Files.write(draftFile().toPath(),
                    wrapper.toString().getBytes(StandardCharsets.UTF_8));
            dirty = true;
        } catch (Throwable ignored) {
        }
    }

    private void deleteDraft() {
        try {
            //noinspection ResultOfMethodCallIgnored
            draftFile().delete();
        } catch (Throwable ignored) {
        }
        dirty = false;
    }

    private TreeMap<String, String> readDraft() {
        File draft = draftFile();
        if (!draft.isFile()) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(draft.toPath()),
                    StandardCharsets.UTF_8);
            JSONObject wrapper = new JSONObject(text);
            if (wrapper.optInt("v", 0) != DRAFT_VERSION) {
                // stale layout (e.g. an empty snapshot from an older build): fall back to the device
                deleteDraft();
                return null;
            }
            JSONObject obj = wrapper.getJSONObject("flags");
            TreeMap<String, String> loaded = new TreeMap<>();
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String k = it.next();
                loaded.put(k, display(obj.get(k)));
            }
            return loaded;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Draft first (unsaved work), device second (first run). */
    private void restoreOrLoad() {
        TreeMap<String, String> draft = readDraft();
        if (draft != null) {
            flags.clear();
            flags.putAll(draft);
            render();
            setStatus("restored " + draft.size() + " saved flags — Apply to write");
            return;
        }
        load();
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void refreshChannel() {
        final boolean alive = ShizukuShell.alive();
        final boolean granted = ShizukuShell.granted();
        io.execute(() -> {
            String text;
            if (!alive) {
                text = "Shizuku not running — start it once, then reopen";
            } else if (!granted) {
                text = "Shizuku privilege not granted — press Apply to grant";
            } else {
                text = "channel: Shizuku · uid " + ShizukuShell.uid() + " · " + flags.size() + " flags";
            }
            // only report state changes, so a resume does not wipe an action's status
            final String state = alive + "|" + granted;
            ui.post(() -> {
                if (state.equals(channelState)) return;
                channelState = state;
                setStatus(text);
            });
        });
    }

    // ---------- rows ----------

    private void render() {
        commitFocused();
        String needle = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        visibleKeys.clear();
        for (String key : flags.keySet()) {
            if (needle.isEmpty() || key.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                visibleKeys.add(key);
            }
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(visibleKeys.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(flags.isEmpty()
                ? "No flags yet.\nUse Add, Preset or JSON to start."
                : "No flag matches that search.");
        if (count != null) count.setText(visibleKeys.size() + " / " + flags.size());
    }

    // ---- FlagAdapter.Host ----

    @Override
    public int flagCount() {
        return visibleKeys.size();
    }

    @Override
    public String flagKeyAt(int position) {
        return visibleKeys.get(position);
    }

    @Override
    public String flagValue(String key) {
        String value = flags.get(key);
        return value == null ? "" : value;
    }

    @Override
    public void onRowValue(String key, String value) {
        flags.put(key, value);
        saveDraft();
    }

    @Override
    public void onDeleteFlag(String key) {
        commitFocused();
        flags.remove(key);
        render();
        saveDraft();
    }

    private void commitFocused() {
        View focused = getCurrentFocus();
        if (!(focused instanceof EditText)) return;
        Object tag = focused.getTag();
        if (tag instanceof String) {
            flags.put((String) tag, ((EditText) focused).getText().toString().trim());
        }
    }

    // ---------- device ----------

    private void load() {
        io.execute(() -> {
            try {
                if (!ShizukuShell.granted()) return;
                String text = ShizukuShell.run("cat " + FLAG_FILE + " 2>/dev/null || true").trim();
                if (text.isEmpty()) {
                    ui.post(() -> {
                        flags.clear();
                        baseline = new TreeMap<>();
                        deleteDraft();
                        render();
                        setStatus("no flags on device — start editing");
                    });
                    return;
                }
                JSONObject obj = new JSONObject(text);
                TreeMap<String, String> loaded = new TreeMap<>();
                for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                    String k = it.next();
                    loaded.put(k, display(obj.get(k)));
                }
                ui.post(() -> {
                    flags.clear();
                    flags.putAll(loaded);
                    baseline = new TreeMap<>(loaded);
                    deleteDraft();
                    render();
                    setStatus(loaded.size() + " flags on device");
                });
            } catch (Throwable t) {
                ui.post(() -> setStatus("read failed: " + brief(t)));
            }
        });
    }

    private static String display(Object v) {
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        return String.valueOf(v);
    }

    /** Writes the flag file through the shell's stdin: no quoting, no base64, no arg limit. */
    private void writeFlagFile(String json) throws Exception {
        try {
            ShizukuShell.runWithInput("cat > " + FLAG_FILE + " && chmod 644 " + FLAG_FILE, json);
        } catch (Exception stdinFailed) {
            // json only contains double quotes, so single-quoting is enough
            ShizukuShell.run("printf '%s' '" + json.replace("'", "'\\''") + "' > " + FLAG_FILE);
            ShizukuShell.run("chmod 644 " + FLAG_FILE);
        }
    }

    /** Compact capability report shown when an apply fails. */
    private String probe() {
        StringBuilder sb = new StringBuilder("probe:");
        try {
            sb.append(" uid=").append(ShizukuShell.run("id -u").trim());
        } catch (Throwable t) {
            sb.append(" uid=?");
        }
        try {
            String[] fields = ShizukuShell.run("ls -ld /data/local/tmp").trim().split("\\s+");
            sb.append(" tmp=").append(fields.length > 0 ? fields[0] : "?");
        } catch (Throwable t) {
            sb.append(" tmp=?");
        }
        try {
            ShizukuShell.run("echo x > /data/local/tmp/.fflag_probe; rm -f /data/local/tmp/.fflag_probe");
            sb.append(" write=ok");
        } catch (Throwable t) {
            sb.append(" write=denied");
        }
        try {
            String[] fields = ShizukuShell.run("ls -ld " + FLAG_FILE).trim().split("\\s+");
            sb.append(" file=").append(fields.length > 0 ? fields[0] : "?");
        } catch (Throwable t) {
            sb.append(" file=none");
        }
        try {
            sb.append(" ctx=").append(Shizuku.getSELinuxContext());
        } catch (Throwable t) {
            sb.append(" ctx=?");
        }
        return sb.toString();
    }

    private void showFailure(String title, Throwable t) {
        String full = t.getMessage();
        final String detail = "v" + versionName() + "\n"
                + (full == null ? t.getClass().getSimpleName() : full.trim())
                + "\n\n" + probe();
        setStatus(title + ": " + brief(t));
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(detail)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy", (d, w) -> {
                        ClipboardManager cb =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("fflag error", detail));
                        Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private void apply() {
        commitFocused();
        if (!Core.available()) {
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "?";
            setStatus("no flag engine for this CPU (" + abi + ") — nothing written");
            return;
        }
        if (!ShizukuShell.granted()) {
            if (ShizukuShell.alive()) {
                ShizukuShell.request(REQ_SHIZUKU);
                setStatus("grant the Shizuku prompt, then press Apply again");
            } else {
                setStatus("Shizuku isn't running — start it, then reopen this app");
            }
            return;
        }
        final String json;
        try {
            json = Core.canonical(toJsonObject(flags).toString());
        } catch (Throwable t) {
            setStatus("invalid: " + brief(t));
            return;
        }
        final String target = selectedTarget();
        setStatus("writing…");
        io.execute(() -> {
            try {
                backupOnce();
                writeFlagFile(json);
                String back = ShizukuShell.run("cat " + FLAG_FILE);
                if (!Core.canonical(back).equals(json)) {
                    throw new IllegalStateException("read-back mismatch");
                }
                int n = new JSONObject(json).length();
                baseline = new TreeMap<>(flags);
                deleteDraft();
                String msg = n + " flags written";
                try {
                    String restarted = restartClients(target);
                    if (!restarted.isEmpty()) msg += " · " + restarted;
                } catch (Throwable t) {
                    msg += " · restart failed: " + brief(t);
                }
                if (msg.contains("exited")) {
                    msg += " — your set may be crashing it: Clear all + Apply resets it";
                }
                final String done = msg;
                ui.post(() -> {
                    setStatus(done);
                    load();
                });
            } catch (Throwable t) {
                ui.post(() -> showFailure("Apply failed", t));
            }
        });
    }

    /**
     * Empties the working set. Nothing is written until Apply, and the pre-first-apply file
     * stays in app storage as ClientAppSettings.json.bak.
     */
    private void clearAll() {
        commitFocused();
        if (flags.isEmpty()) {
            setStatus("already empty");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear all flags?")
                .setMessage("The list becomes empty and is saved as a draft. Press Apply afterwards"
                        + " to write an empty set to " + FLAG_FILE + ".")
                .setPositiveButton("Clear", (d, w) -> {
                    flags.clear();
                    render();
                    saveDraft();
                    setStatus("cleared — press Apply to write an empty set");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private File backupFile() {
        return new File(getFilesDir(), "ClientAppSettings.json.bak");
    }

    private void backupOnce() throws Exception {
        File bak = backupFile();
        if (bak.isFile()) return;
        String current = ShizukuShell.run("cat " + FLAG_FILE + " 2>/dev/null || true");
        if (current.trim().isEmpty()) return;
        java.nio.file.Files.write(bak.toPath(), current.getBytes(StandardCharsets.UTF_8));
    }

    private String selectedTarget() {
        return TARGET_KEYS[Math.max(0, Math.min(TARGET_KEYS.length - 1, target - 1))];
    }

    /**
     * Restarts the selected client. Two things matter here: let the force-stop finish (a start
     * during teardown can be dropped) and launch through the launcher activity, not the private
     * splash component. Then check the process is still there — if it died, the caller says so
     * instead of leaving a mysterious "App info" screen.
     */
    private String restartClients(String target) throws Exception {
        StringBuilder done = new StringBuilder();
        for (String pkg : PKGS) {
            if (!pkg.equals(target)) continue;
            if (!ShizukuShell.run("pm list packages " + pkg).contains("package:" + pkg)) {
                done.append(done.length() == 0 ? "" : " + ")
                        .append(shortName(pkg)).append(": not installed");
                continue;
            }
            ShizukuShell.run("am force-stop " + pkg);
            for (int i = 0; i < 12
                    && !ShizukuShell.run("pidof " + pkg + " || true").trim().isEmpty(); i++) {
                Thread.sleep(200);
            }
            String out = ShizukuShell.run("am start -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER -p " + pkg);
            Thread.sleep(3000);
            boolean alive = !ShizukuShell.run("pidof " + pkg + " || true").trim().isEmpty();
            if (done.length() > 0) done.append(" + ");
            if (alive) {
                done.append("restarted ").append(shortName(pkg));
            } else if (out.contains("Error")) {
                done.append(shortName(pkg)).append(": launch failed (")
                        .append(lastLine(out)).append(")");
            } else {
                done.append(shortName(pkg)).append(": started, then exited (crash?)");
            }
        }
        return done.toString();
    }

    private static String shortName(String pkg) {
        return pkg.endsWith("vnggames") ? "VNG" : "Roblox";
    }

    /** `cmd package resolve-activity` prints a header line before the component. */
    private static String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1].trim();
    }

    /** Status bar is one line: never dump a stack trace into it. */
    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        m = m.trim();
        int cut = m.indexOf('\n');
        if (cut > 0) m = m.substring(0, cut);
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }

    // ---------- dialogs ----------

    private void addDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        EditText key = new EditText(this);
        key.setHint("FFlagSomething / DFIntThing");
        key.setSingleLine(true);
        EditText value = new EditText(this);
        value.setHint("value (true / 120 / text)");
        value.setSingleLine(true);
        box.addView(key);
        box.addView(value);
        new AlertDialog.Builder(this)
                .setTitle("Add flag")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    try {
                        String one = Core.one(key.getText().toString().trim(), value.getText().toString().trim());
                        JSONObject obj = new JSONObject(one);
                        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                            String k = it.next();
                            flags.put(k, display(obj.get(k)));
                        }
                        render();
                        saveDraft();
                        setStatus("added " + obj.keys().next());
                    } catch (Throwable t) {
                        Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void jsonDialog() {
        commitFocused();
        jsonBox = new EditText(this);
        jsonBox.setTypeface(Typeface.MONOSPACE);
        jsonBox.setTextSize(12);
        jsonBox.setTextColor(TEXT);
        jsonBox.setGravity(Gravity.TOP | Gravity.START);
        jsonBox.setMinLines(8);
        jsonBox.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        try {
            jsonBox.setText(toJsonObject(flags).toString(2));
        } catch (JSONException ignored) {
        }
        jsonBox.setSelection(0);

        jsonDialog = new AlertDialog.Builder(this)
                .setTitle("Custom FFlags (JSON)")
                .setView(jsonBox)
                .setPositiveButton("Replace", null)
                .setNeutralButton(".json file", null)
                .setNegativeButton("Cancel", null)
                .create();
        jsonDialog.setOnShowListener(x -> {
            jsonDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> replaceFromBox());
            jsonDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> pickJsonFile());
        });
        jsonDialog.show();
    }

    /** The picked object becomes the whole set, after the Rust engine validates it. */
    private void replaceFromBox() {
        try {
            JSONObject imported = Core.importJson(jsonBox.getText().toString());
            JSONObject obj = imported.getJSONObject("flags");
            flags.clear();
            int n = 0;
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String k = it.next();
                flags.put(k, display(obj.get(k)));
                n++;
            }
            render();
            saveDraft();
            setStatus("replaced with " + n + " flags — press Apply to write");
            jsonDialog.dismiss();
            reportImport(imported);
        } catch (Throwable t) {
            // keep the dialog open so the JSON can be fixed
            Toast.makeText(this, brief(t), Toast.LENGTH_LONG).show();
        }
    }

    /** SAF picker; only .json documents are accepted, and only if they parse as flags. */
    private void pickJsonFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // providers disagree about the json mime type, so show the candidates
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/json"});
        try {
            startActivityForResult(intent, REQ_JSON);
        } catch (Throwable t) {
            Toast.makeText(this, "no file picker: " + brief(t), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_JSON || resultCode != RESULT_OK) return;
        Uri uri = data == null ? null : data.getData();
        if (uri == null) return;
        try {
            String name = displayName(uri);
            if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                throw new IllegalArgumentException("only .json files (" + name + ")");
            }
            String text = readText(uri, MAX_JSON_BYTES);
            JSONObject imported = Core.importJson(text);
            JSONObject obj = imported.getJSONObject("flags");
            if (jsonBox == null || jsonDialog == null || !jsonDialog.isShowing()) return;
            jsonBox.setText(obj.toString(2));
            jsonBox.setSelection(0);
            setStatus(name + ": " + obj.length() + " valid flags — press Replace");
            reportImport(imported);
        } catch (Throwable t) {
            Toast.makeText(this, brief(t), Toast.LENGTH_LONG).show();
        }
    }

    /** Explains what an import dropped, inferred or skipped, with the full list on demand. */
    private void reportImport(JSONObject imported) {
        org.json.JSONArray aliased = imported.optJSONArray("aliased");
        org.json.JSONArray inferred = imported.optJSONArray("inferred");
        org.json.JSONArray skipped = imported.optJSONArray("skipped");
        int a = aliased == null ? 0 : aliased.length();
        int i = inferred == null ? 0 : inferred.length();
        int k = skipped == null ? 0 : skipped.length();
        if (a + i + k == 0) return;

        StringBuilder body = new StringBuilder();
        if (a > 0) {
            body.append("Dropped ").append(a).append(" bare name(s) that already exist with a real")
                    .append(" prefix in this file (the prefixed one is the one the client reads):\n")
                    .append(join(aliased, 12)).append("\n\n");
        }
        if (i > 0) {
            body.append("Wrote ").append(i).append(" orphan bare name(s) as both F and DF variants")
                    .append(" (the variant the client doesn't know is ignored):\n")
                    .append(join(inferred, 12)).append("\n\n");
        }
        if (k > 0) {
            body.append("Skipped ").append(k).append(" key(s) that cannot be applied:\n")
                    .append(join(skipped, 12)).append("\n");
        }
        final String detail = body.toString().trim();
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Import report")
                    .setMessage(detail)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy", (d, w) -> {
                        ClipboardManager cb =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("fflag import", detail));
                        Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private static String join(org.json.JSONArray array, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length() && i < limit; i++) {
            sb.append("  ").append(array.optString(i)).append("\n");
        }
        if (array.length() > limit) sb.append("  …and ").append(array.length() - limit).append(" more\n");
        return sb.toString();
    }

    // ---------- device tuning ----------

    // ---------- self-update ----------

    private void footerRefresh() {
        if (footer == null) return;
        if (updateBanner != null) {
            boolean has = pendingUpdate != null;
            updateBanner.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                updateBanner.setText("Update " + pendingUpdate + " ready — tap to install");
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("v").append(versionName());
        if (profile != null) text.append(" · ").append(profile.label);
        text.append(" · writes ").append(FLAG_FILE).append(" through Shizuku (uid 2000, no root)");
        footer.setText(text.toString());
    }

    /** Silent on launch; interactive when the Update button asked. */
    private void checkUpdate(boolean interactive) {
        if (interactive) setStatus("checking for updates…");
        io.execute(() -> {
            try {
                Updater.Release release = Updater.latest();
                if (Updater.newer(release.tag, versionName())) {
                    pendingUpdate = release.tag;
                    pendingUpdateUrl = release.apkUrl;
                    ui.post(() -> {
                        footerRefresh();
                        if (interactive) {
                            setStatus("update " + release.tag + " available — tap the violet banner");
                        }
                    });
                } else if (interactive) {
                    ui.post(() -> setStatus("v" + versionName() + " is the latest"));
                }
            } catch (Throwable t) {
                if (interactive) ui.post(() -> setStatus("update check failed: " + brief(t)));
            }
        });
    }

    private void installUpdate() {
        if (!ShizukuShell.granted()) {
            setStatus("grant Shizuku first");
            return;
        }
        final String url = pendingUpdateUrl;
        final String tag = pendingUpdate;
        setStatus("downloading " + tag + "…");
        io.execute(() -> {
            try {
                String out = Updater.install(url);
                ui.post(() -> setStatus("installed " + tag + " (" + out + ") — open the app again"));
            } catch (Throwable t) {
                ui.post(() -> showFailure("Update failed", t));
            }
        });
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment();
    }

    private String readText(Uri uri, int limit) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open file");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (buf.size() + n > limit) throw new IllegalStateException("file too large");
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Builds a ready-to-paste prompt for an AI, using this device's client + current set. */
    /** name -> {flag: value}. Empty until the user saves a set; nothing ships preloaded. */
    private JSONObject presets() {
        try {
            return new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(PRESETS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void savePresets(JSONObject all) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PRESETS, all.toString()).apply();
    }

    private void presetDialog() {
        commitFocused();
        JSONObject store = presets();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Presets")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        TextView save = Style.pill(this, "Save current set (" + flags.size() + " flags)", true);
        save.setOnClickListener(v -> {
            dlg.dismiss();
            savePresetDialog();
        });
        box.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        if (store.length() == 0) {
            TextView hint = Style.label(this,
                    "No presets yet.\nSave your current flags once, then apply that set any time.",
                    12, Style.DIM, false);
            hint.setPadding(0, dp(18), 0, dp(10));
            box.addView(hint);
            dlg.show();
            return;
        }
        for (Iterator<String> it = store.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject set = store.optJSONObject(name);
            if (set != null) box.addView(presetRow(dlg, name, set));
        }
        dlg.show();
    }

    private View presetRow(AlertDialog dlg, String name, JSONObject set) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Style.round(this, Style.SURFACE_HI, 12, Style.STROKE));
        row.setPadding(dp(12), dp(10), dp(6), dp(10));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Style.label(this, name, 13, Style.TEXT, true));
        TextView sub = Style.label(this, set.length() + " flags · tap to apply",
                10.5f, Style.DIM, false);
        sub.setPadding(0, dp(3), 0, 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = Style.label(this, "✕", 14, Style.DANGER, true);
        del.setGravity(Gravity.CENTER);
        del.setBackground(Style.ripple(this, Style.round(this, 0x00000000, 17), 17));
        del.setOnClickListener(v -> {
            JSONObject store = presets();
            store.remove(name);
            savePresets(store);
            dlg.dismiss();
            setStatus("deleted preset " + name);
        });
        row.addView(del, new LinearLayout.LayoutParams(dp(34), dp(34)));

        row.setOnClickListener(v -> {
            int added = 0;
            for (Iterator<String> it = set.keys(); it.hasNext(); ) {
                String k = it.next();
                flags.put(k, display(set.opt(k)));
                added++;
            }
            render();
            saveDraft();
            dlg.dismiss();
            setStatus("applied " + name + ": " + added + " flags");
        });
        return row;
    }

    private void savePresetDialog() {
        EditText input = new EditText(this);
        input.setHint("Preset name");
        input.setSingleLine(true);
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.DIM);
        input.setIncludeFontPadding(false);
        input.setBackground(Style.round(this, Style.SURFACE_HI, 10, Style.STROKE));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(6), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Save preset")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        setStatus("preset needs a name");
                        return;
                    }
                    JSONObject store = presets();
                    try {
                        store.put(name, toJsonObject(flags));
                    } catch (JSONException e) {
                        setStatus("could not save preset: " + brief(e));
                        return;
                    }
                    savePresets(store);
                    setStatus("preset saved: " + name + " (" + flags.size() + " flags)");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static JSONObject toJsonObject(TreeMap<String, String> flags) {
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, String> e : flags.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {
        }
        return obj;
    }
}
