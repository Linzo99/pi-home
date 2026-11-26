package com.sall.pihome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The large clock in glass: the digits are filled with a blurred, brightened copy of the photo
 * behind them, under a frost that whitens toward their foot and a bright outline, over a soft
 * shadow. After tvOS Launcher's glass clock (github.com/techgenius1/tvOS-Launcher, Apache 2.0),
 * in Antonio Light (SIL Open Font License, see OFL-Antonio.txt).
 */
final class GlassClock extends View {
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
            frost = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final Matrix matrix = new Matrix();
    private final int[] at = new int[2];
    private final float density;
    private String time = "";
    private Bitmap texture;
    private BitmapShader shader;
    private int shadedFor = -1;

    GlassClock(Context c, float textSizePx) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        Typeface t;
        try {
            t = c.getResources().getFont(R.font.antonio);
        } catch (Exception e) {
            t = Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        for (Paint p : new Paint[] {shadow, fill, frost, stroke}) {
            p.setTypeface(t);
            p.setTextSize(textSizePx);
            p.setLetterSpacing(-0.02f);
            p.setFontVariationSettings("'wght' 300");
        }
        shadow.setColor(Color.argb(42, 0, 0, 0));
        fill.setColor(0xD9FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.7f, 1.2f * density));
        tick();
    }

    /** the minute changed */
    void tick() {
        String now = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date());
        if (now.equals(time)) return;
        boolean wider = now.length() != time.length();
        time = now;
        if (wider) requestLayout();
        invalidate();
    }

    /** the photo behind: the digits become glass over it; null for plain frosted white */
    void setPhoto(Bitmap photo) {
        texture = photo == null ? null : bake(photo);
        shader = texture == null ? null : new BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        fill.setShader(shader);
        invalidate();
    }

    /** the photo blurred small, a little brighter and more saturated, with a diagonal sheen */
    private static Bitmap bake(Bitmap src) {
        int w = 480, h = 270;
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.25f);
        cm.postConcat(new ColorMatrix(new float[] {1.08f, 0, 0, 0, 10, 0, 1.08f, 0, 0, 8, 0, 0, 1.08f, 0, 10, 0, 0, 0, 1, 0}));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(small, 0, 0, p);
        if (small != src) small.recycle();
        Glass.blur(out, 2);
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, 0, w * 0.45f, h,
                new int[] {Color.argb(68, 255, 255, 255), Color.argb(28, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.argb(26, 255, 255, 255)},
                new float[] {0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, sheen);
        return out;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        fill.getTextBounds("08:47", 0, 5, bounds);
        int w = Math.round(fill.measureText(time.isEmpty() ? "08:47" : time) + 4 * density);
        // the digits' height, plus room for the shadow
        setMeasuredDimension(w, Math.round(-bounds.top + 6 * density));
    }

    @Override
    protected void onDraw(Canvas c) {
        float x = 2 * density, base = getHeight() - 6 * density, top = base + bounds.top;
        if (shadedFor != getHeight()) {
            shadedFor = getHeight();
            frost.setShader(new LinearGradient(0, top, 0, base,
                    new int[] {Color.argb(84, 218, 224, 255), Color.argb(118, 236, 232, 255), Color.argb(168, 255, 242, 252)},
                    new float[] {0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            stroke.setShader(new LinearGradient(0, top, 0, base,
                    new int[] {Color.argb(228, 244, 248, 255), Color.argb(185, 232, 236, 255), Color.argb(250, 255, 255, 255)},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        if (shader != null) {
            // the texture covers the whole screen: line it up with where the clock is
            getLocationOnScreen(at);
            int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
            matrix.setScale(sw / (float) texture.getWidth(), sh / (float) texture.getHeight());
            matrix.postTranslate(-at[0], -at[1]);
            shader.setLocalMatrix(matrix);
        }
        c.drawText(time, x, base + 3.5f * density, shadow);
        c.drawText(time, x, base, fill);
        c.drawText(time, x, base, frost);
        c.drawText(time, x, base, stroke);
    }
}
