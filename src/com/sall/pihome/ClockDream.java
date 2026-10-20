package com.sall.pihome;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.Random;

/** Screensaver: a thin clock that drifts to a new spot every minute, so nothing burns in. */
public class ClockDream extends DreamService {
    private static final long DRIFT_MS = 60_000;
    /** after midnight, an hour without input (screensaver delay included) puts the Pi to sleep */
    private static final int NIGHT_END = 6;
    private static final long NIGHT_IDLE_MS = 60 * 60_000L;
    private long dreamingSince;
    private boolean sleepRequested;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root;
    private LinearLayout clock;
    private GlowView glow;
    private TextView greeting;

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            clock.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                place();
                clock.animate().alpha(1f).setDuration(900).start();
            }).start();
            maybeSleep();
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    private void maybeSleep() {
        if (sleepRequested || Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= NIGHT_END) return;
        long before = Settings.System.getLong(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60_000L);
        if (before + SystemClock.elapsedRealtime() - dreamingSince < NIGHT_IDLE_MS) return;
        sleepRequested = true;
        // the LG remote bridge runs as root and carries this out, as for the Veille button
        try (FileOutputStream o = openFileOutput("request", MODE_PRIVATE)) {
            o.write("sleep".getBytes());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        glow = new GlowView(this);
        glow.setColorNow(getSharedPreferences("home", MODE_PRIVATE).getInt("glow", 0xFF3A3A48));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.START);
        greeting = text(20, 0xB3FFFFFF, "sans-serif-light");
        greeting.setText(MainActivity.greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        style(time, 96, Color.WHITE, "sans-serif-thin");
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        style(date, 18, 0x99FFFFFF, "sans-serif-light");
        clock.addView(greeting);
        clock.addView(time);
        clock.addView(date);
        root.addView(clock, new FrameLayout.LayoutParams(-2, -2));
        setContentView(root);
        root.post(this::place);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreamingSince = SystemClock.elapsedRealtime();
        sleepRequested = false;
        handler.postDelayed(drift, DRIFT_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(drift);
        super.onDreamingStopped();
    }

    private void place() {
        int w = root.getWidth(), h = root.getHeight();
        int cw = clock.getWidth(), ch = clock.getHeight();
        if (w == 0 || cw == 0) return;
        int pad = w / 16;
        float x = pad + random.nextFloat() * Math.max(1, w - cw - 2 * pad);
        float y = pad + random.nextFloat() * Math.max(1, h - ch - 2 * pad);
        clock.setX(x);
        clock.setY(y);
        glow.setCenter((x + cw / 2f) / w, (y + ch / 2f) / h);
        greeting.setText(MainActivity.greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
    }

    private TextView text(float sp, int color, String font) {
        TextView t = new TextView(this);
        style(t, sp, color, font);
        return t;
    }

    private void style(TextView t, float sp, int color, String font) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(font, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
    }
}
