package com.sall.pihome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A dark name bubble with a small pointer at the bottom, toward what it names. Shape and colours
 * after tvOS Launcher's dock tooltip (github.com/techgenius1/tvOS-Launcher, Apache 2.0).
 */
final class Bubble extends Drawable {
    private final float radius, pointerW, pointerH, stroke;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int builtW = -1, builtH = -1;

    Bubble(float radius, float pointerW, float pointerH, float stroke) {
        this.radius = radius;
        this.pointerW = pointerW;
        this.pointerH = pointerH;
        this.stroke = stroke;
        fill.setColor(0xEB1C1E24);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        border.setColor(0x2EFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() != builtW || b.height() != builtH) {
            builtW = b.width();
            builtH = b.height();
            float h = stroke / 2, l = h, t = h, r = b.width() - h, bottom = b.height() - pointerH, cx = b.width() / 2f;
            float cr = Math.min(radius, Math.min((r - l) / 2, (bottom - t) / 2));
            path.reset();
            path.moveTo(l + cr, t);
            path.lineTo(r - cr, t);
            path.quadTo(r, t, r, t + cr);
            path.lineTo(r, bottom - cr);
            path.quadTo(r, bottom, r - cr, bottom);
            path.lineTo(cx + pointerW / 2, bottom);
            path.lineTo(cx, bottom + pointerH);
            path.lineTo(cx - pointerW / 2, bottom);
            path.lineTo(l + cr, bottom);
            path.quadTo(l, bottom, l, bottom - cr);
            path.lineTo(l, t + cr);
            path.quadTo(l, t, l + cr, t);
            path.close();
            path.offset(b.left, b.top);
        }
        c.drawPath(path, fill);
        c.drawPath(path, border);
    }

    @Override
    public void setAlpha(int a) {
        fill.setAlpha(a * 0xEB / 255);
        border.setAlpha(a * 0x2E / 255);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
