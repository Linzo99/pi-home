package com.sall.pitv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** The look: Netflix black and red, white focus, and lists that keep the highlight in place. */
final class Ui {
    static final int RED = 0xFFE50914, BG = 0xFF141414, CARD = 0xFF2A2A2A, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView line(Context c, float sp, int color, boolean bold) {
        TextView t = text(c, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable outline(int stroke, int width, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(width, stroke);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** grows a card when highlighted, with a white frame drawn over it */
    static void focusCard(View v, float scale) {
        Context c = v.getContext();
        Drawable frame = outline(Color.WHITE, dp(c, 2.5f), dp(c, 6));
        v.setOnFocusChangeListener((x, has) -> {
            v.setForeground(has ? frame : null);
            v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).translationZ(has ? dp(c, 8) : 0)
                    .setInterpolator(new OvershootInterpolator(1.6f)).setDuration(180).start();
        });
    }

    /**
     * a pill button: grey, white when highlighted. Returns the view; the icon may be 0.
     */
    static TextView button(Context c, int icon, String label, View.OnClickListener click) {
        TextView b = text(c, 14, Color.WHITE, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 18), dp(c, 8));
        if (icon != 0) {
            Drawable d = c.getDrawable(icon).mutate();
            d.setBounds(0, 0, dp(c, 18), dp(c, 18));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 8));
        }
        Runnable style = () -> {
            boolean has = b.hasFocus();
            b.setBackground(rounded(has ? Color.WHITE : 0x40FFFFFF, dp(c, 6)));
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setCompoundDrawableTintList(ColorStateList.valueOf(has ? Color.BLACK : Color.WHITE));
        };
        style.run();
        b.setOnFocusChangeListener((v, has) -> {
            style.run();
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(click);
        return b;
    }

    static void setIcon(TextView b, int icon) {
        Drawable d = b.getContext().getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(b.getContext(), 18), dp(b.getContext(), 18));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawableTintList(ColorStateList.valueOf(b.hasFocus() ? Color.BLACK : Color.WHITE));
    }

    static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /**
     * a list whose highlighted entry stays at the start (left, or top) instead of the list
     * scrolling only when the highlight reaches the edge, as on Netflix
     */
    static final class PinnedLayout extends LinearLayoutManager {
        private final int offset;

        PinnedLayout(Context c, int orientation, int offsetPx) {
            super(c, orientation, false);
            offset = offsetPx;
            setItemPrefetchEnabled(true);
        }

        @Override
        public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
            int d = getOrientation() == HORIZONTAL ? child.getLeft() - parent.getPaddingLeft() - offset
                    : child.getTop() - parent.getPaddingTop() - offset;
            if (d == 0) return false;
            if (getOrientation() == HORIZONTAL) parent.smoothScrollBy(d, 0);
            else parent.smoothScrollBy(0, d);
            return true;
        }

        @Override
        protected int getExtraLayoutSpace(RecyclerView.State state) {
            // lay out a screen ahead, so the next posters are ready before the highlight arrives
            return getOrientation() == HORIZONTAL ? getWidth() : getHeight() / 2;
        }
    }

    static String clock(long t) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(new java.util.Date(t));
    }

    static String duration(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + " h " + String.format(java.util.Locale.FRANCE, "%02d", m % 60) : m + " min";
    }

    private Ui() {
    }
}
