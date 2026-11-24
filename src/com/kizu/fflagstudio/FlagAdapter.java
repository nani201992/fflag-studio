package com.kizu.fflagstudio;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Recycled row list: only the visible flags hold views, so a 1300-flag set scrolls like a
 * 10-flag one. Row children by index: 0 name, 1 value, 2 delete.
 */
final class FlagAdapter extends BaseAdapter {

    interface Host {
        int flagCount();

        String flagKeyAt(int position);

        String flagValue(String key);

        void onDeleteFlag(String key);

        void onRowValue(String key, String value);

        int dp(int value);
    }

    private final Context ctx;
    private final Host host;

    FlagAdapter(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    @Override
    public int getCount() {
        return host.flagCount();
    }

    @Override
    public Object getItem(int position) {
        return host.flagKeyAt(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row = convertView instanceof LinearLayout
                ? (LinearLayout) convertView
                : buildRow();

        String key = host.flagKeyAt(position);
        ((TextView) row.getChildAt(0)).setText(key);
        EditText value = (EditText) row.getChildAt(1);
        value.setTag(key);
        value.setText(host.flagValue(key));
        return row;
    }

    private LinearLayout buildRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Style.round(ctx, Style.SURFACE, 14, Style.STROKE));
        row.setPadding(host.dp(12), host.dp(10), host.dp(6), host.dp(10));

        TextView name = Style.label(ctx, "", 12.5f, Style.TEXT, true);
        name.setLineSpacing(0, 1.05f);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText value = new EditText(ctx);
        value.setTextColor(Style.VALUE);
        value.setTextSize(12.5f);
        value.setTypeface(Typeface.MONOSPACE);
        value.setSingleLine(true);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.CENTER);
        value.setPadding(host.dp(6), host.dp(7), host.dp(6), host.dp(7));
        value.setBackground(Style.round(ctx, Style.SURFACE_HI, 10, Style.STROKE));
        // commit on blur: the row may be recycled for another flag before the next Apply
        value.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            Object key = v.getTag();
            if (key instanceof String) {
                host.onRowValue((String) key, ((EditText) v).getText().toString().trim());
            }
        });
        LinearLayout.LayoutParams valueLp =
                new LinearLayout.LayoutParams(host.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.leftMargin = host.dp(8);
        row.addView(value, valueLp);

        TextView del = Style.label(ctx, "✕", 14, Style.DANGER, true);
        del.setGravity(Gravity.CENTER);
        del.setBackground(Style.ripple(ctx, Style.round(ctx, 0x00000000, 17), 17));
        del.setClickable(true);
        del.setOnClickListener(v -> {
            Object key = ((EditText) row.getChildAt(1)).getTag();
            if (key instanceof String) host.onDeleteFlag((String) key);
        });
        LinearLayout.LayoutParams delLp =
                new LinearLayout.LayoutParams(host.dp(34), host.dp(34));
        delLp.leftMargin = host.dp(4);
        row.addView(del, delLp);
        return row;
    }
}
