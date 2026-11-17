package com.sall.pitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full-screen playback. Reliability first: a stream that drops is reconnected by itself (with
 * the Xtream HLS address as a fallback for channels), a live stream that stalls is restarted,
 * and films and episodes resume where they were left.
 */
public class PlayerActivity extends Activity {
    private static List<Model.Item> queue = new ArrayList<>();
    private static int startIndex;
    private static long startPos;

    /** plays list[index]; pos in ms, or -1 for the saved position (0 = from the start) */
    static void play(Context c, List<Model.Item> list, int index, long pos) {
        queue = new ArrayList<>(list);
        startIndex = index;
        startPos = pos;
        c.startActivity(new Intent(c, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private List<Model.Item> items;
    private int index;
    private Model.Item item;
    private Library lib;
    private Catalog cat;
    private boolean live;
    /** the Xtream channel is tried as HLS after the MPEG-TS address fails */
    private boolean useHls;
    private DefaultMediaSourceFactory direct, proxied;
    private DefaultHttpDataSource.Factory http;
    private androidx.media3.datasource.okhttp.OkHttpDataSource.Factory okhttp;
    /** this title goes through the proxy */
    private boolean viaProxy;
    /** the last failure was the network's (not the decoder's): worth another route */
    private boolean lastErrorNetwork = true;
    /** this title has played already on the current route */
    private boolean playedOnRoute;
    private int retries;
    private long resumeAt;
    /** an add-on title's links tried so far, by address: the next one is taken when this fails */
    private final java.util.Set<String> tried = new java.util.HashSet<>();
    /** when the stream had to wait for data while playing, lately: too often means too slow */
    private final java.util.ArrayDeque<Long> stalls = new java.util.ArrayDeque<>();
    /** the connection's speed has been measured in this run (20 s of playing) */
    private static boolean measured;
    /** the link is being tried again without the headers its script gave */
    private boolean bare;
    /** a seek waits for data too: that wait isn't the connection's fault */
    private long seekedAt;
    /** a link that doesn't start in time is given up for the next */
    private final Runnable startTimeout = () -> nextLink(false, "Le lien ne démarre pas");
    /** stuck waiting for data for a while: a lighter link plays better on this connection */
    private final Runnable stallTimeout = () -> nextLink(true, "Connexion trop lente");
    private DefaultExtractorsFactory extractors;
    /** a live channel paused: recorded from then on, to resume, rewind or forward in it */
    private Timeshift shift;
    /** the player reads the recording (not the live stream); [shiftBase] is where it started */
    private boolean shiftPlaying;
    private long shiftBase;

    private FrameLayout root, videoBox;
    private SurfaceView surface;
    /** black over the previous title's last picture until the new one shows */
    private View cover;
    private TextView subtitles, message;
    private ProgressBar spinner;
    private LinearLayout overlay, buttons;
    private ImageView logo;
    private TextView title, subtitle, times, nextLine, playButton;
    private ProgressBar seek;
    private View seekRow;
    private LinearLayout chanPanel;
    private VideoSize videoSize = VideoSize.UNKNOWN;

    private long seekTarget = -1;
    /** the controls were opened to use them (OK, or the arrows on a film), not just shown */
    private boolean controlsActive;
    private int pendingZap = -1;

    private final Runnable hideOverlay = () -> {
        // a paused channel keeps its controls on screen, with the time it's been paused
        if (!livePaused()) showOverlay(false);
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTimes();
            saveProgress(false);
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable commitSeek = this::commitSeek;
    private final Runnable doZap = this::applyZap;
    private final Runnable stallCheck = () -> {
        // a live stream stuck buffering for 15 s rarely recovers by itself
        if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) reconnect("Le flux ne répond plus");
    };
    private final Runnable markWatched = () -> {
        if (live && player != null && player.isPlaying()) lib.watchedLive(item);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lib = Library.get(this);
        cat = Catalog.get(this);
        setContentView(build());
        createPlayer();
        startFromQueue();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        saveProgress(true);
        startFromQueue();
    }

    private void startFromQueue() {
        items = queue;
        index = Math.max(0, Math.min(startIndex, items.size() - 1));
        if (items.isEmpty()) {
            finish();
            return;
        }
        long pos = startPos;
        if (pos < 0) {
            Library.Progress p = lib.progress(items.get(index));
            pos = p != null && !p.finished() ? p.pos : 0;
        }
        load(index, pos);
    }

    /** the activity went to the background: standby, or Home */
    private boolean stopped;

    @Override
    protected void onStop() {
        stopped = true;
        endShift();
        saveProgress(true);
        if (player != null) player.pause();
        // the launcher's row, when Home is pressed during playback
        TvHome.sync(this);
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && item != null && stopped) {
            // back from standby: a live stream must start again from now
            if (live) start(0);
            else player.play();
        }
        stopped = false;
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        endShift();
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- player

    private void createPlayer() {
        http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Net.agent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000)
                .setKeepPostFor302Redirects(true);
        extractors = new DefaultExtractorsFactory()
                // IPTV streams often lack proper key frame flags; this starts the picture sooner
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(true);
        DefaultLoadErrorHandlingPolicy retry = new DefaultLoadErrorHandlingPolicy(6) {
            @Override
            public long getRetryDelayMsFor(@NonNull LoadErrorInfo info) {
                // a refused address won't work by asking again: fail at once, so the other
                // format, or the proxy, can be tried
                if (info.exception instanceof HttpDataSource.InvalidResponseCodeException) return C.TIME_UNSET;
                return Math.min(4_000, info.errorCount * 1_000L);
            }
        };
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this, http), extractors).setLoadErrorHandlingPolicy(retry);
        direct = sources;
        java.net.InetSocketAddress pa = cat.source.proxyAddress();
        if (pa != null) {
            // geo-blocked channels go through a proxy in another country; the rest stays direct
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, pa))
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .followSslRedirects(true)
                    .build();
            okhttp = new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client).setUserAgent(Net.agent);
            proxied = new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this, okhttp), extractors)
                    .setLoadErrorHandlingPolicy(retry);
        }
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                // FFmpeg for the AC3 / E-AC3 / DTS sound Android can't play
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
                // the Pi has 2 GB: cap the buffer at 64 MB whatever the bitrate
                .setTargetBufferBytes(64 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(sources)
                .setLoadControl(load)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.setVideoSurfaceView(surface);
        // the languages chosen last time, for every film and episode
        android.content.SharedPreferences pp = getSharedPreferences("player", MODE_PRIVATE);
        androidx.media3.common.TrackSelectionParameters.Builder tp = player.getTrackSelectionParameters().buildUpon();
        String audioLang = pp.getString("audioLang", null), subLang = pp.getString("subLang", null);
        if (audioLang != null) tp.setPreferredAudioLanguage(audioLang);
        if (subLang != null && !subLang.isEmpty()) tp.setPreferredTextLanguage(subLang).setSelectUndeterminedTextLanguage(false);
        // a channel's "default" subtitles stay off; only the chosen language (or forced ones) show
        tp.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT);
        // the Pi's hardware decoder fails when a stream switches quality on the way (e.g. 540p to
        // 720p): take the best one at once and keep it
        tp.setForceHighestSupportedBitrate(true);
        player.setTrackSelectionParameters(tp.build());
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                ui.removeCallbacks(stallCheck);
                ui.removeCallbacks(stallTimeout);
                if (s == Player.STATE_BUFFERING && live) ui.postDelayed(stallCheck, 15_000);
                if (s == Player.STATE_BUFFERING && canFallBack() && playedOnRoute && player.getPlayWhenReady()) {
                    // three waits in a minute and a half, or one of 12 s: the link is too heavy
                    long now = System.currentTimeMillis();
                    boolean seek = now - seekedAt < 3_000;
                    if (!seek) stalls.addLast(now);
                    while (!stalls.isEmpty() && now - stalls.peekFirst() > 90_000) stalls.removeFirst();
                    if (stalls.size() >= 3) {
                        nextLink(true, "Connexion trop lente");
                        return;
                    }
                    ui.postDelayed(stallTimeout, seek ? 25_000 : 12_000);
                }
                if (s == Player.STATE_READY) {
                    ui.removeCallbacks(startTimeout);
                    // it played bare: this server is asked without headers from now on
                    if (bare) getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("bare." + host(item.url), true).apply();
                    if (!live && item.source != null) saveSource(PlayerActivity.this, item);
                    message.setVisibility(View.GONE);
                    getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("geo." + item.key(), viaProxy).apply();
                    playedOnRoute = true;
                    if (live && Source.XTREAM.equals(cat.source.type) && item.url.endsWith(".ts")) {
                        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("hls." + cat.source.id(), useHls).apply();
                    }
                    if (resumeAt > 0) {
                        // the position is set at prepare; nothing to do but forget it
                        resumeAt = 0;
                    }
                }
                if (s == Player.STATE_ENDED) ended();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) {
                    // playing for 20 s in a row: the connection is fine again
                    ui.postDelayed(() -> {
                        if (player == null || !player.isPlaying()) return;
                        retries = 0;
                        // the speed measured while playing: the next fallback goes by it
                        measured = true;
                        getSharedPreferences("player", MODE_PRIVATE).edit().putLong("bandwidth",
                                androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(PlayerActivity.this).getBitrateEstimate()).apply();
                    }, 20_000);
                }
                playButton.setText(playing ? "Pause" : "Lecture");
                Ui.setIcon(playButton, playing ? R.drawable.ic_pause : R.drawable.ic_play);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                android.util.Log.w("PiTV", "playback " + e.getErrorCodeName(), e);
                if (shiftPlaying) {
                    String why = shift != null ? shift.error() : null;
                    toast((why != null ? why : "Le différé ne se lit plus") + " : retour au direct");
                    goLive();
                    return;
                }
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                // the Pi's hardware decoder sometimes fails when a stream restarts: that is worth
                // a few tries; only an unsupported format is final
                boolean unsupported = e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES;
                if (unsupported && retries > 0) {
                    fail("Ce format vidéo n'est pas lu par le Pi (" + e.getErrorCodeName() + ")");
                    return;
                }
                boolean network = e.errorCode >= 2000 && e.errorCode < 3000;
                lastErrorNetwork = network;
                // a link that never played is dead: the next one, at once; one that played gets
                // a couple of tries first
                // some servers refuse the Referer a script insists on (Vidlink's video host
                // answers 429 with it, 206 without): the same link once more, bare
                if (canFallBack() && !playedOnRoute && !bare && item.headers != null && !item.headers.isEmpty()
                        && e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    bare = true;
                    android.util.Log.i("PiTV", "retrying without the script's headers: " + host(item.url));
                    start(resumeAt);
                    return;
                }
                if (canFallBack() && (!playedOnRoute || retries >= 2)) {
                    nextLink(false, e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ? "Le serveur refuse ce lien"
                            : network ? "Ce lien ne répond pas" : "Ce lien ne se lit pas");
                    return;
                }
                reconnect(e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ? "Le serveur refuse le flux"
                        : network ? "Connexion perdue" : "Erreur de lecture");
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo from, @NonNull Player.PositionInfo to, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seekedAt = System.currentTimeMillis();
            }

            @Override
            public void onRenderedFirstFrame() {
                cover.setVisibility(View.GONE);
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize size) {
                videoSize = size;
                fitVideo();
            }

            @Override
            public void onCues(@NonNull CueGroup cues) {
                StringBuilder b = new StringBuilder();
                for (Cue c : cues.cues) if (c.text != null) b.append(b.length() > 0 ? "\n" : "").append(c.text);
                subtitles.setText(b);
                subtitles.setVisibility(b.length() == 0 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                buttons.findViewWithTag("audio").setVisibility(count(C.TRACK_TYPE_AUDIO) > 1 ? View.VISIBLE : View.GONE);
                buttons.findViewWithTag("subs").setVisibility(count(C.TRACK_TYPE_TEXT) > 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void load(int i, long pos) {
        // another title: no last picture of the previous one while this one starts
        if (item != null && !items.get(i).key().equals(item.key())) {
            player.stop();
            cover.setVisibility(View.VISIBLE);
        }
        endShift();
        index = i;
        item = items.get(i);
        live = item.kind == Model.LIVE;
        // the format that worked last time for this provider's channels
        playedOnRoute = false;
        lastErrorNetwork = true;
        useHls = live && getSharedPreferences("player", MODE_PRIVATE).getBoolean("hls." + cat.source.id(), false);
        // marked so in the playlist (iptv-org's "[Geo-blocked]"), or found so before
        viaProxy = proxied != null && (item.name.toLowerCase(Locale.ROOT).contains("geo-blocked")
                || getSharedPreferences("player", MODE_PRIVATE).getBoolean("geo." + item.key(), false));
        retries = 0;
        resumeAt = pos;
        bare = false;
        tried.clear();
        stalls.clear();
        ui.removeCallbacks(startTimeout);
        ui.removeCallbacks(stallTimeout);
        subtitles.setVisibility(View.GONE);
        message.setVisibility(View.GONE);
        ui.removeCallbacks(markWatched);
        if (live) ui.postDelayed(markWatched, 5_000);
        if (item.fromAddon() && (item.url == null || item.url.isEmpty())) findStream(pos);
        else start(pos);
        fillOverlay();
        showOverlay(true);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** the address to play: an Xtream channel in the format that works */
    private String streamUrl() {
        String url = item.url;
        if (live && useHls && url.endsWith(".ts")) url = url.substring(0, url.length() - 3) + ".m3u8";
        return url;
    }

    private void start(long pos) {
        // an add-on episode still looking for its stream
        if (item.url == null || item.url.isEmpty()) return;
        String url = streamUrl();
        // a plugin's link often needs the Referer / User-Agent its script returned, unless the
        // server is known to refuse them
        boolean noHeaders = bare || getSharedPreferences("player", MODE_PRIVATE).getBoolean("bare." + host(url), false);
        Map<String, String> headers = item.headers == null || noHeaders ? java.util.Collections.emptyMap() : item.headers;
        if (http != null) http.setDefaultRequestProperties(headers);
        if (okhttp != null) okhttp.setDefaultRequestProperties(headers);
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        String format = Net.format(url);
        if (Net.HLS.equals(format)) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (Net.DASH.equals(format)) b.setMimeType(MimeTypes.APPLICATION_MPD);
        if (live) b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build());
        if (item.subs != null && !item.subs.isEmpty()) {
            // the add-ons' subtitles, beside the stream's own
            List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
            for (String[] sub : item.subs) {
                String l = sub[0].toLowerCase(Locale.ROOT);
                subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub[0]))
                        .setMimeType(l.contains(".vtt") ? MimeTypes.TEXT_VTT : l.contains(".ass") || l.contains(".ssa") ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(sub[1])
                        .build());
            }
            b.setSubtitleConfigurations(subs);
        }
        DefaultMediaSourceFactory f = viaProxy && proxied != null ? proxied : direct;
        android.util.Log.i("PiTV", "start " + (f == proxied ? "proxy " : "direct ") + url);
        androidx.media3.exoplayer.source.MediaSource src = f.createMediaSource(b.build());
        if (pos > 0) player.setMediaSource(src, pos);
        else player.setMediaSource(src);
        player.prepare();
        player.play();
        tried.add(item.url);
        ui.removeCallbacks(startTimeout);
        if (canFallBack() && !playedOnRoute) ui.postDelayed(startTimeout, 25_000);
    }

    /** an add-on film or episode with other links to fall back on */
    private boolean canFallBack() {
        return item != null && !live && item.alternatives != null && !shiftPlaying;
    }

    /** the bitrate a quality needs, roughly, in bit/s (0 unknown, 1 = 360p … 5 = 4K) */
    private static long needs(int q) {
        return new long[] {6_000_000, 1_000_000, 2_000_000, 4_000_000, 8_000_000, 25_000_000}[q];
    }

    private static String qualityName(int q) {
        return new String[] {"", "360p", "480p", "720p", "1080p", "4K"}[q];
    }

    /**
     * the next link for this title, when the one playing fails or is too heavy: the best quality
     * the connection carries (as the player measured it), else the lightest left. [lower] keeps
     * to lower qualities than the current one.
     */
    private void nextLink(boolean lower, String why) {
        if (!canFallBack()) return;
        ui.removeCallbacks(startTimeout);
        ui.removeCallbacks(stallTimeout);
        int cur = 0;
        List<Addons.Stream> left = new ArrayList<>();
        for (Addons.Stream x : new ArrayList<>(item.alternatives)) {
            if (x.url == null || x.url.isEmpty()) continue;
            if (x.url.equals(item.url)) cur = x.quality();
            else if (!tried.contains(x.url)) left.add(x);
        }
        if (lower && cur > 0) {
            List<Addons.Stream> lighter = new ArrayList<>();
            for (Addons.Stream x : left) if (x.quality() > 0 && x.quality() < cur) lighter.add(x);
            // nothing lighter: any other link, the connection may suit it better
            if (!lighter.isEmpty()) left = lighter;
        }
        if (left.isEmpty()) {
            fail(why + ". Aucun autre lien ne fonctionne : choisissez-en un autre ou réessayez plus tard.");
            return;
        }
        // until something has played a while, the meter only guesses: the last real measure is better
        long bandwidth = bandwidth(this);
        Addons.Stream pick = bestFor(this, left);
        long pos = playedOnRoute ? Math.max(0, player.getCurrentPosition()) : resumeAt;
        item.url = pick.url;
        item.headers = pick.headers;
        item.bingeGroup = pick.bingeGroup;
        item.source = pick.source();
        android.util.Log.i("PiTV", "fallback (" + why + ", ~" + bandwidth / 1000 + " kbit/s): " + pick.label().replace('\n', ' '));
        String q = qualityName(pick.quality());
        message.setText(why + " — essai de " + pick.addon + (q.isEmpty() ? "" : " en " + q) + "…");
        message.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        retries = 0;
        playedOnRoute = false;
        bare = false;
        stalls.clear();
        resumeAt = pos;
        start(pos);
    }

    private static String host(String url) {
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /** the connection's speed in bit/s: measured while playing, else the last measure, else a guess */
    static long bandwidth(Context c) {
        long b = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(c).getBitrateEstimate();
        // until something has played a while, the meter only guesses: the last real measure is better
        long saved = c.getSharedPreferences("player", MODE_PRIVATE).getLong("bandwidth", 0);
        return !measured && saved > 0 ? saved : b;
    }

    /** of links listed best first: the best the connection carries, else the lightest; null if none */
    static Addons.Stream bestFor(Context c, List<Addons.Stream> links) {
        long bandwidth = bandwidth(c);
        for (Addons.Stream x : links) if (x.url != null && !x.url.isEmpty() && needs(x.quality()) <= bandwidth * 8 / 10) return x;
        Addons.Stream pick = null;
        for (Addons.Stream x : links) {
            if (x.url == null || x.url.isEmpty()) continue;
            if (pick == null || (x.quality() > 0 && (pick.quality() == 0 || x.quality() < pick.quality()))) pick = x;
        }
        return pick;
    }

    /** the source that played a title (a series, for its episodes), to take it again next time */
    static String lastSource(Context c, Model.Item i) {
        return c.getSharedPreferences("player", MODE_PRIVATE).getString(sourceKey(i), null);
    }

    /** the source of the last link played, whatever the title: tried first on a title never played */
    static String latestSource(Context c) {
        return c.getSharedPreferences("player", MODE_PRIVATE).getString("src.latest", null);
    }

    private static void saveSource(Context c, Model.Item i) {
        c.getSharedPreferences("player", MODE_PRIVATE).edit().putString(sourceKey(i), i.source).putString("src.latest", i.source).apply();
    }

    private static String sourceKey(Model.Item i) {
        return "src." + (i.kind == Model.EPISODE && i.seriesId != null ? Model.SERIES + ":" + i.seriesId : i.key());
    }

    /**
     * an add-on episode queued after the one chosen: its stream is asked for now, the same kind
     * (add-on and quality) as before if there is one
     */
    private void findStream(long pos) {
        Model.Item it = item;
        String binge = index > 0 ? items.get(index - 1).bingeGroup : null;
        message.setText("Recherche d'une source…");
        message.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        String subLang = getSharedPreferences("player", MODE_PRIVATE).getString("subLang", null);
        cat.async(() -> {
            Addons ad = Addons.get(this);
            it.subs = ad.subtitles(it, subLang);
            // the same source as the last episode plays the moment it answers, without
            // waiting for every other script
            AtomicReference<Addons.Stream> same = new AtomicReference<>();
            CountDownLatch found = new CountDownLatch(1);
            Future<List<Addons.Stream>> plugins = Plugins.get(this).future(it, part -> {
                for (Addons.Stream x : part) if (binge != null && binge.equals(x.bingeGroup) && same.compareAndSet(null, x)) found.countDown();
            });
            List<Addons.Stream> streams = new ArrayList<>(ad.streams(it));
            for (Addons.Stream x : streams) if (binge != null && binge.equals(x.bingeGroup)) return streams;
            try {
                long until = System.currentTimeMillis() + 45_000;
                while (binge != null && !plugins.isDone() && System.currentTimeMillis() < until) {
                    if (found.await(250, TimeUnit.MILLISECONDS)) break;
                }
                if (same.get() != null) {
                    streams.add(0, same.get());
                    return streams;
                }
                streams.addAll(plugins.get(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                plugins.cancel(true);
            }
            return streams;
        }, streams -> {
            if (player == null || item != it) return;
            if (streams == null || streams.isEmpty()) {
                fail("Aucune source pour cet épisode");
                return;
            }
            Addons.Stream s = inPreferredLanguage(this, streams).get(0);
            for (Addons.Stream x : streams) if (binge != null && binge.equals(x.bingeGroup)) {
                s = x;
                break;
            }
            it.url = s.url;
            it.bingeGroup = s.bingeGroup;
            it.headers = s.headers;
            it.source = s.source();
            it.alternatives = streams;
            message.setVisibility(View.GONE);
            start(pos);
        });
    }

    /** tries again after a pause that grows with each failure */
    private void reconnect(String why) {
        if (player == null || item == null) return;
        // a paused channel whose recording failed goes back to live
        endShift();
        retries++;
        if (retries > 8) {
            fail((why == null ? "Lecture impossible" : why) + ". Réessayez plus tard ou choisissez une autre chaîne.");
            return;
        }
        // a stream that fails directly may be geo-blocked, and one through the proxy may fail
        // because the proxy is out of reach: odd retries switch between the two
        // after three failures in a row, the route that worked may be the problem now
        if (retries >= 3) playedOnRoute = false;
        if (!lastErrorNetwork || playedOnRoute) {
            // a decoder hiccup, or a drop after it played: same route again
        } else if (proxied != null && retries % 2 == 1) viaProxy = !viaProxy;
        // an Xtream channel that fails in one format often works in the other
        else if (live && Source.XTREAM.equals(cat.source.type) && item.url.endsWith(".ts") && retries <= 6) useHls = !useHls;
        long pos = live ? 0 : Math.max(0, player.getCurrentPosition());
        if (why != null) {
            message.setText(why + " — reconnexion" + (retries > 1 ? " (" + retries + ")" : "") + "…");
            message.setVisibility(View.VISIBLE);
        }
        spinner.setVisibility(View.VISIBLE);
        long delay = why == null || retries == 1 ? 0 : Math.min(8_000, 500L << Math.min(4, retries - 1));
        ui.postDelayed(() -> {
            if (player != null) start(pos);
        }, delay);
    }

    private void fail(String why) {
        spinner.setVisibility(View.GONE);
        message.setText(why);
        message.setVisibility(View.VISIBLE);
        showOverlay(true);
    }

    // ---------------------------------------------------------------- live pause

    /** Pause on a channel: it's recorded from now on, and the picture stays on this moment */
    private void pauseLive() {
        if (shift != null) {
            // already behind live: the recording goes on while paused
            player.pause();
            return;
        }
        try {
            shift = new Timeshift(this, streamUrl(), viaProxy ? cat.source.proxyAddress() : null);
        } catch (java.io.IOException e) {
            toast(e.getMessage());
            return;
        }
        shift.start();
        shiftPlaying = false;
        // the recording takes over the connection: providers often allow only one
        player.stop();
        ui.removeCallbacks(stallCheck);
        spinner.setVisibility(View.GONE);
        fillOverlay();
    }

    private void resumeLive() {
        if (shiftPlaying) {
            player.play();
            return;
        }
        String why = shift.error();
        if (why != null) {
            toast(why + " : retour au direct");
            goLive();
            return;
        }
        playShift(shift.oldestMs());
    }

    /** plays the recording from [ms] (the start of the stretch that holds it) */
    private void playShift(long ms) {
        long[] at = shift.entryAt(ms);
        Timeshift ts = shift;
        shiftBase = at[0];
        shiftPlaying = true;
        player.setMediaSource(new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(() -> ts.new Source(at[1]), extractors)
                .createMediaSource(MediaItem.fromUri("timeshift://" + at[1])));
        player.prepare();
        player.play();
        fillOverlay();
    }

    private void goLive() {
        endShift();
        fillOverlay();
        start(0);
        showOverlay(true);
    }

    private void endShift() {
        if (shift == null) return;
        shift.close();
        shift = null;
        shiftPlaying = false;
    }

    /** the channel is paused (recording, or its recording paused) */
    private boolean livePaused() {
        return live && shift != null && (!shiftPlaying || player == null || !player.getPlayWhenReady());
    }

    /** where the picture is, in the recording's time */
    private long shiftPos() {
        return shiftPlaying ? shiftBase + Math.max(0, player.getCurrentPosition()) : 0;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private void ended() {
        saveProgress(true);
        if (!live && item.kind == Model.EPISODE && index + 1 < items.size()) {
            message.setText("Épisode suivant…");
            message.setVisibility(View.VISIBLE);
            ui.postDelayed(() -> load(index + 1, 0), 1500);
        } else if (live) {
            reconnect("Le flux s'est arrêté");
        } else {
            finish();
        }
    }

    private void saveProgress(boolean now) {
        if (player == null || item == null || live) return;
        long dur = player.getDuration(), pos = player.getCurrentPosition();
        if (dur == C.TIME_UNSET || dur <= 0 || pos <= 0) return;
        if (!now && (pos / 1000) % 10 != 0) return;
        lib.setProgress(item, player.getPlaybackState() == Player.STATE_ENDED ? dur : pos, dur);
    }

    private int count(int type) {
        int n = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == type) n += g.length;
        return n;
    }

    // ---------------------------------------------------------------- views

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // holds the highlight while the controls are only shown, so the arrows still zap
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        videoBox = new FrameLayout(this);
        surface = new SurfaceView(this);
        videoBox.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        videoBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));

        subtitles = Ui.text(this, 22, Color.WHITE, true);
        subtitles.setGravity(Gravity.CENTER);
        subtitles.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        subtitles.setBackgroundColor(0x66000000);
        subtitles.setPadding(dp(10), dp(4), dp(10), dp(4));
        subtitles.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = dp(40);
        root.addView(subtitles, sp);

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.RED));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        message = Ui.text(this, 15, Color.WHITE, true);
        message.setGravity(Gravity.CENTER);
        message.setBackground(Ui.rounded(0xCC000000, dp(10)));
        message.setPadding(dp(18), dp(10), dp(18), dp(10));
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        mp.topMargin = dp(90);
        root.addView(message, mp);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(48), dp(60), dp(48), dp(28));
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xB3000000, 0xF2000000}));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        head.addView(logo, Ui.margins(dp(84), dp(48), 0, 0, dp(14), 0));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        title = Ui.line(this, 22, Color.WHITE, true);
        tt.addView(title);
        subtitle = Ui.line(this, 13, Ui.DIM, false);
        tt.addView(subtitle, Ui.margins(-1, -2, 0, dp(5), 0, 0));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        overlay.addView(head);

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        seek = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.RED));
        seek.setSecondaryProgressTintList(ColorStateList.valueOf(0x80FFFFFF));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        sr.addView(seek, new LinearLayout.LayoutParams(0, dp(4), 1f));
        times = Ui.line(this, 12, Ui.DIM, false);
        sr.addView(times, Ui.margins(-2, -2, dp(14), 0, 0, 0));
        sr.setFocusable(true);
        sr.setPadding(0, dp(8), 0, dp(8));
        sr.setOnFocusChangeListener((v, has) -> seek.setScaleY(has ? 2f : 1f));
        seekRow = sr;
        overlay.addView(sr, Ui.margins(-1, -2, 0, dp(14), 0, 0));
        nextLine = Ui.line(this, 12, Ui.FAINT, false);
        overlay.addView(nextLine, Ui.margins(-1, -2, 0, dp(2), 0, 0));

        buttons = new LinearLayout(this);
        playButton = Ui.button(this, R.drawable.ic_pause, "Pause", v -> togglePlay());
        buttons.addView(playButton);
        TextView toLive = Ui.button(this, 0, "Direct", v -> goLive());
        toLive.setTag("live");
        buttons.addView(toLive, Ui.margins(-2, -2, dp(10), 0, 0, 0));
        TextView audio = Ui.button(this, R.drawable.ic_audio, "Audio", v -> pickTrack(C.TRACK_TYPE_AUDIO));
        audio.setTag("audio");
        buttons.addView(audio, Ui.margins(-2, -2, dp(10), 0, 0, 0));
        TextView subs = Ui.button(this, R.drawable.ic_subtitles, "Sous-titres", v -> pickTrack(C.TRACK_TYPE_TEXT));
        subs.setTag("subs");
        buttons.addView(subs, Ui.margins(-2, -2, dp(10), 0, 0, 0));
        TextView next = Ui.button(this, R.drawable.ic_next, "Épisode suivant", v -> {
            if (index + 1 < items.size()) {
                saveProgress(true);
                load(index + 1, 0);
            }
        });
        next.setTag("next");
        buttons.addView(next, Ui.margins(-2, -2, dp(10), 0, 0, 0));
        TextView list = Ui.button(this, R.drawable.ic_list, "Chaînes", v -> showChannels(true));
        list.setTag("list");
        buttons.addView(list, Ui.margins(-2, -2, dp(10), 0, 0, 0));
        TextView fit = Ui.button(this, 0, "Format", v -> cycleFit());
        fit.setTag("fit");
        buttons.addView(fit, Ui.margins(-2, -2, dp(10), 0, 0, 0));
        overlay.addView(buttons, Ui.margins(-2, -2, 0, dp(16), 0, 0));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        chanPanel = new LinearLayout(this);
        chanPanel.setOrientation(LinearLayout.VERTICAL);
        chanPanel.setVisibility(View.GONE);
        root.addView(chanPanel, new FrameLayout.LayoutParams(dp(330), -1, Gravity.START));
        return root;
    }

    private void fillOverlay() {
        boolean ep = item.kind == Model.EPISODE;
        logo.setVisibility(live ? View.VISIBLE : View.GONE);
        if (live) Images.get(this).load(logo, item.image, dp(84), dp(48), true);
        title.setText(live ? (item.num > 0 ? item.num + "  " : "") + item.name : ep && item.seriesName != null ? item.seriesName : item.name);
        buttons.findViewWithTag("next").setVisibility(ep && index + 1 < items.size() ? View.VISIBLE : View.GONE);
        buttons.findViewWithTag("list").setVisibility(live && items.size() > 1 ? View.VISIBLE : View.GONE);
        buttons.findViewWithTag("live").setVisibility(live && shift != null ? View.VISIBLE : View.GONE);
        seekRow.setFocusable(!live || shift != null);
        updateTimes();
    }

    private void updateTimes() {
        if (player == null || item == null) return;
        long now = System.currentTimeMillis();
        if (live && shift != null) {
            // paused or behind live: the bar spans what's recorded, up to live
            long end = shift.liveMs(), oldest = shift.oldestMs();
            long pos = seekTarget >= 0 ? seekTarget : shiftPos();
            seek.setProgress((int) (1000 * Math.max(0, pos - oldest) / Math.max(1, end - oldest)));
            seek.setSecondaryProgress(1000);
            times.setText("−" + hms(end - pos));
            Model.Prog p = cat.epg.now(item);
            subtitle.setText((p == null ? "" : p.title + "   ·   ") + (livePaused() ? "En pause" : "En différé")
                    + (viaProxy ? "   ·   via le proxy" : ""));
            nextLine.setVisibility(View.GONE);
            return;
        }
        if (live) {
            Model.Prog p = cat.epg.now(item);
            List<Model.Prog> up = cat.epg.upcoming(item);
            subtitle.setText((p == null ? "En direct" : p.title + "   " + Ui.clock(p.start) + " – " + Ui.clock(p.stop))
                    + (viaProxy ? "   ·   via le proxy" : ""));
            if (p != null) {
                seek.setProgress((int) (1000 * (now - p.start) / Math.max(1, p.stop - p.start)));
                seek.setSecondaryProgress(0);
                times.setText(Ui.clock(now));
            } else {
                seek.setProgress(0);
                times.setText(Ui.clock(now));
            }
            Model.Prog next = null;
            for (Model.Prog x : up) if (x.start >= now) {
                next = x;
                break;
            }
            nextLine.setText(next == null ? "" : "À suivre · " + Ui.clock(next.start) + "  " + next.title);
            nextLine.setVisibility(next == null ? View.GONE : View.VISIBLE);
            return;
        }
        subtitle.setText(item.kind == Model.EPISODE ? "S" + item.season + " · É" + item.episode + " — " + item.name : item.meta());
        long dur = player.getDuration(), pos = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        if (dur > 0 && dur != C.TIME_UNSET) {
            seek.setProgress((int) (1000 * pos / dur));
            seek.setSecondaryProgress((int) (1000 * player.getBufferedPosition() / dur));
            times.setText(hms(pos) + " / " + hms(dur));
        } else {
            times.setText(hms(pos));
        }
        nextLine.setVisibility(View.GONE);
    }

    private static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.FRANCE, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.FRANCE, "%d:%02d", s / 60, s % 60);
    }

    private void showOverlay(boolean show) {
        ui.removeCallbacks(hideOverlay);
        if (show) {
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(150).start();
            ui.postDelayed(hideOverlay, 5_000);
            if (!controlsActive) root.requestFocus();
        } else if (overlay.getVisibility() == View.VISIBLE) {
            controlsActive = false;
            overlay.animate().alpha(0f).setDuration(250).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
            root.requestFocus();
        }
    }

    private boolean overlayShown() {
        return overlay.getVisibility() == View.VISIBLE && overlay.getAlpha() > 0.5f;
    }

    /** "fit" (bars), "zoom" (fill, cropping) or "stretch" */
    private int fitMode;

    private void cycleFit() {
        fitMode = (fitMode + 1) % 3;
        android.widget.Toast.makeText(this, new String[] {"Format : adapté", "Format : zoom", "Format : étiré"}[fitMode], android.widget.Toast.LENGTH_SHORT).show();
        fitVideo();
    }

    private void fitVideo() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || videoSize.width == 0 || videoSize.height == 0) {
            root.post(this::fitVideo);
            return;
        }
        float ar = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height;
        int w = W, h = H;
        if (fitMode == 0) {
            if (W / (float) H > ar) w = Math.round(H * ar);
            else h = Math.round(W / ar);
        } else if (fitMode == 1) {
            if (W / (float) H > ar) h = Math.round(W / ar);
            else w = Math.round(H * ar);
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (p.width != w || p.height != h) {
            p.width = w;
            p.height = h;
            surface.setLayoutParams(p);
        }
    }

    private void togglePlay() {
        if (live) {
            if (livePaused()) resumeLive();
            else pauseLive();
            showOverlay(true);
            return;
        }
        if (player.isPlaying()) player.pause();
        else {
            if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) start(player.getCurrentPosition());
            player.play();
        }
        showOverlay(true);
    }

    // ---------------------------------------------------------------- tracks

    private void pickTrack(int type) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (type == C.TRACK_TYPE_TEXT) {
            labels.add("Désactivés");
            actions.add(() -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                getSharedPreferences("player", MODE_PRIVATE).edit().putString("subLang", "").apply();
            });
        }
        int selected = -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int t = 0; t < g.length; t++) {
                Format f = g.getTrackFormat(t);
                if (g.isTrackSelected(t)) selected = labels.size();
                labels.add(trackName(f, labels.size()));
                int tt = t;
                actions.add(() -> {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), tt)).build());
                    if (f.language != null) {
                        getSharedPreferences("player", MODE_PRIVATE).edit()
                                .putString(type == C.TRACK_TYPE_AUDIO ? "audioLang" : "subLang", f.language).apply();
                    }
                });
            }
        }
        if (type == C.TRACK_TYPE_TEXT && selected < 0) selected = 0;
        // the language can also change by taking another of the title's links
        if (type == C.TRACK_TYPE_AUDIO && canFallBack()) {
            String cur = "";
            java.util.Map<String, Integer> other = new java.util.LinkedHashMap<>();
            for (Addons.Stream x : new ArrayList<>(item.alternatives)) if (x.url != null && x.url.equals(item.url)) cur = x.language();
            for (Addons.Stream x : new ArrayList<>(item.alternatives)) {
                if (x.url == null || x.url.isEmpty() || x.url.equals(item.url) || x.language().isEmpty()) continue;
                other.merge(x.language(), 1, Integer::sum);
            }
            other.remove(cur);
            for (java.util.Map.Entry<String, Integer> e : other.entrySet()) {
                labels.add("Autre lien en " + e.getKey() + "  (" + e.getValue() + (e.getValue() > 1 ? " liens)" : " lien)"));
                actions.add(() -> switchLanguage(e.getKey()));
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(type == C.TRACK_TYPE_AUDIO ? "Audio" : "Sous-titres")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (d, which) -> {
                    actions.get(which).run();
                    d.dismiss();
                })
                .show();
    }

    /** plays the best of the title's links in [lang] from where it is, and prefers that language from now on */
    private void switchLanguage(String lang) {
        getSharedPreferences("player", MODE_PRIVATE).edit().putString("srcLang", lang).apply();
        List<Addons.Stream> links = new ArrayList<>();
        for (Addons.Stream x : new ArrayList<>(item.alternatives)) if (x.url != null && !x.url.equals(item.url) && lang.equals(x.language())) links.add(x);
        Addons.Stream pick = bestFor(this, links);
        if (pick == null) return;
        ui.removeCallbacks(startTimeout);
        ui.removeCallbacks(stallTimeout);
        long pos = Math.max(0, player.getCurrentPosition());
        item.url = pick.url;
        item.headers = pick.headers;
        item.bingeGroup = pick.bingeGroup;
        item.source = pick.source();
        String q = qualityName(pick.quality());
        message.setText("Passage en " + lang + " — " + pick.addon + (q.isEmpty() ? "" : " en " + q) + "…");
        message.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        retries = 0;
        playedOnRoute = false;
        bare = false;
        stalls.clear();
        resumeAt = pos;
        start(pos);
    }

    /** the links in the language last chosen with Audio, or all of them if none is in it */
    static List<Addons.Stream> inPreferredLanguage(Context c, List<Addons.Stream> links) {
        String lang = c.getSharedPreferences("player", MODE_PRIVATE).getString("srcLang", null);
        if (lang == null) return links;
        List<Addons.Stream> out = new ArrayList<>();
        for (Addons.Stream x : links) if (lang.equals(x.language())) out.add(x);
        return out.isEmpty() ? links : out;
    }

    private static String trackName(Format f, int n) {
        StringBuilder b = new StringBuilder();
        if (f.label != null) b.append(f.label);
        else if (f.language != null && !f.language.equals("und")) {
            String l = new Locale(f.language).getDisplayLanguage(Locale.FRANCE);
            b.append(l.isEmpty() ? f.language : Character.toUpperCase(l.charAt(0)) + l.substring(1));
        } else b.append("Piste ").append(n + 1);
        // the audio format helps choose (AC3 5.1 or AAC stéréo); a subtitle's internal format doesn't
        String codec = f.sampleMimeType == null || !f.sampleMimeType.startsWith("audio/") ? "" : f.sampleMimeType.substring(6);
        if (f.channelCount > 0) b.append(" · ").append(f.channelCount >= 6 ? "5.1" : f.channelCount == 2 ? "stéréo" : f.channelCount + " canaux");
        if (!codec.isEmpty()) b.append(" · ").append(codec.toUpperCase(Locale.ROOT));
        return b.toString();
    }

    // ---------------------------------------------------------------- live: zapping and the list

    private void zap(int delta) {
        int base = pendingZap >= 0 ? pendingZap : index;
        pendingZap = (base + delta + items.size()) % items.size();
        Model.Item ch = items.get(pendingZap);
        // show the target at once, switch once the presses stop
        title.setText((ch.num > 0 ? ch.num + "  " : "") + ch.name);
        Images.get(this).load(logo, ch.image, dp(84), dp(48), true);
        Model.Prog p = cat.epg.now(ch);
        subtitle.setText(p == null ? "En direct" : p.title);
        showOverlay(true);
        ui.removeCallbacks(doZap);
        ui.postDelayed(doZap, 450);
    }

    private void applyZap() {
        if (pendingZap < 0) return;
        int z = pendingZap;
        pendingZap = -1;
        if (z != index) load(z, 0);
    }

    private void showChannels(boolean show) {
        if (!show) {
            chanPanel.setVisibility(View.GONE);
            chanPanel.removeAllViews();
            root.requestFocus();
            return;
        }
        showOverlay(false);
        chanPanel.removeAllViews();
        chanPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF2000000, 0xE6000000, 0x00000000}));
        androidx.recyclerview.widget.RecyclerView list = new androidx.recyclerview.widget.RecyclerView(this);
        list.setLayoutManager(new Ui.PinnedLayout(this, androidx.recyclerview.widget.RecyclerView.VERTICAL, dp(200)));
        list.setPadding(dp(20), dp(20), dp(20), dp(300));
        list.setClipToPadding(false);
        list.setItemAnimator(null);
        list.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup p, int t) {
                TextView v = Ui.line(PlayerActivity.this, 14, Color.WHITE, true);
                v.setPadding(dp(12), dp(10), dp(12), dp(10));
                v.setFocusable(true);
                v.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder h, int pos) {
                TextView v = (TextView) h.itemView;
                Model.Item ch = items.get(pos);
                Model.Prog p = cat.epg.now(ch);
                v.setText((ch.num > 0 ? ch.num + "  " : "") + ch.name + (p == null ? "" : "\n" + p.title));
                v.setSingleLine(false);
                v.setMaxLines(2);
                Runnable style = () -> {
                    boolean f = v.hasFocus();
                    v.setBackground(f ? Ui.rounded(Color.WHITE, dp(6)) : pos == index ? Ui.rounded(0x33FFFFFF, dp(6)) : null);
                    v.setTextColor(f ? Color.BLACK : Color.WHITE);
                };
                style.run();
                v.setOnFocusChangeListener((x, has) -> style.run());
                v.setOnClickListener(x -> {
                    showChannels(false);
                    if (pos != index) load(pos, 0);
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });
        chanPanel.addView(list, new LinearLayout.LayoutParams(-1, -1));
        chanPanel.setVisibility(View.VISIBLE);
        list.scrollToPosition(index);
        list.post(() -> {
            View v = list.getLayoutManager().findViewByPosition(index);
            if (v != null) v.requestFocus();
            else list.requestFocus();
        });
    }

    // ---------------------------------------------------------------- seeking

    private void seekBy(long delta) {
        if (player == null || live && shift == null) return;
        if (live) {
            // within the recording; past its end is live again
            if (seekTarget < 0) seekTarget = shiftPos();
            seekTarget = Math.max(shift.oldestMs(), Math.min(shift.liveMs(), seekTarget + delta));
            updateTimes();
            showOverlay(true);
            ui.removeCallbacks(commitSeek);
            ui.postDelayed(commitSeek, 650);
            return;
        }
        long dur = player.getDuration();
        if (seekTarget < 0) seekTarget = player.getCurrentPosition();
        seekTarget = Math.max(0, seekTarget + delta);
        if (dur > 0 && dur != C.TIME_UNSET) seekTarget = Math.min(dur - 1000, seekTarget);
        updateTimes();
        showOverlay(true);
        ui.removeCallbacks(commitSeek);
        ui.postDelayed(commitSeek, 650);
    }

    private void commitSeek() {
        long to = seekTarget;
        seekTarget = -1;
        if (to < 0 || player == null) return;
        if (!live) player.seekTo(to);
        else if (shift != null) {
            if (to >= shift.liveMs() - 3_000) goLive();
            else playShift(to);
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (chanPanel.getVisibility() == View.VISIBLE) {
            if (down && (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                showChannels(false);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (!down) return super.dispatchKeyEvent(e);
        boolean shown = overlayShown();
        View focus = getCurrentFocus();
        boolean inButtons = shown && controlsActive && focus != null && focus.getParent() == buttons;
        boolean onSeek = shown && controlsActive && focus == seekRow;
        switch (key) {
            case KeyEvent.KEYCODE_BACK:
                if (shown) {
                    showOverlay(false);
                    return true;
                }
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (live) {
                    boolean paused = livePaused();
                    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || paused == (key == KeyEvent.KEYCODE_MEDIA_PLAY)) togglePlay();
                    else showOverlay(true);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY && !player.isPlaying()) player.play();
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                else togglePlay();
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30_000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-10_000);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (live) zap(-1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (live) zap(1);
                return true;
        }
        if (inButtons) {
            showOverlay(true);
            if (key == KeyEvent.KEYCODE_DPAD_UP && (!live || shift != null)) {
                seekRow.requestFocus();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (onSeek) {
            showOverlay(true);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                long step = e.getRepeatCount() > 6 ? 60_000 : e.getRepeatCount() > 2 ? 30_000 : 10_000;
                seekBy(key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                togglePlay();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                playButton.requestFocus();
                return true;
            }
            return true;
        }
        // nothing on screen: the arrows act directly
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (live) zap(-1);
                else focusOverlay(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (live) zap(1);
                else focusOverlay(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (live && shift == null) focusOverlay(true);
                else {
                    long step = e.getRepeatCount() > 6 ? 60_000 : e.getRepeatCount() > 2 ? 30_000 : 10_000;
                    seekBy(key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step);
                    if (!controlsActive) {
                        controlsActive = true;
                        ui.post(() -> seekRow.requestFocus());
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (livePaused()) togglePlay();
                else if (live) {
                    if (items.size() > 1) showChannels(true);
                    else focusOverlay(true);
                } else if (shown) togglePlay();
                else focusOverlay(false);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** shows the controls with the highlight on the buttons, or on the progress bar */
    private void focusOverlay(boolean onButtons) {
        controlsActive = true;
        showOverlay(true);
        View target = onButtons || live ? firstVisibleButton() : seekRow;
        ui.post(() -> {
            if (target != null) target.requestFocus();
        });
    }

    private View firstVisibleButton() {
        for (int i = 0; i < buttons.getChildCount(); i++) if (buttons.getChildAt(i).getVisibility() == View.VISIBLE) return buttons.getChildAt(i);
        return null;
    }
}
