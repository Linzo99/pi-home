package com.sall.pitv;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * The highlighted channel playing small and muted in TV en direct, once the highlight rests on
 * it. The player is released as soon as it's not shown: the Pi's video decoder is needed whole
 * for full-screen playback.
 */
final class Preview {
    /** how long the highlight rests on a channel before it plays */
    private static final long DELAY = 900;

    private final Context c;
    private final Catalog cat;
    private final SurfaceView surface;
    /** shown over the surface until the picture comes: the logo, or why there's none */
    private final View cover;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private ExoPlayer player;
    private Model.Item pending;
    private final Runnable start = this::start;

    Preview(Context c, Catalog cat, SurfaceView surface, View cover) {
        this.c = c;
        this.cat = cat;
        this.surface = surface;
        this.cover = cover;
    }

    /** plays [ch] once the highlight stays on it */
    void show(Model.Item ch) {
        ui.removeCallbacks(start);
        if (player != null) player.stop();
        cover.setVisibility(View.VISIBLE);
        pending = ch;
        ui.postDelayed(start, DELAY);
    }

    void stop() {
        ui.removeCallbacks(start);
        pending = null;
        cover.setVisibility(View.VISIBLE);
        if (player != null) player.release();
        player = null;
    }

    private void start() {
        Model.Item ch = pending;
        if (ch == null || ch.url == null || ch.url.isEmpty()) return;
        if (player == null) create();
        android.content.SharedPreferences pp = c.getSharedPreferences("player", Context.MODE_PRIVATE);
        // the same address and route the full-screen player found working
        String url = ch.url;
        if (Source.XTREAM.equals(cat.source.type) && url.endsWith(".ts") && pp.getBoolean("hls." + cat.source.id(), false)) {
            url = url.substring(0, url.length() - 3) + ".m3u8";
        }
        java.net.InetSocketAddress proxy = cat.source.proxyAddress();
        boolean viaProxy = proxy != null && (ch.name.toLowerCase(Locale.ROOT).contains("geo-blocked") || pp.getBoolean("geo." + ch.key(), false));
        OkHttpClient.Builder b = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS);
        if (viaProxy) b.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, proxy));
        MediaItem.Builder m = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) m.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaSource(new DefaultMediaSourceFactory(new OkHttpDataSource.Factory(b.build()).setUserAgent(Net.agent)).createMediaSource(m.build()));
        player.prepare();
        player.play();
    }

    private void create() {
        player = new ExoPlayer.Builder(c)
                // a few seconds are enough for a preview, and keep memory for the rest
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(2_000, 8_000, 1_000, 1_000)
                        .setTargetBufferBytes(12 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build())
                .build();
        player.setVolume(0f);
        // the lightest quality, and no switching: the Pi's decoder fails on quality changes
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setForceLowestBitrate(true).build());
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                cover.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                // the logo stays; full screen has its own retries
                cover.setVisibility(View.VISIBLE);
            }
        });
    }
}
