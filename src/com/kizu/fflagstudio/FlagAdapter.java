package com.kizu.fflagstudio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
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
        row.setPadding(0, host.dp(4), 0, host.dp(4));

        TextView name = new TextView(ctx);
        name.setTextColor(0xFFE6E6E6);
        name.setTextSize(12);
        name.setTypeface(Typeface.MONOSPACE);
        name.setPadding(0, 0, host.dp(8), 0);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText value = new EditText(ctx);
        value.setTextColor(0xFFFFD54F);
        value.setTextSize(12);
        value.setTypeface(Typeface.MONOSPACE);
        value.setSingleLine(true);
        // commit on blur: the row may be recycled for another flag before the next Apply
        value.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            Object key = v.getTag();
            if (key instanceof String) {
                host.onRowValue((String) key, ((EditText) v).getText().toString().trim());
            }
        });
        value.setBackgroundTintList(ColorStateList.valueOf(0xFF33333C));
        row.addView(value, new LinearLayout.LayoutParams(host.dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button del = new Button(ctx);
        del.setText("✕");
        del.setTextSize(12);
        del.setTextColor(0xFFFF6E6E);
        del.setBackgroundColor(Color.TRANSPARENT);
        del.setPadding(0, 0, 0, 0);
        del.setOnClickListener(v -> {
            Object key = ((EditText) row.getChildAt(1)).getTag();
            if (key instanceof String) host.onDeleteFlag((String) key);
        });
        row.addView(del, new LinearLayout.LayoutParams(host.dp(38),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
}
