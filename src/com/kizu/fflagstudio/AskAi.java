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

/**
 * "Ask AI" dialog: one question, one answer, no chat history. The API key is the user's own and
 * lives in private preferences, so the question leaves the phone only to the endpoint they chose.
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
        if (Ai.key(act).isEmpty()) {
            showKey(act, host);
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

        final TextView status = Style.label(act, "model " + Ai.MODEL + " · "
                + Ai.leftToday(act, System.currentTimeMillis()) + " of " + Ai.PER_DAY + " left today",
                11, Style.DIM, false);
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
        final TextView keyBtn = Style.pill(act, "Key…", false);
        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, host.dp(42), 2f);
        sendLp.topMargin = host.dp(10);
        sendLp.rightMargin = host.dp(4);
        buttons.addView(send, sendLp);
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, host.dp(42), 1f);
        keyLp.topMargin = host.dp(10);
        keyLp.leftMargin = host.dp(4);
        buttons.addView(keyBtn, keyLp);
        root.addView(buttons);
        root.addView(status);
        root.addView(row);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Ask AI")
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        // setView trims long content, so let the dialog size itself against the answer area
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
        keyBtn.setOnClickListener(v -> {
            dlg.dismiss();
            showKey(act, host);
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
            status.setText("asking " + Ai.MODEL + "…");
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
                    status.setText("model " + Ai.MODEL + " · "
                            + Ai.leftToday(act, System.currentTimeMillis())
                            + " of " + Ai.PER_DAY + " left today");
                });
            }).start();
        });
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

    private static void showKey(final Activity act, final Host host) {
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(18), host.dp(8), host.dp(18), 0);

        TextView hint = Style.label(act,
                "Paste your own OpenCode API key (opencode.ai → Zen). It is stored only on this "
                        + "phone, in this app's private storage — no key ships with the app, because "
                        + "anything inside an APK can be read by whoever installs it.",
                11.5f, Style.DIM, false);
        hint.setLineSpacing(0, 1.1f);
        root.addView(hint);

        final EditText input = new EditText(act);
        input.setHint("oc_sk_…");
        input.setHintTextColor(Style.DIM);
        input.setTextColor(Style.TEXT);
        input.setTextSize(12.5f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setText(Ai.key(act));
        input.setBackground(Style.round(act, Style.SURFACE_HI, 10, Style.STROKE));
        input.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = host.dp(10);
        root.addView(input, inputLp);

        new AlertDialog.Builder(act)
                .setTitle("Ask AI key")
                .setView(root)
                .setPositiveButton("Save", (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (!Ai.looksLikeKey(key)) {
                        host.aiStatus("that does not look like an API key");
                        return;
                    }
                    Ai.saveKey(act, key);
                    host.aiStatus("AI key saved");
                    show(act, host);
                })
                .setNeutralButton("Forget", (d, w) -> {
                    Ai.forgetKey(act);
                    host.aiStatus("AI key removed");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
