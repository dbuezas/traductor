package com.dbuezas.traductor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Colors (light or dark, following the phone) and small view helpers shared by the screens. */
final class Ui {
    static final int WHITE = 0xFFFFFFFF;   // text and icons on accent or red
    static final int RED = 0xFFDC2626;

    static boolean dark;
    static int BG, CARD, TEXT, MUTED, FAINT, LINE, STRONG;

    /** One accent per person: bottom (A) and top (B); DARK = text on a card, TINT = that half's background. */
    static int A, A_DARK, A_TINT, B, B_DARK, B_TINT;

    /** Picks the palette for the phone's current light/dark mode. Call before building views. */
    static void init(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            BG = 0xFF0F1115; CARD = 0xFF1C1F26; TEXT = 0xFFF3F4F6; MUTED = 0xFF9CA3AF;
            FAINT = 0xFF6B7280; LINE = 0xFF2A2E37; STRONG = 0xFF374151;
            A = 0xFF6366F1; A_DARK = 0xFFA5B4FC; A_TINT = 0xFF15172B;
            B = 0xFF0F9F90; B_DARK = 0xFF5EEAD4; B_TINT = 0xFF0E1F1E;
        } else {
            BG = 0xFFF3F4F6; CARD = 0xFFFFFFFF; TEXT = 0xFF111827; MUTED = 0xFF6B7280;
            FAINT = 0xFF9CA3AF; LINE = 0xFFE5E7EB; STRONG = 0xFF111827;
            A = 0xFF4F46E5; A_DARK = 0xFF3730A3; A_TINT = 0xFFEEF0FE;
            B = 0xFF0D9488; B_DARK = 0xFF115E59; B_TINT = 0xFFE6F6F4;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Rounded rect with its own corner radii: top-left, top-right, bottom-right, bottom-left. */
    static GradientDrawable round(int color, float tl, float tr, float br, float bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return d;
    }

    static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** A pill-shaped clickable label. */
    static TextView pill(Context c, String s, int bg, int fg) {
        TextView t = text(c, s, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        t.setBackground(ripple(round(bg, dp(c, 100)), 0x33000000));
        t.setClickable(true);
        return t;
    }

    /** Dialog theme matching the palette. */
    static int dialogTheme() {
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private Ui() {}
}
