package com.sall.piairplay;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.lang.ref.WeakReference;

/** What the AirPlay server receives, handed to the video and audio pipes and the mirror screen. */
final class Receiver implements Playback {
    private static final String TAG = "PiAirPlay";
    private static Receiver instance;

    final VideoPipe video = new VideoPipe();
    final AudioPipe audio = new AudioPipe();
    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WifiManager.WifiLock wifiLock;
    private volatile boolean mirroring;
    private WeakReference<MirrorActivity> screen = new WeakReference<>(null);

    static synchronized Receiver get(Context c) {
        if (instance == null) instance = new Receiver(c.getApplicationContext());
        return instance;
    }

    private Receiver(Context app) {
        this.app = app;
        // no Wi-Fi power saving while a screen is mirrored: less delay and fewer stutters
        wifiLock = app.getSystemService(WifiManager.class)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pi-airplay");
        wifiLock.setReferenceCounted(false);
    }

    boolean isMirroring() {
        return mirroring;
    }

    void setScreen(MirrorActivity a) {
        screen = new WeakReference<>(a);
    }

    @Override
    public void onVideoFormat(VideoStreamInfo info) {
        Log.i(TAG, "mirroring started");
        mirroring = true;
        wifiLock.acquire();
        ui.post(this::showScreen);
    }

    void showScreen() {
        app.startActivity(new Intent(app, MirrorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public void onVideo(byte[] bytes) {
        video.onFrame(bytes);
    }

    @Override
    public void onVideoSrcDisconnect() {
        Log.i(TAG, "mirroring stopped");
        mirroring = false;
        video.reset();
        if (wifiLock.isHeld()) wifiLock.release();
        ui.post(() -> {
            MirrorActivity a = screen.get();
            if (a != null) a.finish();
        });
    }

    @Override
    public void onAudioFormat(AudioStreamInfo info) {
        audio.start(info);
    }

    @Override
    public void onAudio(byte[] bytes) {
        audio.onAudio(bytes);
    }

    @Override
    public void onAudioSrcDisconnect() {
        audio.stop();
    }

    @Override
    public void onVolume(double volumeLinear) {
        audio.setVolume((float) volumeLinear);
    }

    @Override
    public double volume() {
        return audio.volume();
    }
}
