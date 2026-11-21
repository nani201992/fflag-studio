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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
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
    private RadioGroup targets;
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
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(18), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        title.setText("FFlag Studio");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        root.addView(header);

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(12);
        status.setPadding(0, dp(4), 0, dp(10));
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Add", v -> addDialog()));
        buttons.addView(button("Preset", v -> presetDialog()));
        buttons.addView(button("JSON", v -> jsonDialog()));
        root.addView(buttons);

        LinearLayout buttons2 = new LinearLayout(this);
        buttons2.setOrientation(LinearLayout.HORIZONTAL);
        buttons2.addView(button("Undo", v -> revert()));
        buttons2.addView(button("Prompt", v -> promptDialog()));
        buttons2.addView(button("Apply", v -> apply()));
        root.addView(buttons2);

        LinearLayout buttons3 = new LinearLayout(this);
        buttons3.setOrientation(LinearLayout.HORIZONTAL);
        buttons3.addView(button("Optimize", v -> optimizeAndReport()));
        buttons3.addView(button("Update", v -> checkUpdate(true)));
        root.addView(buttons3);

        search = new EditText(this);
        search.setHint("filter");
        search.setHintTextColor(DIM);
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        search.setOnEditorActionListener((v, a, e) -> {
            render();
            return false;
        });
        search.setOnKeyListener((v, code, e) -> {
            render();
            return false;
        });
        root.addView(search);

        empty = new TextView(this);
        empty.setTextColor(DIM);
        empty.setPadding(0, dp(12), 0, 0);
        root.addView(empty);

        // ListView recycles row views: a 1300-flag set no longer builds 1300 EditTexts
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        adapter = new FlagAdapter(this, this);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(list, lp);

        TextView targetLabel = new TextView(this);
        targetLabel.setText("apply to");
        targetLabel.setTextColor(DIM);
        targetLabel.setTextSize(12);
        targetLabel.setPadding(0, dp(6), 0, dp(2));
        root.addView(targetLabel);

        targets = new RadioGroup(this);
        targets.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"Roblox", "VNG"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(labels[i]);
            rb.setTextColor(DIM);
            rb.setTextSize(12);
            rb.setButtonTintList(ColorStateList.valueOf(ACCENT));
            targets.addView(rb, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int saved = prefs.getInt("target", 1);
        targets.check(saved >= 1 && saved <= labels.length ? saved : 1);
        targets.setOnCheckedChangeListener((group, checked) ->
                prefs.edit().putInt("target", checked).apply());
        root.addView(targets);

        footer = new TextView(this);
        footer.setTextColor(0xFF6E6E78);
        footer.setTextSize(11);
        footer.setOnClickListener(v -> {
            if (pendingUpdate != null) installUpdate();
        });
        root.addView(footer);
        footerRefresh();
        return root;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setBackgroundTintList(ColorStateList.valueOf(CARD));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
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
        empty.setText(flags.isEmpty() ? "no flags yet — Add, Preset or JSON" : "no match");
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

    private void revert() {
        if (!ShizukuShell.granted()) {
            setStatus("grant Shizuku first");
            return;
        }
        final String target = selectedTarget();
        setStatus("reverting…");
        io.execute(() -> {
            try {
                File bak = backupFile();
                String msg;
                if (bak.isFile()) {
                    String text = new String(java.nio.file.Files.readAllBytes(bak.toPath()),
                            StandardCharsets.UTF_8);
                    writeFlagFile(text);
                    //noinspection ResultOfMethodCallIgnored
                    bak.delete();
                    msg = "restored the previous file";
                } else {
                    ShizukuShell.run("rm -f " + FLAG_FILE);
                    msg = "removed " + FLAG_FILE;
                }
                try {
                    String restarted = restartClients(target);
                    if (!restarted.isEmpty()) msg += " · " + restarted;
                } catch (Throwable t) {
                    msg += " · restart failed: " + brief(t);
                }
                final String done = msg;
                ui.post(() -> {
                    setStatus(done);
                    load();
                });
            } catch (Throwable t) {
                ui.post(() -> showFailure("Undo failed", t));
            }
        });
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
        int checked = targets == null ? 1 : targets.getCheckedRadioButtonId();
        return TARGET_KEYS[Math.max(0, Math.min(TARGET_KEYS.length - 1, checked - 1))];
    }

    /** Restarts the selected client (the file itself is shared by both). */
    private String restartClients(String target) throws Exception {
        StringBuilder done = new StringBuilder();
        for (String pkg : PKGS) {
            if (!pkg.equals(target)) continue;
            if (!ShizukuShell.run("pm list packages " + pkg).contains("package:" + pkg)) {
                done.append(done.length() == 0 ? "" : " + ").append(shortName(pkg)).append(": not installed");
                continue;
            }
            String act = "";
            try {
                act = lastLine(ShizukuShell.run("cmd package resolve-activity --brief " + pkg));
            } catch (Throwable ignored) {
                // some builds/roms exit 1 with no output here; the launcher intent below still works
            }
            ShizukuShell.run("am force-stop " + pkg);
            if (act.contains("/")) {
                ShizukuShell.run("am start -n " + act);
            } else {
                ShizukuShell.run("am start -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER -p " + pkg);
            }
            done.append(done.length() == 0 ? "" : " + ").append(shortName(pkg));
        }
        return done.length() == 0 ? "" : "restarted " + done;
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

    /**
     * Nothing is rewritten unless the client actually crashed: the log buffer is the only
     * evidence this app has about the device, and flag values are typed settings, not a
     * score (a high value is not automatically bad).
     */
    private void optimizeAndReport() {
        commitFocused();
        setStatus("checking the client crash log…");
        final String pkg = selectedTarget();
        io.execute(() -> {
            CrashScan scan;
            try {
                scan = CrashScan.run(pkg);
            } catch (Throwable t) {
                scan = CrashScan.of("", "");
            }
            final CrashScan found = scan;
            ui.post(() -> {
                if (CrashScan.NONE.equals(found.kind)) {
                    setStatus("no crash found — nothing changed");
                    showReport("Optimize", "No crash for " + shortName(pkg)
                            + " in the log since boot, so no value was changed.\n\n"
                            + "Flags are typed settings read by the client (" + FLAG_FILE
                            + "); a large value is legal and not automatically bad, so this"
                            + " app only lowers things after a real crash.");
                } else {
                    List<String> fix = applyMitigation(found.kind);
                    render();
                    saveDraft();
                    showCrashReport(found, fix);
                }
            });
        });
    }

    /** The flag levers that matter for each crash family; returns what was set. */
    private List<String> applyMitigation(String kind) {
        String[][] fix;
        if ("memory".equals(kind)) {
            fix = new String[][]{
                    {"DFIntTextureQualityOverride", "1"},
                    {"DFIntMaxFrameBufferSize", "4"},
                    {"FIntDebugForceMSAASamples", "1"},
                    {"DFIntTaskSchedulerTargetFps", "30"},
                    {"DFFlagEnableMeshPreloading", "false"},
                    {"DFFlagEnableMeshPreloading2", "false"},
                    {"DFFlagAudioEnableVolumetricPanningForMeshes", "false"},
            };
        } else if ("graphics".equals(kind)) {
            fix = new String[][]{
                    {"FIntDebugForceMSAASamples", "1"},
                    {"DFIntDebugFRMQualityLevelOverride", "5"},
                    {"DFIntMaxFrameBufferSize", "6"},
            };
        } else {
            fix = new String[][]{
                    {"DFIntTaskSchedulerTargetFps", "30"},
                    {"DFIntTextureQualityOverride", "2"},
                    {"FIntDebugForceMSAASamples", "1"},
            };
        }
        List<String> applied = new ArrayList<>();
        for (String[] kv : fix) {
            flags.put(kv[0], kv[1]);
            applied.add(kv[0] + " = " + kv[1]);
        }
        return applied;
    }

    private void showCrashReport(CrashScan scan, List<String> fix) {
        StringBuilder body = new StringBuilder("Device: ").append(profile.label).append("\n")
                .append("Crash: ").append(scan.kind).append("\n\n")
                .append(scan.evidence.trim()).append("\n\nFixes applied:\n");
        for (String line : fix) body.append("  ").append(line).append("\n");
        body.append("\nPress Apply to write them. ");
        body.append("The log tells the family (memory / graphics / native), not the exact flag — ");
        body.append("if it keeps crashing, press Undo and drop your most recent additions.");
        final String detail = body.toString().trim();
        setStatus("crash found (" + scan.kind + ") — " + fix.size() + " fix(es) staged");
        showReport("Crash report", detail);
    }

    private void showReport(String title, final String detail) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(detail)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy", (d, w) -> {
                        ClipboardManager cb =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("fflag report", detail));
                        Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    // ---------- self-update ----------

    private void footerRefresh() {
        if (footer == null) return;
        StringBuilder text = new StringBuilder();
        if (pendingUpdate != null) {
            text.append("update ").append(pendingUpdate).append(" available — tap here to install\n");
        }
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
                            setStatus("update " + release.tag + " available — tap the footer");
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
    private void promptDialog() {
        commitFocused();
        final String current;
        try {
            current = toJsonObject(flags).toString(2);
        } catch (JSONException e) {
            return;
        }
        setStatus("building prompt…");
        io.execute(() -> {
            String prompt = buildPrompt(current, clientVersions());
            ui.post(() -> showPrompt(prompt));
        });
    }

    private String clientVersions() {
        StringBuilder sb = new StringBuilder();
        for (String pkg : PKGS) {
            try {
                String out = ShizukuShell.run(
                        "dumpsys package " + pkg + " | grep -m1 versionName").trim();
                if (out.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(shortName(pkg)).append(" ")
                        .append(out.replace("versionName=", "").trim());
            } catch (Throwable ignored) {
            }
        }
        return sb.length() == 0 ? "Roblox Android (version unknown)" : sb.toString();
    }

    private String buildPrompt(String current, String clients) {
        return "You are writing Roblox Android FastFlags for ClientAppSettings.json.\n"
                + "Answer with ONE JSON object only: no prose, no markdown fences, no comments,"
                + " no trailing commas.\n\n"
                + "Format rules:\n"
                + "- Key prefixes decide the type: FFlag / DFFlag = boolean,"
                + " FInt / DFInt = integer, FString / DFString = string.\n"
                + "- The value must match the prefix: true/false, a bare integer, or a quoted string.\n"
                + "- Use only real Roblox flag names you are confident exist. Never invent a name.\n"
                + "- Prefer client-side rendering/performance flags. Skip anything that breaks"
                + " gameplay, physics sanity or anti-cheat.\n"
                + "- Return at most 15 flags.\n\n"
                + "Client: " + clients + "\n"
                + "Goal: <replace this with what you want, e.g. 120 FPS, lowest graphics,"
                + " no visual clutter>\n\n"
                + "Current flags on the device (keep or change as needed):\n"
                + current + "\n\n"
                + "Return only the JSON object; I will paste it into my flag editor.\n";
    }

    private void showPrompt(String prompt) {
        EditText box = new EditText(this);
        box.setTypeface(Typeface.MONOSPACE);
        box.setTextSize(12);
        box.setTextColor(TEXT);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setMinLines(10);
        box.setTextIsSelectable(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setText(prompt);

        new AlertDialog.Builder(this)
                .setTitle("AI prompt")
                .setView(box)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cb =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("fflag prompt",
                                box.getText().toString()));
                    }
                    setStatus("prompt copied — paste it into your AI");
                })
                .setNeutralButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, box.getText().toString());
                    try {
                        startActivity(Intent.createChooser(send, "share prompt"));
                    } catch (Throwable t) {
                        Toast.makeText(this, brief(t), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
        setStatus("prompt ready — Copy, ask your AI, paste the JSON back in");
    }

    private void presetDialog() {
        String[] names = {"fps", "performance", "clean"};
        new AlertDialog.Builder(this)
                .setTitle("Preset")
                .setItems(names, (d, which) -> {
                    try {
                        commitFocused();
                        JSONObject obj = new JSONObject(Core.presetJson(names[which]));
                        int added = 0;
                        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                            String k = it.next();
                            flags.put(k, display(obj.get(k)));
                            added++;
                        }
                        render();
                        saveDraft();
                        setStatus("preset " + names[which] + ": " + added + " flags");
                    } catch (Throwable t) {
                        Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
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
