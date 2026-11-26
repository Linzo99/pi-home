package com.sall.pihome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * An app's icon as a full square picture, drawn once at a size larger than shown so it stays sharp
 * when scaled down and grown on focus. Adapted from tvOS Launcher's icon builder
 * (github.com/techgenius1/tvOS-Launcher, Apache 2.0).
 */
final class Icons {
    static final int SIZE = 256;

    static Bitmap square(String pkg, Drawable icon, Drawable banner, int color) {
        int s = SIZE;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        boolean white = "com.android.vending".equals(pkg);
        if (white) c.drawColor(Color.WHITE);
        else {
            // the app's colour, lighter at the top
            float[] hsv = new float[3];
            Color.colorToHSV(color != 0 ? color : 0xFF24262E, hsv);
            int top = Color.HSVToColor(new float[] {hsv[0], Math.min(1f, hsv[1] * 0.9f), Math.max(0.28f, Math.min(0.85f, hsv[2] * 1.35f))});
            int bottom = Color.HSVToColor(new float[] {hsv[0], hsv[1], Math.max(0.14f, Math.min(0.55f, hsv[2] * 0.85f))});
            p.setShader(new LinearGradient(0, 0, 0, s, top, bottom, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, s, s, p);
            p.setShader(null);
        }

        if (icon instanceof AdaptiveIconDrawable) {
            // its layers bleed past the square, as the system crops them
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            int in = -Math.round(s * 0.24f);
            for (Drawable d : new Drawable[] {white ? null : a.getBackground(), a.getForeground()}) {
                if (d == null) continue;
                Rect old = d.copyBounds();
                d.setBounds(in, in, s - in, s - in);
                d.draw(c);
                d.setBounds(old);
            }
            return out;
        }
        if (icon == null) return out;

        Bitmap tmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Rect old = icon.copyBounds();
        icon.setBounds(0, 0, s, s);
        icon.draw(new Canvas(tmp));
        icon.setBounds(old);
        // the drawn part, without the transparent margin many old icons have
        int[] px = new int[s * s];
        tmp.getPixels(px, 0, s, 0, 0, s, s);
        int minX = s, minY = s, maxX = 0, maxY = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (Color.alpha(px[y * s + x]) > 32) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX <= minX || maxY <= minY) {
            c.drawBitmap(tmp, 0, 0, p);
            tmp.recycle();
            return out;
        }
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        // an icon that is itself a filled square (rounded or not) fills the whole tile, past its
        // own corners, over a gradient of its own top and bottom colours
        boolean nearlySquare = Math.abs(bw - bh) <= Math.max(bw, bh) * 0.18f;
        int ix = Math.max(6, Math.round(bw * 0.14f)), iy = Math.max(6, Math.round(bh * 0.14f));
        int x0 = minX + ix, y0 = minY + iy, x1 = maxX - ix, y1 = maxY - iy;
        boolean filled = nearlySquare && x1 > x0 && y1 > y0
                && Color.alpha(px[y0 * s + x0]) > 200 && Color.alpha(px[y0 * s + x1]) > 200
                && Color.alpha(px[y1 * s + x0]) > 200 && Color.alpha(px[y1 * s + x1]) > 200;
        Rect src = new Rect(minX, minY, maxX + 1, maxY + 1);
        if (filled) {
            int topEdge = px[y0 * s + minX + bw / 2], bottomEdge = px[y1 * s + minX + bw / 2];
            if (Color.alpha(topEdge) > 180 && Color.alpha(bottomEdge) > 180) {
                p.setShader(new LinearGradient(0, 0, 0, s, topEdge | 0xFF000000, bottomEdge | 0xFF000000, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
            }
            int zoom = Math.round(s * 0.06f);
            c.drawBitmap(tmp, src, new Rect(-zoom, -zoom, s + zoom, s + zoom), p);
        } else {
            int pad = Math.round(s * 0.16f);
            c.drawBitmap(tmp, src, new Rect(pad, pad, s - pad, s - pad), p);
        }
        tmp.recycle();
        return out;
    }

    /** a light icon (Play Store, YouTube): a white focus frame would vanish on it */
    static boolean light(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        float[][] pts = {{0.14f, 0.14f}, {0.86f, 0.14f}, {0.14f, 0.86f}, {0.86f, 0.86f},
                {0.5f, 0.08f}, {0.5f, 0.92f}, {0.08f, 0.5f}, {0.92f, 0.5f}};
        float sum = 0;
        int n = 0;
        for (float[] pt : pts) {
            int c = b.getPixel(Math.round(pt[0] * (w - 1)), Math.round(pt[1] * (h - 1)));
            if (Color.alpha(c) < 128) continue;
            sum += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
            n++;
        }
        return n > 0 && sum / n >= 0.78f;
    }

    private Icons() {
    }
}
