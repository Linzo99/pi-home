package com.sall.pihome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Soft ambient glow behind the top shelf, tinted with the focused app's colour. */
public class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFF3A3A48, target = color;
    private float cx = 0.24f, cy = 0.02f;
    private ValueAnimator anim;

    public GlowView(Context c) {
        super(c);
    }

    public void setColor(int to) {
        target = to;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofArgb(color, to);
        anim.setDuration(600);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            color = (int) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    public void setColorNow(int to) {
        if (anim != null) anim.cancel();
        color = target = to;
        invalidate();
    }

    public int getTargetColor() {
        return target;
    }

    /** glow centre as a fraction of the view size */
    public void setCenter(float fx, float fy) {
        cx = fx;
        cy = fy;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        paint.setShader(new RadialGradient(w * cx, h * cy, w * 0.72f,
                new int[] {withAlpha(color, 0x8C), withAlpha(color, 0x2E), Color.TRANSPARENT},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }
}
