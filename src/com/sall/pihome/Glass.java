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
 *
 * The livelier colours (saturation and lift before the blur), the lens band at the edge, the tint
 * that follows the brightness behind and the diagonal rim follow tvOS Launcher's "Liquid Glass"
 * (github.com/techgenius1/tvOS-Launcher, Apache 2.0).
 */
final class Glass {
    /** a blurred picture of the screen behind the panels that use it */
    static final class Source {
        private Bitmap bmp;
        private float scale = 1;
        private final List<WeakReference<Panel>> panels = new ArrayList<>();
        /** how much smaller the picture is drawn, and the blur's radius on it: the strength */
        private final float shrink;
        private final int blurRadius;

        Source() {
            this(10f, 3);
        }

        /** a lighter blur: drawn less small (shrink 4 instead of 10), blurred less */
        Source(float shrink, int blurRadius) {
            this.shrink = shrink;
            this.blurRadius = blurRadius;
        }

        /** draws [what] (screen coordinates) at a tenth of the size, blurs it, and repaints the panels */
        void capture(int screenW, int screenH, java.util.function.Consumer<Canvas> what) {
            if (screenW <= 0 || screenH <= 0) return;
            float s = shrink;
            int w = (int) Math.ceil(screenW / s), h = (int) Math.ceil(screenH / s);
            Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            c.drawColor(Color.BLACK);
            c.scale(1 / s, 1 / s);
            try {
                what.accept(c);
            } catch (Exception ignored) {
                // a view that can't draw in software: the panels keep their previous picture
                return;
            }
            // glass makes what is behind it look more vivid: more saturated, a little lifted
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(1.6f);
            cm.postConcat(new android.graphics.ColorMatrix(new float[] {
                    1.05f, 0, 0, 0, 14, 0, 1.05f, 0, 0, 14, 0, 0, 1.05f, 0, 14, 0, 0, 0, 1, 0}));
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            new Canvas(b).drawBitmap(raw, 0, 0, p);
            raw.recycle();
            blur(b, blurRadius);
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
        private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix lensMatrix = new Matrix();
        private Bitmap shaderFor;
        private BitmapShader shader, lensShader;
        private int shadedHeight = -1, shadedWidth = -1;
        /** the width of the thick-glass band inside the edge; 0 for none */
        private float lens;
        /** how bright the rim is, 0 to 1 */
        private float rimStrength = 1f;
        /** the tint follows what is behind: darker over bright, a light frost over dark */
        private boolean adaptive;
        private int adaptiveTint;
        private Bitmap lumFor;
        private int lumX = Integer.MIN_VALUE, lumY;

        Panel(View host, Source source, float radius, int tint, float rimWidth) {
            this.host = host;
            this.source = source;
            this.radius = radius;
            this.tint = tint;
            this.rimWidth = rimWidth;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(rimWidth);
            lensPaint.setStyle(Paint.Style.STROKE);
            lensPaint.setAlpha(150);
            if (source != null) source.panels.add(new WeakReference<>(this));
        }

        /** a band inside the edge showing the background slightly magnified, as thick glass bends it */
        Panel lens(float width) {
            lens = width;
            return this;
        }

        Panel rimStrength(float k) {
            rimStrength = k;
            shadedHeight = -1;
            return this;
        }

        Panel adaptive() {
            adaptive = true;
            return this;
        }

        private int rimA(int a) {
            return (Math.round(a * rimStrength) << 24) | 0xFFFFFF;
        }

        /** darker over bright artwork so white text stays readable, a light frost over dark */
        private static int tintFor(float lum) {
            if (lum >= 0.45f) return Math.round(0x1F + (Math.min(lum, 0.85f) - 0.45f) / 0.4f * (0x5C - 0x1F)) << 24;
            return (Math.round((0.45f - Math.max(lum, 0.05f)) / 0.4f * 0x24) << 24) | 0xFFFFFF;
        }

        /** the average brightness behind the panel, from 16 points of the blurred picture */
        private float luminanceBehind(Bitmap b, float scale, float w, float h) {
            int x0 = Math.max(0, (int) (at[0] / scale)), y0 = Math.max(0, (int) (at[1] / scale));
            int x1 = Math.min(b.getWidth() - 1, (int) ((at[0] + w) / scale)), y1 = Math.min(b.getHeight() - 1, (int) ((at[1] + h) / scale));
            if (x1 < x0 || y1 < y0) return 0.3f;
            float sum = 0;
            for (int j = 0; j < 4; j++) {
                for (int i = 0; i < 4; i++) {
                    int px = b.getPixel(x0 + (x1 - x0) * i / 3, y0 + (y1 - y0) * j / 3);
                    sum += (0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px)) / 255f;
                }
            }
            return sum / 16f;
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
                    lensShader = new BitmapShader(shaderFor, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    lensPaint.setShader(lensShader);
                }
                // the picture is in screen coordinates: line it up with where the panel is
                host.getLocationOnScreen(at);
                float sx = host.getScaleX() == 0 ? 1 : host.getScaleX();
                matrix.setScale(source.scale, source.scale);
                matrix.postTranslate(-at[0], -at[1]);
                matrix.postScale(1 / sx, 1 / sx);
                shader.setLocalMatrix(matrix);
                canvas.drawRoundRect(r, radius, radius, blurPaint);
                if (lens > 0) {
                    float lw = Math.min(lens, Math.min(r.width(), r.height()) / 3f);
                    lensMatrix.set(matrix);
                    lensMatrix.postScale(1.04f, 1.04f, r.centerX(), r.centerY());
                    lensShader.setLocalMatrix(lensMatrix);
                    lensPaint.setStrokeWidth(lw);
                    canvas.drawRoundRect(r.left + lw / 2, r.top + lw / 2, r.right - lw / 2, r.bottom - lw / 2,
                            radius - lw / 2, radius - lw / 2, lensPaint);
                }
                if (adaptive && (source.bmp != lumFor || Math.abs(at[0] - lumX) > 32 || Math.abs(at[1] - lumY) > 32)) {
                    lumFor = source.bmp;
                    lumX = at[0];
                    lumY = at[1];
                    adaptiveTint = tintFor(luminanceBehind(source.bmp, source.scale, r.width() * sx, r.height() * sx));
                }
            }
            fill.setColor(adaptive && lumFor != null ? adaptiveTint : tint);
            canvas.drawRoundRect(r, radius, radius, fill);
            if (shadedHeight != b.height() || shadedWidth != b.width()) {
                shadedHeight = b.height();
                shadedWidth = b.width();
                sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                        0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                // bright at the top-left and bottom-right edges, as light catches a glass slab
                rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        new int[] {rimA(0xD9), rimA(0x40), rimA(0x14), rimA(0x26), rimA(0x8C)},
                        new float[] {0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
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
    static void blur(Bitmap b, int radius) {
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
