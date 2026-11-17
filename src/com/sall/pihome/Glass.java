package com.sall.pihome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Frosted glass: each panel shows a blurred copy of what lies behind it, under a light tint, a
 * sheen at the top and a bright rim. The blur is made once per change of the background, on a
 * picture a tenth of the screen's size, so drawing a panel costs one bitmap fill.
 */
final class Glass {
    /** a blurred picture of the screen behind the panels that use it */
    static final class Source {
        private Bitmap bmp;
        private float scale = 1;
        private final List<WeakReference<Panel>> panels = new ArrayList<>();

        /** draws [what] (screen coordinates) at a tenth of the size, blurs it, and repaints the panels */
        void capture(int screenW, int screenH, java.util.function.Consumer<Canvas> what) {
            if (screenW <= 0 || screenH <= 0) return;
            float s = 10f;
            Bitmap b = Bitmap.createBitmap((int) Math.ceil(screenW / s), (int) Math.ceil(screenH / s), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.BLACK);
            c.scale(1 / s, 1 / s);
            try {
                what.accept(c);
            } catch (Exception ignored) {
                // a view that can't draw in software: the panels keep their previous picture
                return;
            }
            blur(b, 3);
            bmp = b;
            scale = s;
            invalidate();
        }

        /** the panels redraw against the picture, e.g. after their page scrolled */
        void invalidate() {
            for (int i = panels.size() - 1; i >= 0; i--) {
                Panel p = panels.get(i).get();
                if (p == null) panels.remove(i);
                else p.invalidateSelf();
            }
        }
    }

    /** a rounded glass surface: set it as a view's background */
    static final class Panel extends Drawable {
        private final View host;
        private final Source source;
        private final float radius, rimWidth;
        private final int tint;
        private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF r = new RectF();
        private final int[] at = new int[2];
        private Bitmap shaderFor;
        private BitmapShader shader;
        private int shadedHeight = -1;

        Panel(View host, Source source, float radius, int tint, float rimWidth) {
            this.host = host;
            this.source = source;
            this.radius = radius;
            this.tint = tint;
            this.rimWidth = rimWidth;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(rimWidth);
            if (source != null) source.panels.add(new WeakReference<>(this));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            r.set(b);
            if (source != null && source.bmp != null) {
                if (shaderFor != source.bmp) {
                    shaderFor = source.bmp;
                    shader = new BitmapShader(shaderFor, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    blurPaint.setShader(shader);
                }
                // the picture is in screen coordinates: line it up with where the panel is
                host.getLocationOnScreen(at);
                float sx = host.getScaleX() == 0 ? 1 : host.getScaleX();
                matrix.setScale(source.scale, source.scale);
                matrix.postTranslate(-at[0], -at[1]);
                matrix.postScale(1 / sx, 1 / sx);
                shader.setLocalMatrix(matrix);
                canvas.drawRoundRect(r, radius, radius, blurPaint);
            }
            fill.setColor(tint);
            canvas.drawRoundRect(r, radius, radius, fill);
            if (shadedHeight != b.height()) {
                shadedHeight = b.height();
                sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                        0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                        0x66FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(r, radius, radius, sheen);
            float h = rimWidth / 2;
            r.inset(h, h);
            canvas.drawRoundRect(r, radius - h, radius - h, rim);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** the sheen and rim alone, over something opaque such as an app's banner */
    static Drawable rim(float radius, float width) {
        return new Panel(null, null, radius, Color.TRANSPARENT, width);
    }

    /** three box blurs in each direction, close to a gaussian */
    private static void blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, radius, true);
            boxBlur(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        float div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int start = horizontal ? l * w : l;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[start + clamp(k, len) * step];
                sa += p >>> 24;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[start + i * step] = (Math.round(sa / div) << 24) | (Math.round(sr / div) << 16)
                        | (Math.round(sg / div) << 8) | Math.round(sb / div);
                int add = in[start + clamp(i + r + 1, len) * step], sub = in[start + clamp(i - r, len) * step];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private Glass() {
    }
}
