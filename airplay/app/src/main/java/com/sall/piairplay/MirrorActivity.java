package com.sall.piairplay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** The mirrored screen, full screen; also shows the receiver's name when nothing is mirrored. */
public class MirrorActivity extends Activity {
    private Receiver receiver;
    private FrameLayout root;
    private SurfaceView surface;
    private TextView idle;
    private int videoW = 16, videoH = 9;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AirPlayService.start(this);
        receiver = Receiver.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        idle = new TextView(this);
        idle.setTextColor(0xB3FFFFFF);
        idle.setTextSize(22);
        idle.setGravity(Gravity.CENTER);
        idle.setText("AirPlay prêt\n\nSur le Mac ou l'iPhone, choisissez « " + AirPlayService.NAME
                + " » dans Recopie de l'écran.\nRetour pour quitter.");
        root.addView(idle, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());
        setContentView(root);

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                receiver.video.attach(h.getSurface(), (w, hh) -> runOnUiThread(() -> {
                    videoW = w;
                    videoH = hh;
                    idle.setVisibility(View.GONE);
                    fit();
                }));
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                receiver.video.detach();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        receiver.setScreen(this);
        hideBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        receiver.setScreen(null);
    }

    /** the largest size with the picture's proportions */
    private void fit() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int sw = w, sh = (int) ((long) w * videoH / videoW);
        if (sh > h) {
            sh = h;
            sw = (int) ((long) h * videoW / videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != sw || lp.height != sh) {
            lp.width = sw;
            lp.height = sh;
            surface.setLayoutParams(lp);
        }
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
