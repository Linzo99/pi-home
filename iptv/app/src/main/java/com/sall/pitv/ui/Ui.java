package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

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

/**
 * The look: black and red, glass panels (translucent, a light edge and a sheen at the top, over
 * a blurred picture where there is one), white focus, and lists that keep the highlight in place.
 */
public final class Ui {
    public static final int RED = 0xFFE50914, BG = 0xFF141414, CARD = 0xFF2A2A2A, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView line(Context c, float sp, int color, boolean bold) {
        TextView t = text(c, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /**
     * a glass panel: translucent white, a thin light edge, and a sheen fading down from its top
     * edge. [level] 0 for a quiet panel, 1 for a selected one.
     */
    static Drawable glass(Context c, float radiusPx, int level) {
        GradientDrawable body = new GradientDrawable();
        body.setColor(level > 0 ? 0x47FFFFFF : 0x1FFFFFFF);
        body.setCornerRadius(radiusPx);
        body.setStroke(Math.max(1, dp(c, 1)), level > 0 ? 0x66FFFFFF : 0x38FFFFFF);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {level > 0 ? 0x33FFFFFF : 0x1AFFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radiusPx);
        return new android.graphics.drawable.LayerDrawable(new Drawable[] {body, sheen});
    }

    /** blurs what a view draws (the picture behind a glass panel) */
    static void blur(View v, float radiusPx) {
        v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP));
    }

    /**
     * lets a view that grows on focus draw past its layouts' edges instead of being cut: the
     * layouts around it stop clipping, up to the first one that scrolls (which must keep hiding
     * what is scrolled away)
     */
    static void unclip(View v) {
        android.view.ViewParent p = v.getParent();
        for (int i = 0; i < 4 && p instanceof android.view.ViewGroup; i++, p = p.getParent()) {
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            if (g instanceof android.widget.ScrollView || g instanceof android.widget.HorizontalScrollView
                    || g instanceof android.widget.AbsListView || g.getClass().getName().contains("RecyclerView")) break;
            g.setClipChildren(false);
            g.setClipToPadding(false);
        }
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
    public static TextView button(Context c, int icon, String label, View.OnClickListener click) {
        TextView b = text(c, 14, Color.WHITE, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(c, 20), dp(c, 9), dp(c, 20), dp(c, 9));
        if (icon != 0) {
            Drawable d = c.getDrawable(icon).mutate();
            d.setBounds(0, 0, dp(c, 18), dp(c, 18));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 8));
        }
        Runnable style = () -> {
            boolean has = b.hasFocus();
            b.setBackground(has ? rounded(Color.WHITE, dp(c, 22)) : glass(c, dp(c, 22), 0));
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setCompoundDrawableTintList(ColorStateList.valueOf(has ? Color.BLACK : Color.WHITE));
        };
        style.run();
        b.setOnFocusChangeListener((v, has) -> {
            style.run();
            if (has) unclip(v);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(click);
        return b;
    }

    /** an on/off switch in a pill button: red track and knob at the right when on */
    static TextView toggle(Context c, boolean on, String what, View.OnClickListener click) {
        TextView b = button(c, 0, "", click);
        b.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        b.setCompoundDrawablePadding(0);
        Drawable d = new SwitchDrawable(on, dp(c, 2));
        d.setBounds(0, 0, dp(c, 40), dp(c, 22));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setContentDescription(what + (on ? " : activé" : " : désactivé"));
        return b;
    }

    /** draws itself in its own colours: the button's focus tint doesn't reach it */
    private static final class SwitchDrawable extends Drawable {
        private final boolean on;
        private final int inset;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        SwitchDrawable(boolean on, int inset) {
            this.on = on;
            this.inset = inset;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            Rect r = getBounds();
            float h = r.height(), radius = h / 2;
            paint.setColor(on ? RED : 0xFF6B6B6B);
            canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, radius, radius, paint);
            float knob = radius - inset;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(on ? r.right - radius : r.left + radius, r.top + radius, knob, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public static void setIcon(TextView b, int icon) {
        Drawable d = b.getContext().getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(b.getContext(), 18), dp(b.getContext(), 18));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawableTintList(ColorStateList.valueOf(b.hasFocus() ? Color.BLACK : Color.WHITE));
    }

    public static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /**
     * a list whose highlighted entry stays at the start (left, or top) instead of the list
     * scrolling only when the highlight reaches the edge, as on Netflix
     */
    public static final class PinnedLayout extends LinearLayoutManager {
        private final int offset;

        public PinnedLayout(Context c, int orientation, int offsetPx) {
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

    public static String clock(long t) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(new java.util.Date(t));
    }

    static String duration(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + " h " + String.format(java.util.Locale.FRANCE, "%02d", m % 60) : m + " min";
    }

    private Ui() {
    }
}
