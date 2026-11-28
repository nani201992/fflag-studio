package com.kizu.fflagstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * "Ask AI" dialog: one question, one answer, no chat history. Endpoint, model and key are the
 * user's own and live in private preferences, so the question leaves the phone only to the server
 * they configured.
 */
final class AskAi {

    interface Host {
        /** Short description of the current set, filtered by what the question mentions. */
        String flagContext(String question);

        /** JSON object of suggested flags; the app validates it with the Rust engine. */
        void aiSuggested(String json);

        void aiStatus(String message);

        int dp(int value);
    }

    private AskAi() {
    }

    static void show(final Activity act, final Host host) {
        if (!Ai.ready(act)) {
            showSettings(act, host);
            return;
        }

        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(18), host.dp(8), host.dp(18), host.dp(8));

        final EditText question = new EditText(act);
        question.setHint("Ask about a flag, or what to change");
        question.setHintTextColor(Style.DIM);
        question.setTextColor(Style.TEXT);
        question.setTextSize(13);
        question.setMinLines(3);
        question.setMaxLines(5);
        question.setGravity(Gravity.TOP | Gravity.START);
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        question.setBackground(Style.round(act, Style.SURFACE_HI, 12, Style.STROKE));
        question.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        root.addView(question);

        final TextView status = Style.label(act, quota(act), 11, Style.DIM, false);
        status.setPadding(0, host.dp(8), 0, 0);

        final ScrollView answerScroll = new ScrollView(act);
        answerScroll.setVisibility(View.GONE);
        final TextView answer = new TextView(act);
        answer.setTextSize(12.5f);
        answer.setTextColor(Style.TEXT);
        answer.setTextIsSelectable(true);
        answer.setLineSpacing(0, 1.12f);
        answer.setBackground(Style.round(act, Style.SURFACE, 12, Style.STROKE));
        answer.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        answerScroll.addView(answer);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(300));
        scrollLp.topMargin = host.dp(10);
        root.addView(answerScroll, scrollLp);

        final TextView copy = Style.pill(act, "Copy", false);
        final TextView add = Style.pill(act, "Add flags", false);
        copy.setVisibility(View.GONE);
        add.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, host.dp(10), 0, 0);
        LinearLayout.LayoutParams halfLp =
                new LinearLayout.LayoutParams(0, host.dp(40), 1f);
        halfLp.rightMargin = host.dp(4);
        row.addView(copy, halfLp);
        LinearLayout.LayoutParams halfLp2 =
                new LinearLayout.LayoutParams(0, host.dp(40), 1f);
        halfLp2.leftMargin = host.dp(4);
        row.addView(add, halfLp2);

        final TextView send = Style.pill(act, "Send", true);
        final TextView setup = Style.pill(act, "Setup…", false);
        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, host.dp(42), 2f);
        sendLp.topMargin = host.dp(10);
        sendLp.rightMargin = host.dp(4);
        buttons.addView(send, sendLp);
        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(0, host.dp(42), 1f);
        setupLp.topMargin = host.dp(10);
        setupLp.leftMargin = host.dp(4);
        buttons.addView(setup, setupLp);
        root.addView(buttons);
        root.addView(status);
        root.addView(row);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Ask AI")
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        dlg.show();

        final String[] suggestion = {null};

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("answer", answer.getText()));
            status.setText("answer copied");
        });
        add.setOnClickListener(v -> {
            if (suggestion[0] == null) return;
            host.aiSuggested(suggestion[0]);
            dlg.dismiss();
        });
        setup.setOnClickListener(v -> {
            dlg.dismiss();
            showSettings(act, host);
        });

        send.setOnClickListener(v -> {
            final String q = question.getText().toString().trim();
            if (q.isEmpty()) {
                status.setText("type a question first");
                return;
            }
            String blocked = Ai.blocked(act, System.currentTimeMillis());
            if (blocked != null) {
                status.setText(blocked);
                return;
            }
            send.setEnabled(false);
            send.setText("Thinking…");
            status.setText("asking " + Ai.model(act) + "…");
            new Thread(() -> {
                String text = null;
                String error = null;
                try {
                    text = Ai.ask(act, q, host.flagContext(q));
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String ok = text;
                final String bad = error;
                act.runOnUiThread(() -> {
                    send.setEnabled(true);
                    send.setText("Send");
                    if (ok == null) {
                        status.setText(bad);
                        return;
                    }
                    answer.setText(ok);
                    answerScroll.setVisibility(View.VISIBLE);
                    copy.setVisibility(View.VISIBLE);
                    suggestion[0] = jsonBlock(ok);
                    add.setVisibility(suggestion[0] == null ? View.GONE : View.VISIBLE);
                    status.setText(quota(act));
                });
            }).start();
        });
    }

    private static String quota(Activity act) {
        return Ai.model(act) + " · " + Ai.leftToday(act, System.currentTimeMillis())
                + " of " + Ai.PER_DAY + " left today";
    }

    /** The last fenced json block, or a bare {...} object, from the answer. */
    static String jsonBlock(String text) {
        int open = text.indexOf("```json");
        int from = 7;
        if (open < 0) {
            open = text.indexOf("```");
            from = 3;
        }
        if (open >= 0) {
            int end = text.indexOf("```", open + from);
            if (end > open) {
                String body = text.substring(open + from, end).trim();
                if (body.startsWith("{")) return body;
            }
        }
        int a = text.indexOf("{");
        int b = text.lastIndexOf("}");
        return a >= 0 && b > a ? text.substring(a, b + 1).trim() : null;
    }

    /**
     * Endpoint, model and key. Save applies all three; Fetch asks the typed endpoint (with the
     * typed key) for its model list, so the fields can be tried before they are committed.
     */
    private static void showSettings(final Activity act, final Host host) {
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(18), host.dp(8), host.dp(18), 0);

        TextView hint = Style.label(act,
                "OpenCode Zen, OpenRouter, OpenAI, Groq or any OpenAI-compatible server. The key "
                        + "stays on this phone in this app's private storage — none ships with the "
                        + "app, because anything inside an APK can be read by whoever installs it.",
                11.5f, Style.DIM, false);
        hint.setLineSpacing(0, 1.1f);
        root.addView(hint);

        final EditText endpoint = field(act, host, root, "Endpoint",
                "https://opencode.ai/zen/v1", Ai.endpoint(act), false);

        caption(act, host, root, "Model id");
        LinearLayout modelRow = new LinearLayout(act);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText model = input(act, host, Ai.DEFAULT_MODEL, Ai.model(act), true);
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(0, host.dp(40), 3f);
        modelLp.topMargin = host.dp(4);
        modelLp.rightMargin = host.dp(4);
        modelRow.addView(model, modelLp);
        final TextView fetch = Style.pill(act, "Fetch", false);
        LinearLayout.LayoutParams fetchLp = new LinearLayout.LayoutParams(0, host.dp(40), 1f);
        fetchLp.topMargin = host.dp(4);
        fetchLp.leftMargin = host.dp(4);
        modelRow.addView(fetch, fetchLp);
        root.addView(modelRow);

        final EditText key = field(act, host, root, "API key",
                "oc_sk_… (leave empty for a local server)", Ai.key(act), true);

        ScrollView wrap = new ScrollView(act);
        wrap.addView(root);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Ask AI setup")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (!Ai.looksLikeKey(k)) {
                        host.aiStatus("that does not look like an API key");
                        return;
                    }
                    boolean opencode = Ai.chatUrl(endpoint.getText().toString())
                            .equals(Ai.chatUrl(Ai.DEFAULT_ENDPOINT));
                    if (opencode && k.isEmpty()) {
                        host.aiStatus("opencode needs your key");
                        return;
                    }
                    Ai.saveSettings(act, endpoint.getText().toString(),
                            model.getText().toString(), k);
                    host.aiStatus("AI settings saved");
                    show(act, host);
                })
                .setNeutralButton("Reset", (d, w) -> {
                    Ai.saveSettings(act, Ai.DEFAULT_ENDPOINT, Ai.DEFAULT_MODEL, "");
                    host.aiStatus("AI settings reset");
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();

        fetch.setOnClickListener(v -> {
            fetch.setEnabled(false);
            fetch.setText("…");
            new Thread(() -> {
                List<String> ids = null;
                String error = null;
                try {
                    ids = Ai.models(endpoint.getText().toString(), key.getText().toString());
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final List<String> list = ids;
                final String bad = error;
                act.runOnUiThread(() -> {
                    fetch.setEnabled(true);
                    fetch.setText("Fetch");
                    if (list == null) {
                        host.aiStatus(bad);
                        return;
                    }
                    if (list.isEmpty()) {
                        host.aiStatus("the endpoint returned no models");
                        return;
                    }
                    new AlertDialog.Builder(act)
                            .setTitle(list.size() + " models — tap to use")
                            .setItems(list.toArray(new String[0]),
                                    (d, which) -> model.setText(list.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            }).start();
        });
    }

    /** Label + full-width field. */
    private static EditText field(Activity act, Host host, LinearLayout root,
                                  String label, String hint, String value, boolean mono) {
        caption(act, host, root, label);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40));
        lp.topMargin = host.dp(4);
        row.addView(input(act, host, hint, value, mono), lp);
        root.addView(row);
        return (EditText) row.getChildAt(0);
    }

    private static void caption(Activity act, Host host, LinearLayout root, String label) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = host.dp(10);
        root.addView(Style.label(act, label, 11, Style.DIM, false), lp);
    }

    private static EditText input(Activity act, Host host, String hint, String value,
                                  boolean mono) {
        EditText input = new EditText(act);
        input.setHint(hint);
        input.setHintTextColor(Style.DIM);
        input.setTextColor(Style.TEXT);
        input.setTextSize(12.5f);
        if (mono) input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setText(value);
        input.setBackground(Style.round(act, Style.SURFACE_HI, 10, Style.STROKE));
        input.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        return input;
    }
}
