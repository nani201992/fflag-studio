package com.kizu.fflagstudio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** Code-only dark theme: no XML resources, no AndroidX. */
final class Style {
    static final int BG = 0xFF0B0B10;
    static final int SURFACE = 0xFF15151C;
    static final int SURFACE_HI = 0xFF1D1D26;
    static final int STROKE = 0xFF262631;
    static final int TEXT = 0xFFEDEDF2;
    static final int STATUS = 0xFFB4B4C2;
    static final int DIM = 0xFF8F8F9D;
    static final int ACCENT = 0xFF7C5CFF;
    static final int ACCENT_SOFT = 0xFF2A2145;
    static final int ACCENT_TEXT = 0xFFC9B8FF;
    static final int VALUE = 0xFFFFC857;
    static final int DANGER = 0xFFFF6B6B;

    private Style() {
    }

    static int dp(Context c, float v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable round(Context c, int color, float radiusDp, int strokeColor) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(dp(c, 1), strokeColor);
        return d;
    }

    static RippleDrawable ripple(Context c, GradientDrawable background, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), background,
                round(c, 0xFFFFFFFF, radiusDp));
    }

    static TextView label(Context c, String text, float sizeSp, int color, boolean medium) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    /** Every action button is one of these; `primary` is the filled accent one. */
    static TextView pill(Context c, String text, boolean primary) {
        TextView v = label(c, text, 13f, primary ? 0xFFFFFFFF : TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(primary
                ? ripple(c, round(c, ACCENT, 13), 13)
                : ripple(c, round(c, SURFACE_HI, 13, STROKE), 13));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }
}
