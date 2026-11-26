package com.sall.pihome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    /** the dock's apps at first (then as many as chosen, up to MAX_DOCK); COLS: the all-apps grid */
    static final int DOCK = 4, MAX_DOCK = 8, COLS = 6;
    /** reachable from the top bar, so never shown as a tile */
    static final Set<String> BUILT_IN_HIDDEN = new HashSet<>(Arrays.asList(
            "com.android.tv.settings", "com.google.android.tv.remote.service"));
    /** first-run order; anything else follows alphabetically */
    static final List<String> DEFAULT_ORDER = Arrays.asList(
            "org.smarttube.stable", "com.nuvio.tv", "com.apple.atve.androidtv.appletv",
            "com.ionitech.airscreen");
    /** the home screen dims between these hours */
    static final int NIGHT_FROM = 23, NIGHT_TO = 6;

    static class App {
        String pkg, label, originalLabel;
        Intent launch;
        Drawable banner, icon;
        int color;
        View tile;
        /** the icon as shown: a full square picture; light ones get a dark focus frame */
        Bitmap square;
        boolean light;
    }

    private final List<App> apps = new ArrayList<>();
    private final List<App> hiddenApps = new ArrayList<>();
    private final Map<String, Integer> colors = new HashMap<>();
    private final Set<Integer> swallowUp = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private SharedPreferences prefs;
    private GlowView glow;
    private FrameLayout page;
    private LinearLayout dock, watchRow, shelf, hero, topBar;
    /** the all-apps window's grid, while it is open */
    private GridLayout allGrid;
    private TextView title, hint, watchLabel, wifiPill, greeting, weather, nowPill, sleepPill;
    private TextView prayerLine, updatePill, controlPill, searchPill, callout, peek, credit;
    private ImageView wallpaper;
    private GlassClock clock;
    private View wallScrim, appsTile;
    /** the focused app's content is up, in place of the clock */
    private boolean shelfOpen;
    private long lastHome, lastBack;
    /** Home or Back pressed twice within this (ms): the recent apps */
    private static final long DOUBLE_PRESS = 700;
    /** the app whose content the row holds now (rowApp is the one it is loading for) */
    private App rowFor;
    private String netName = "Wi-Fi";
    private boolean offline = true;
    private float lastCpu = Float.NaN, lastRam = Float.NaN;
    /** the control panel's memory line and bar, while it is open */
    private TextView memLine;
    private ClipDrawable memBar;
    private List<Updates.Update> updates = new ArrayList<>();
    /** packages being updated through the bridge, to report when each one lands */
    private final Set<String> updating = new HashSet<>();
    private String[] prayerTimes;
    private String prayerDay, prayerShown;
    private final Runnable updateLoop = new Runnable() {
        @Override
        public void run() {
            checkUpdates(false);
            ui.postDelayed(this, 12 * 3600_000L);
        }
    };
    private ImageView backdrop;
    private View backdropLayer;
    private WatchNext.Item backdropFor;
    private MediaSessionManager sessions;
    private MediaController playing;
    /** every active session, watched: one that starts playing later must still show up */
    private final List<MediaController> watched = new ArrayList<>();
    private final MediaController.Callback playingChanged = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            choosePlaying();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            choosePlaying();
        }

        @Override
        public void onSessionDestroyed() {
            if (sessions != null) pickSession(sessions.getActiveSessions(null));
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pickSession;
    private final Runnable weatherRetry = this::loadWeather;
    private final Runnable weatherLoop = new Runnable() {
        @Override
        public void run() {
            loadWeather();
            ui.postDelayed(this, 30 * 60_000L);
        }
    };
    private HorizontalScrollView watchScroll;
    private View night;
    private FrameLayout menuLayer;
    private FrameLayout root;
    /** what the glass shows: the glow and artwork behind the page, and the whole screen behind a menu */
    private final Glass.Source behind = new Glass.Source(), behindMenu = new Glass.Source();
    /** the recent apps' background: the whole screen, so a lighter blur than a panel's */
    private final Glass.Source behindRecents = new Glass.Source(4f, 2);
    private final Runnable captureBehind = this::captureBehind;
    private View focusBeforeMenu;
    private App moving;
    /** the app whose content the row under the dock shows; null = every app's "continue watching" */
    private App rowApp;
    private Runnable pendingRow;
    private String rowKey;
    /** tile to highlight when Home comes back to the front */
    private App returnTo;
    private boolean refocus;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean wasOnline;
    /** the hidden apps' code was typed since Home last came to the front */
    private boolean unlocked;
    /** the open keypad, which also takes the remote's number keys */
    private java.util.function.IntConsumer pinDigit;
    /** CPU and RAM, for the control panel, while Home is on screen */
    private final Runnable statsLoop = new Runnable() {
        @Override
        public void run() {
            float cpu = PiInfo.cpuLoad();
            if (!Float.isNaN(cpu)) lastCpu = cpu;
            lastRam = PiInfo.memoryUsed(MainActivity.this);
            showMemory();
            ui.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : null;
            if (pkg != null && Intent.ACTION_PACKAGE_ADDED.equals(i.getAction()) && updating.remove(pkg)) {
                Toast.makeText(MainActivity.this, appLabel(pkg) + " est à jour", Toast.LENGTH_LONG).show();
                checkUpdates(false);
            }
            loadApps();
            buildTiles(null);
        }
    };

    private final BroadcastReceiver clockTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateTimeOfDay(true);
            clock.tick();
            updateSleepBadge();
            prayerTick();
        }
    };

    private final ContentObserver watchNextChanged = new ContentObserver(ui) {
        @Override
        public void onChange(boolean self) {
            loadWatchNext();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        setContentView(buildLayout());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockTick, t);
        try {
            getContentResolver().registerContentObserver(WatchNext.URI, true, watchNextChanged);
            getContentResolver().registerContentObserver(WatchNext.PREVIEW, true, watchNextChanged);
        } catch (Exception ignored) {
        }

        loadApps();
        buildTiles(null);
        watchNetwork();
        updateTimeOfDay(false);
        watchSessions();
        ui.post(weatherLoop);
        ui.postDelayed(updateLoop, 20_000);
        loadPrayers();
        loadWallpaper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessions != null) pickSession(sessions.getActiveSessions(null));
        if (cm != null && "Wi-Fi".contentEquals(wifiPill.getContentDescription())) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) setWifi(wifiName(caps), false);
        }
        ui.post(statsLoop);
        updateSleepBadge();
        loadWatchNext();
        updateTimeOfDay(false);
        loadWallpaper();
    }

    @Override
    protected void onPause() {
        App a = appOf(getCurrentFocus());
        if (a != null) returnTo = a;
        refocus = true;
        unlocked = false;
        ui.removeCallbacks(statsLoop);
        // the screensaver borrows the last glow colour
        prefs.edit().putInt("glow", glow.getTargetColor()).apply();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(clockTick);
        ui.removeCallbacks(weatherLoop);
        ui.removeCallbacks(updateLoop);
        ui.removeCallbacks(weatherRetry);
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (MediaController c : watched) c.unregisterCallback(playingChanged);
        getContentResolver().unregisterContentObserver(watchNextChanged);
        if (netCallback != null) cm.unregisterNetworkCallback(netCallback);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home twice in a row: the recent apps
        long now = android.os.SystemClock.elapsedRealtime();
        boolean twice = now - lastHome < DOUBLE_PRESS;
        lastHome = now;
        if (twice && moving == null) {
            showRecents();
            return;
        }
        // Home pressed while already home: back to the first dock app
        closeMenu(false);
        if (moving != null) endMove();
        closeShelf();
        if (!apps.isEmpty()) focus(apps.get(0).tile);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        // Android TV can start us in touch mode, where nothing shows a highlight and the
        // first arrow press is spent just bringing it back
        if (has && menuLayer.getChildCount() == 0 && moving == null && !apps.isEmpty()) {
            View f = getCurrentFocus();
            // coming back to Home lands on an app, never on a top-bar button
            if (refocus || f == null || f.isInTouchMode()) {
                refocus = false;
                App a = returnTo != null && apps.contains(returnTo) && returnTo.tile.getParent() == dock ? returnTo : apps.get(0);
                closeShelf();
                focus(a.tile);
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        glow = new GlowView(this);
        glow.onSettled = this::scheduleGlass;
        glow.setColorNow(prefs.getInt("glow", 0xFF3A3A48));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        // the photo of the day over the glow, which shows until it has loaded
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setAlpha(0f);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        // darker at the top and the bottom, where the clock and the dock sit
        wallScrim = new View(this);
        wallScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x80000000, 0x14000000, 0x14000000, 0x99000000}));
        root.addView(wallScrim, new FrameLayout.LayoutParams(-1, -1));

        // the highlighted card's artwork, over the photo while the shelf is up
        FrameLayout back = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View fadeDown = new View(this);
        fadeDown.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x33000000, 0x66000000, 0xF2000000}));
        back.addView(fadeDown, new FrameLayout.LayoutParams(-1, -1));
        View fadeLeft = new View(this);
        fadeLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6000000, 0x66000000, 0x00000000}));
        back.addView(fadeLeft, new FrameLayout.LayoutParams(-1, -1));
        back.setAlpha(0f);
        backdropLayer = back;
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));

        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bp.setMargins(0, dp(24), dp(32), 0);
        page.addView(buildTopBar(), bp);

        // at rest: greeting, date and weather over a large clock
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        greeting = text(13, 0xD9FFFFFF, "sans-serif-medium");
        greeting.setShadowLayer(dp(8), 0, 0, 0x66000000);
        // a vertical layout stretches its children: kept to their size, they sit centred
        hero.addView(greeting, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        styleText(date, 15, Color.WHITE, "sans-serif-medium");
        date.setShadowLayer(dp(8), 0, 0, 0x66000000);
        dateRow.addView(date);
        weather = text(15, Color.WHITE, "sans-serif-medium");
        weather.setShadowLayer(dp(8), 0, 0, 0x66000000);
        weather.setCompoundDrawablePadding(dp(8));
        weather.setSingleLine(true);
        weather.setVisibility(View.GONE);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.leftMargin = dp(14);
        dateRow.addView(weather, wp);
        LinearLayout.LayoutParams drp = new LinearLayout.LayoutParams(-2, -2);
        drp.topMargin = dp(3);
        hero.addView(dateRow, drp);
        clock = new GlassClock(this, dp(132));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(8);
        hero.addView(clock, clp);
        prayerLine = text(12, 0xD9FFFFFF, "sans-serif-medium");
        prayerLine.setShadowLayer(dp(8), 0, 0, 0x66000000);
        prayerLine.setCompoundDrawablePadding(dp(8));
        prayerLine.setSingleLine(true);
        prayerLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = dp(8);
        hero.addView(prayerLine, plp);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = dp(30);
        page.addView(hero, hp);

        credit = text(9, 0x80FFFFFF, "sans-serif");
        credit.setMaxLines(2);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        credit.setLineSpacing(dp(2), 1f);
        credit.setMaxWidth(dp(190));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cp.setMargins(dp(32), 0, 0, dp(16));
        page.addView(credit, cp);

        // the focused app's content, up from the dock in place of the clock
        shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        // what is highlighted, for the code that names it (not shown: the cards say it)
        title = text(1, Color.WHITE, "sans-serif");
        hint = text(1, Color.WHITE, "sans-serif");
        watchLabel = text(13, 0xF2FFFFFF, "sans-serif-medium");
        watchLabel.setText("Continuer à regarder");
        watchLabel.setShadowLayer(dp(6), 0, 0, 0x99000000);
        watchLabel.setGravity(Gravity.CENTER_VERTICAL);
        watchLabel.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.leftMargin = dp(4);
        shelf.addView(watchLabel, wl);
        watchScroll = new HorizontalScrollView(this);
        watchScroll.setHorizontalScrollBarEnabled(false);
        watchScroll.setClipChildren(false);
        watchScroll.setClipToPadding(false);
        watchRow = new LinearLayout(this);
        watchRow.setOrientation(LinearLayout.HORIZONTAL);
        watchRow.setClipChildren(false);
        watchRow.setClipToPadding(false);
        watchRow.setPadding(0, 0, dp(40), 0);
        watchScroll.addView(watchRow);
        LinearLayout.LayoutParams wsp = new LinearLayout.LayoutParams(-1, -2);
        wsp.topMargin = dp(4);
        shelf.addView(watchScroll, wsp);
        shelf.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        // high enough above the dock for the name bubble to fit between
        sp.setMargins(dp(32), 0, 0, dp(122));
        page.addView(shelf, sp);

        // what is up there, over the focused app
        peek = text(11, 0xCCFFFFFF, "sans-serif-medium");
        peek.setShadowLayer(dp(6), 0, 0, 0x80000000);
        peek.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams pk = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pk.bottomMargin = dp(134);
        page.addView(peek, pk);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(9), dp(8), dp(9));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setBackground(new Glass.Panel(dock, behind, dp(22), 0x541E141C, Math.max(1, dp(1.1f))).lens(dp(8)).rimStrength(0.55f));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = dp(14);
        page.addView(dock, dl);

        // the focused dock app's name, in a bubble over it
        callout = text(11.5f, 0xF5F5F5F7, "sans-serif-medium");
        callout.setPadding(dp(10), dp(4), dp(10), dp(5) + dp(5));
        callout.setBackground(new Bubble(dp(6.5f), dp(9.5f), dp(5), Math.max(1, dp(0.8f))));
        callout.setAlpha(0f);
        page.addView(callout, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        night = new View(this);
        night.setBackgroundColor(Color.BLACK);
        night.setAlpha(0f);
        night.setFocusable(false);
        night.setClickable(false);
        root.addView(night, new FrameLayout.LayoutParams(-1, -1));

        menuLayer = new FrameLayout(this);
        root.addView(menuLayer, new FrameLayout.LayoutParams(-1, -1));

        setHint();
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) scheduleGlass();
        });
        return root;
    }

    // ---------------------------------------------------------------- glass

    private int rimWidth() {
        return Math.max(1, dp(1));
    }

    /** once the glow, the photo or the artwork has settled */
    private void scheduleGlass() {
        ui.removeCallbacks(captureBehind);
        ui.postDelayed(captureBehind, 120);
    }

    private void captureBehind() {
        behind.capture(root.getWidth(), root.getHeight(), c -> {
            glow.draw(c);
            for (View v : new View[] {wallpaper, wallScrim, backdropLayer}) {
                float alpha = v.getAlpha();
                if (alpha < 0.01f) continue;
                c.save();
                c.translate(v.getLeft(), v.getTop());
                c.saveLayerAlpha(0, 0, v.getWidth(), v.getHeight(), Math.round(alpha * 255));
                v.draw(c);
                c.restore();
                c.restore();
            }
        });
    }

    // ---------------------------------------------------------------- wallpaper

    private boolean wallShown;

    private void loadWallpaper() {
        io.execute(() -> {
            if (!wallShown) {
                Wallpaper.Photo saved = Wallpaper.saved(this);
                if (saved != null) ui.post(() -> showWallpaper(saved));
            }
            Wallpaper.Photo fresh = Wallpaper.refresh(this);
            if (fresh != null) ui.post(() -> showWallpaper(fresh));
        });
    }

    private void showWallpaper(Wallpaper.Photo p) {
        wallShown = true;
        wallpaper.setImageBitmap(p.bitmap);
        clock.setPhoto(p.bitmap);
        credit.setText(p.credit);
        wallpaper.animate().alpha(1f).setDuration(600).withEndAction(this::scheduleGlass).start();
    }

    // ---------------------------------------------------------------- top bar

    /** top right: what is going on (playing, updates, sleep timer, offline), then the control panel */
    private View buildTopBar() {
        LinearLayout bar = topBar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);

        nowPill = pill(R.drawable.ic_pause, "", false);
        nowPill.setMaxWidth(dp(260));
        nowPill.setSingleLine(true);
        nowPill.setEllipsize(TextUtils.TruncateAt.END);
        nowPill.setVisibility(View.GONE);
        nowPill.setOnClickListener(v -> togglePlayback());
        bar.addView(nowPill, new LinearLayout.LayoutParams(-2, -2));

        updatePill = pill(R.drawable.ic_update, "Mises à jour", true);
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(v -> showUpdatesMenu());
        bar.addView(updatePill, pillParams());

        sleepPill = pill(R.drawable.ic_sleep, "Veille", true);
        sleepPill.setVisibility(View.GONE);
        sleepPill.setOnClickListener(v -> showSleepMenu());
        bar.addView(sleepPill, pillParams());

        wifiPill = pill(R.drawable.ic_wifi, "Wi-Fi", true);
        wifiPill.setVisibility(View.GONE);
        wifiPill.setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        bar.addView(wifiPill, pillParams());

        searchPill = pill(R.drawable.ic_search, "Rechercher", true);
        searchPill.setOnClickListener(v -> showSearch());
        bar.addView(searchPill, pillParams());

        controlPill = pill(R.drawable.ic_controls, "Centre de contrôle", true);
        controlPill.setOnClickListener(v -> showControlPanel());
        bar.addView(controlPill, pillParams());

        for (View v : new View[] {nowPill, updatePill, sleepPill, wifiPill, searchPill, controlPill}) v.setId(View.generateViewId());
        relinkTopBar();
        return bar;
    }

    /**
     * left/right stay within the bar, over the buttons that are showing; the compact ones are
     * small enough that the automatic focus search would otherwise jump down to the dock
     */
    private void relinkTopBar() {
        List<View> row = new ArrayList<>();
        for (View v : new View[] {nowPill, updatePill, sleepPill, wifiPill, searchPill, controlPill}) {
            if (v != null && v.getVisibility() == View.VISIBLE) row.add(v);
        }
        for (int i = 0; i < row.size(); i++) {
            row.get(i).setNextFocusLeftId(row.get(Math.max(0, i - 1)).getId());
            row.get(i).setNextFocusRightId(row.get(Math.min(row.size() - 1, i + 1)).getId());
        }
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(10);
        return p;
    }

    /**
     * a rounded top-bar button; compact ones show their label only while focused, plus an
     * optional short status (the tag) while not, e.g. the sleep timer's "42 min"
     */
    private TextView pill(int icon, String label, boolean compact) {
        TextView t = text(11.5f, Color.WHITE, "sans-serif-medium");
        t.setText(compact ? "" : label);
        t.setContentDescription(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        Drawable d = getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(14), dp(14));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setFocusable(true);
        t.setClickable(true);
        Drawable glass = new Glass.Panel(t, behind, dp(20), 0x14FFFFFF, rimWidth()).adaptive();
        t.setOnFocusChangeListener((v, has) -> {
            int fg = has ? Color.BLACK : Color.WHITE;
            t.setTextColor(fg);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(fg));
            t.setBackground(has ? rounded(Color.WHITE, dp(20)) : glass);
            if (compact) pillText(t, has, t.getContentDescription().toString());
            if (has) unclip(v);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
        });
        t.setBackground(glass);
        t.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        if (compact) pillText(t, false, label);
        else {
            t.setPadding(dp(11), dp(6), dp(12), dp(6));
            t.setCompoundDrawablePadding(dp(6));
        }
        return t;
    }

    /**
     * lets a view that grows on focus draw past its layouts' edges instead of being cut: the two
     * layouts around it stop clipping, not the page (a cropped picture on it would spill), nor a
     * layout that scrolls (it must keep hiding what is scrolled away)
     */
    private static void unclip(View v) {
        android.view.ViewParent p = v.getParent();
        for (int i = 0; i < 2 && p instanceof android.view.ViewGroup; i++, p = p.getParent()) {
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            if (g instanceof android.widget.ScrollView || g instanceof android.widget.HorizontalScrollView
                    || g instanceof android.widget.AbsListView || g.getClass().getName().contains("RecyclerView")) break;
            g.setClipChildren(false);
            g.setClipToPadding(false);
        }
    }

    /** the network, for the control panel; offline, a "Hors ligne" button shows in the top bar */
    private void setWifi(String name, boolean off) {
        netName = name;
        offline = off;
        wifiPill.setContentDescription(name);
        wifiPill.setTag(off ? "Hors ligne" : "");
        pillText(wifiPill, wifiPill.hasFocus(), name);
        if (wifiPill.hasFocus() && !off) focus(controlPill);
        wifiPill.setVisibility(off ? View.VISIBLE : View.GONE);
        relinkTopBar();
    }

    private void pillText(TextView t, boolean focused, String label) {
        String status = t.getTag() instanceof String ? (String) t.getTag() : "";
        String text = focused ? (status.isEmpty() ? label : label + " · " + status) : status;
        t.setText(text);
        boolean empty = text.isEmpty();
        t.setCompoundDrawablePadding(empty ? 0 : dp(6));
        t.setPadding(dp(empty ? 8 : 11), dp(6), dp(empty ? 8 : 12), dp(6));
    }

    private TextView sectionLabel(String s) {
        TextView t = text(13, 0x80FFFFFF, "sans-serif-medium");
        t.setText(s);
        return t;
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(18);
        p.leftMargin = dp(4);
        return p;
    }

    // ---------------------------------------------------------------- greeting and night mode

    static String greeting(int hour) {
        if (hour >= 5 && hour < 12) return "Bonjour";
        if (hour >= 12 && hour < 18) return "Bon après-midi";
        return "Bonsoir";
    }

    private void updateTimeOfDay(boolean animate) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(greeting(hour));
        float dim = hour >= NIGHT_FROM || hour < NIGHT_TO ? 0.4f : 0f;
        if (animate) night.animate().alpha(dim).setDuration(1500).start();
        else night.setAlpha(dim);
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(",")));
        Map<String, App> found = new LinkedHashMap<>();
        hiddenApps.clear();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (found.containsKey(pkg) || pkg.equals(getPackageName()) || BUILT_IN_HIDDEN.contains(pkg)) continue;
                if (containsPkg(hiddenApps, pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.originalLabel = ri.loadLabel(pm).toString();
                a.label = prefs.getString("label." + pkg, a.originalLabel);
                a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (hidden.contains(pkg)) {
                    hiddenApps.add(a);
                    continue;
                }
                iconFor(a, ri, pm);
                found.put(pkg, a);
            }
        }

        // saved order first, then the defaults, then everything new alphabetically
        apps.clear();
        List<String> order = new ArrayList<>();
        String saved = prefs.getString("order", null);
        if (saved != null && !saved.isEmpty()) order.addAll(Arrays.asList(saved.split(",")));
        else order.addAll(DEFAULT_ORDER);
        for (String pkg : order) {
            App a = found.remove(pkg);
            if (a != null) apps.add(a);
        }
        List<App> rest = new ArrayList<>(found.values());
        rest.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        apps.addAll(rest);
    }

    /**
     * the app's square icon and colour: drawn once per version of the app and kept in the cache,
     * so Home starts without loading every banner and icon again
     */
    private void iconFor(App a, ResolveInfo ri, PackageManager pm) {
        long stamp = 0;
        try {
            stamp = pm.getPackageInfo(a.pkg, 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        String key = a.pkg + "/" + ri.activityInfo.name + "@" + stamp + "#" + Icons.SIZE;
        File dir = new File(getCacheDir(), "icons");
        File f = new File(dir, Integer.toHexString(key.hashCode()) + ".png");
        String meta = icons().getString(a.pkg, "");
        if (meta.startsWith(key + "|") && f.exists()) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            String[] m = meta.substring(key.length() + 1).split("\\|");
            if (b != null && m.length == 2) {
                a.square = b;
                a.color = (int) Long.parseLong(m[0], 16);
                a.light = "1".equals(m[1]);
                return;
            }
        }
        a.banner = ri.activityInfo.loadBanner(pm);
        if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
        a.icon = ri.loadIcon(pm);
        // the dock's icons are large: the sharpest version the app has
        try {
            int id = ri.getIconResource();
            if (id != 0) {
                Drawable hi = pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
                        .getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                if (hi != null) a.icon = hi;
            }
        } catch (Exception ignored) {
        }
        a.color = colorOf(a.pkg, a.banner != null ? a.banner : a.icon);
        a.square = Icons.square(a.pkg, a.icon, a.banner, a.color);
        a.light = Icons.light(a.square);
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            a.square.compress(Bitmap.CompressFormat.PNG, 100, o);
            icons().edit().putString(a.pkg, key + "|" + Integer.toHexString(a.color) + "|" + (a.light ? "1" : "0")).apply();
        } catch (Exception ignored) {
        }
    }

    private SharedPreferences icons() {
        return getSharedPreferences("icons", MODE_PRIVATE);
    }

    private static boolean containsPkg(List<App> list, String pkg) {
        for (App a : list) if (a.pkg.equals(pkg)) return true;
        return false;
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (App a : apps) sb.append(sb.length() == 0 ? "" : ",").append(a.pkg);
        prefs.edit().putString("order", sb.toString()).apply();
    }

    private int dockSize() {
        return Math.max(1, Math.min(MAX_DOCK, prefs.getInt("dockSize", DOCK)));
    }

    private void buildTiles(App focus) {
        View current = getCurrentFocus();
        App keep = focus != null ? focus : appOf(current);
        dock.removeAllViews();
        int n = Math.min(dockSize(), apps.size());
        for (int i = 0; i < n; i++) {
            App a = apps.get(i);
            a.tile = tile(a, true);
            dock.addView(a.tile);
        }
        appsTile = appsTile();
        dock.addView(appsTile);
        if (allGrid != null && allGrid.isAttachedToWindow()) fillAllApps();

        App target = keep != null && apps.contains(keep) ? keep : (apps.isEmpty() ? null : apps.get(0));
        if (target != null && (current == null || keep != null)) {
            View t = target.tile.isAttachedToWindow() || target.tile.getParent() != null ? target.tile : appsTile;
            t.post(() -> focus(t));
        }
    }

    private FrameLayout tileFrame(int radius) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        t.setClipToOutline(true);
        // a glass edge, until it's highlighted (raise)
        t.setForeground(Glass.rim(radius, rimWidth()));
        return t;
    }

    /** the app's icon, drawn once as a full square (Icons) and rounded by the tile */
    private void iconInto(FrameLayout t, App a, int s) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(a.square);
        t.addView(img, new FrameLayout.LayoutParams(-1, -1));
    }

    private View tile(App a, boolean inDock) {
        int s = inDock ? dp(54) : dp(56), radius = s * 24 / 100;
        FrameLayout t = tileFrame(radius);
        t.setTag(a);
        iconInto(t, a, s);
        t.setOnFocusChangeListener((v, has) -> {
            float sc = has ? (moving == a ? 1.24f : 1.18f) : 1f;
            // in the dock the lift and the name say which is highlighted: no frame, except while moving
            int ring = moving == a ? 0xFF7FD3FF : inDock ? 0 : a.light ? 0xFF111114 : Color.WHITE;
            raise(v, has, sc, ring, radius);
            // as in a Mac's dock, the highlighted icon grows upward
            if (inDock) lift(v, has);
            if (!inDock) return;
            if (has) {
                if (rowFor != a) closeShelf();
                showCallout(v, moving == a ? "← → puis OK" : a.label);
                title.setText(a.label);
                setHint();
                glow.setColor(a.color);
                followApp(a);
                hideBackdrop();
                updatePeek();
            } else ui.post(this::calloutCheck);
        });
        t.setOnClickListener(v -> {
            if (moving != null) { endMove(); return; }
            if (!inDock) closeMenu(false);
            launch(a);
        });
        t.setOnLongClickListener(v -> { showAppMenu(a); return true; });
        if (!inDock) {
            t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            return t;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(dp(5), 0, dp(5), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void lift(View v, boolean has) {
        v.animate().translationY(has ? -dp(7) : 0).setDuration(has ? 190 : 150).start();
    }

    /** the last place in the dock: every other app, in a window */
    private View appsTile() {
        int s = dp(54), radius = s * 24 / 100;
        FrameLayout t = tileFrame(radius);
        t.setBackground(new Glass.Panel(t, behind, radius, 0x26FFFFFF, rimWidth()).adaptive());
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_apps);
        img.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        t.addView(img, new FrameLayout.LayoutParams(s * 42 / 100, s * 42 / 100, Gravity.CENTER));
        t.setOnFocusChangeListener((v, has) -> {
            raise(v, has, 1.18f, 0, radius);
            lift(v, has);
            if (has) {
                if (shelfOpen) closeShelf();
                showCallout(v, "Toutes les apps");
                ui.removeCallbacks(pendingRow);
                glow.setColor(0xFF5A5A6E);
                hideBackdrop();
                updatePeek();
            } else ui.post(this::calloutCheck);
        });
        t.setOnClickListener(v -> showAllApps());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(dp(5), 0, dp(5), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void showCallout(View tile, String label) {
        // at start the dock may not be laid out yet: place it once it is
        if (!tile.isLaidOut() || !dock.isLaidOut()) {
            tile.post(() -> {
                if (tile.hasFocus() && tile.isLaidOut()) showCallout(tile, label);
            });
            return;
        }
        callout.setText(label);
        callout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // where the tile sits on the page, grown as it is when highlighted
        float x = dock.getLeft() + tile.getLeft() + tile.getWidth() / 2f - callout.getMeasuredWidth() / 2f;
        float y = dock.getTop() + tile.getTop() - dp(7) - tile.getHeight() * 0.09f - callout.getMeasuredHeight() - dp(5);
        boolean was = callout.getAlpha() > 0.5f;
        callout.animate().cancel();
        if (was) callout.animate().translationX(x).translationY(y).alpha(1f).setDuration(140).start();
        else {
            callout.setTranslationX(x);
            callout.setTranslationY(y + dp(6));
            callout.animate().translationY(y).alpha(1f).setDuration(160).start();
        }
    }

    /** the bubble goes once the highlight has left the dock */
    private void calloutCheck() {
        View f = getCurrentFocus();
        if (f == null || f.getParent() != dock) callout.animate().alpha(0f).setDuration(120).start();
        if (f == null || f.getParent() != dock) peek.setVisibility(View.INVISIBLE);
    }

    /** the highlighted dock app's content shows by itself, above the dock, once it has loaded */
    private void updatePeek() {
        View f = getCurrentFocus();
        if (f == null || f.getParent() != dock) return;
        if (appOf(f) != null && appOf(f) == rowFor && watchRow.getChildCount() > 0) openShelf();
        else closeShelf();
    }

    private void openShelf() {
        if (shelfOpen) return;
        shelfOpen = true;
        credit.animate().alpha(0f).setDuration(220).start();
        shelf.setVisibility(View.VISIBLE);
        shelf.setAlpha(0f);
        shelf.setTranslationY(dp(24));
        shelf.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void closeShelf() {
        if (!shelfOpen) return;
        shelfOpen = false;
        credit.animate().alpha(1f).setDuration(260).start();
        shelf.animate().alpha(0f).translationY(dp(24)).setDuration(180).withEndAction(() -> {
            if (!shelfOpen) shelf.setVisibility(View.GONE);
        }).start();
        hideBackdrop();
    }

    /** the apps not in the dock, and the hidden ones, in a window over the screen */
    private void showAllApps() {
        LinearLayout card = menuCard("Toutes les apps");
        allGrid = new GridLayout(this);
        allGrid.setColumnCount(COLS);
        allGrid.setClipChildren(false);
        allGrid.setClipToPadding(false);
        View first = fillAllApps();
        // as wide as what it holds, up to COLS a row
        int cols = Math.max(3, Math.min(COLS, allGrid.getChildCount()));
        allGrid.setColumnCount(cols);
        int rows = (allGrid.getChildCount() + cols - 1) / cols;
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(8), 0, dp(8));
        sv.addView(allGrid);
        card.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(rows, 3) * dp(96) + dp(16)));
        if (first == null) {
            TextView none = text(14, 0xB3FFFFFF, "sans-serif");
            none.setText("Toutes les apps sont dans le dock.");
            none.setPadding(dp(6), dp(8), dp(6), dp(8));
            card.addView(none);
            first = menuButton("Fermer", null);
            card.addView(first);
        }
        openMenu(card, first, dp(cols * 84 + 48), Gravity.CENTER);
    }

    /** fills the open window's grid; returns its first entry */
    private View fillAllApps() {
        allGrid.removeAllViews();
        View first = null;
        for (int i = Math.min(dockSize(), apps.size()); i < apps.size(); i++) {
            App a = apps.get(i);
            a.tile = tile(a, false);
            allGrid.addView(gridCell(a.tile, a.label));
            if (first == null) first = a.tile;
        }
        if (!hiddenApps.isEmpty()) {
            View h = hiddenTile();
            allGrid.addView(gridCell(h, "Masquées (" + hiddenApps.size() + ")"));
            if (first == null) first = h;
        }
        return first;
    }

    private View gridCell(View icon, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.addView(icon);
        TextView name = tileName(label, 11);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(8);
        cell.addView(name, np);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = dp(76);
        p.setMargins(dp(4), dp(6), dp(4), dp(6));
        cell.setLayoutParams(p);
        return cell;
    }

    private View hiddenTile() {
        int s = dp(56), radius = s * 24 / 100;
        FrameLayout t = tileFrame(radius);
        t.setBackground(rounded(0x26FFFFFF, radius));
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_hidden);
        img.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
        t.addView(img, new FrameLayout.LayoutParams(s * 40 / 100, s * 40 / 100, Gravity.CENTER));
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        t.setOnFocusChangeListener((v, has) -> raise(v, has, 1.15f, Color.WHITE, radius));
        t.setOnClickListener(v -> withCode(this::showHiddenMenu));
        return t;
    }

    private void showHiddenMenu() {
        String[] labels = new String[hiddenApps.size() + 2];
        Runnable[] actions = new Runnable[labels.length];
        for (int i = 0; i < hiddenApps.size(); i++) {
            App a = hiddenApps.get(i);
            labels[i] = a.label;
            actions[i] = () -> showMenu(a.label,
                    new String[] {"Ouvrir", "Afficher à nouveau", "Retour"},
                    new Runnable[] {() -> launch(a), () -> unhide(a), this::showHiddenMenu});
        }
        labels[labels.length - 2] = "Changer le code";
        actions[labels.length - 2] = () -> newCode(this::showHiddenMenu);
        labels[labels.length - 1] = "Fermer";
        showMenu("Apps masquées", labels, actions);
    }

    // ---------------------------------------------------------------- code for the hidden apps

    /** runs ok once the code is typed, or once one is chosen the first time; unlocked until Home leaves the screen */
    private void withCode(Runnable ok) {
        String saved = prefs.getString("code", null);
        if (saved == null) newCode(ok);
        else if (unlocked) ok.run();
        else showCodePad("Code des apps masquées", c -> {
            if (codeHash(c).equals(saved)) {
                unlocked = true;
                ok.run();
            } else {
                Toast.makeText(this, "Code incorrect", Toast.LENGTH_SHORT).show();
                withCode(ok);
            }
        });
    }

    private void newCode(Runnable then) {
        showCodePad("Choisissez un code (4 chiffres)", c -> showCodePad("Confirmez le code", again -> {
            if (!again.equals(c)) {
                Toast.makeText(this, "Les codes ne correspondent pas", Toast.LENGTH_SHORT).show();
                newCode(then);
                return;
            }
            prefs.edit().putString("code", codeHash(c)).apply();
            unlocked = true;
            Toast.makeText(this, "Code enregistré", Toast.LENGTH_SHORT).show();
            then.run();
        }));
    }

    static String codeHash(String code) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(("pihome:" + code).getBytes());
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** a 4-digit keypad, for the arrows and OK or the remote's number keys */
    private void showCodePad(String heading, java.util.function.Consumer<String> done) {
        LinearLayout card = menuCard(heading);
        StringBuilder code = new StringBuilder();
        TextView dots = text(22, Color.WHITE, "sans-serif-medium");
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.4f);
        dots.setPadding(0, dp(6), 0, dp(10));
        Runnable show = () -> {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < 4; i++) d.append(i < code.length() ? '●' : '○');
            dots.setText(d);
        };
        show.run();
        card.addView(dots, new LinearLayout.LayoutParams(-1, -2));
        java.util.function.IntConsumer digit = n -> {
            if (n < 0) {
                if (code.length() > 0) code.setLength(code.length() - 1);
            } else if (code.length() < 4) code.append(n);
            show.run();
            if (code.length() == 4) {
                pinDigit = null;
                String c = code.toString();
                // let the fourth dot show before the next step
                ui.postDelayed(() -> {
                    closeMenu(false);
                    done.accept(c);
                }, 150);
            }
        };
        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        View first = null;
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕"};
        for (String k : keys) {
            TextView b = text(16, Color.WHITE, "sans-serif-medium");
            b.setText(k);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setBackground(rounded(0x1FFFFFFF, dp(14)));
            b.setOnFocusChangeListener((v, has) -> {
                b.setTextColor(has ? Color.BLACK : Color.WHITE);
                b.setBackground(rounded(has ? Color.WHITE : 0x1FFFFFFF, dp(14)));
            });
            b.setOnClickListener(v -> {
                if (k.equals("✕")) closeMenu(true);
                else digit.accept(k.equals("⌫") ? -1 : Integer.parseInt(k));
            });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = dp(72);
            p.height = dp(40);
            p.setMargins(dp(3), dp(3), dp(3), dp(3));
            pad.addView(b, p);
            if (first == null) first = b;
        }
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.bottomMargin = dp(6);
        card.addView(pad, pp);
        openMenu(card, first);
        pinDigit = digit;
    }

    private TextView tileName(String s, float sp) {
        TextView name = text(sp, 0xE6FFFFFF, "sans-serif-medium");
        name.setText(s);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        return name;
    }

    private void raise(View v, boolean has, float scale, int border, int radius) {
        if (has) unclip(v);
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).translationZ(has ? dp(12) : 0)
                .setDuration(has ? 190 : 150)
                .setInterpolator(has ? new OvershootInterpolator(1.3f) : new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        // border 0: no frame, the glass edge stays
        v.setForeground(has && border != 0 ? rounded(Color.TRANSPARENT, radius, border) : v instanceof LinearLayout ? null : Glass.rim(radius, rimWidth()));
    }

    private void launch(App a) {
        try {
            startActivity(a.launch);
        } catch (Exception e) {
            Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (i != null) startActivity(i);
        }
    }

    // ---------------------------------------------------------------- continue watching

    /** the row follows the highlighted app, after a short pause so scrolling past apps stays smooth */
    private void followApp(App a) {
        if (rowApp == a) return;
        rowApp = a;
        ui.removeCallbacks(pendingRow);
        pendingRow = this::loadWatchNext;
        ui.postDelayed(pendingRow, 250);
    }

    private void loadWatchNext() {
        App a = rowApp;
        io.execute(() -> {
            WatchNext.Row r = a == null ? null : WatchNext.forApp(this, a.pkg, a.label);
            // what Pi Home saw the app play comes first: "Reprendre"
            List<WatchNext.Item> mine = a == null ? new ArrayList<>() : Resume.load(this, a.pkg);
            if (r != null && !mine.isEmpty()) {
                Set<String> titles = new HashSet<>();
                for (WatchNext.Item it : r.items) titles.add(it.title.toLowerCase(java.util.Locale.ROOT));
                List<WatchNext.Item> merged = new ArrayList<>();
                for (WatchNext.Item it : mine) if (!titles.contains(it.title.toLowerCase(java.util.Locale.ROOT))) merged.add(it);
                if (!merged.isEmpty()) {
                    merged.addAll(r.items);
                    r.items = merged;
                    r.label = r.label.equals(a.label) ? a.label + " · Reprendre"
                            : r.label.replaceFirst(java.util.regex.Pattern.quote(a.label) + " · ", a.label + " · Reprendre · ");
                }
            }
            if (r == null || r.items.isEmpty()) {
                // apps that show nothing: everyone's "continue watching", what Pi Home saw first
                Set<String> hidden = new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(",")));
                r = new WatchNext.Row();
                r.label = "Continuer à regarder";
                Set<String> titles = new HashSet<>();
                for (WatchNext.Item it : Resume.load(this, null)) {
                    if (!hidden.contains(it.pkg) && titles.add(it.title.toLowerCase(java.util.Locale.ROOT))) r.items.add(it);
                }
                for (WatchNext.Item it : WatchNext.load(this, hidden)) {
                    if (titles.add(it.title.toLowerCase(java.util.Locale.ROOT))) r.items.add(it);
                }
                if (r.items.size() > 15) r.items = new ArrayList<>(r.items.subList(0, 15));
            }
            WatchNext.Row row = r;
            ui.post(() -> {
                if (rowApp == a) showWatchNext(row);
            });
        });
    }

    private void showWatchNext(WatchNext.Row row) {
        List<WatchNext.Item> items = row.items;
        StringBuilder key = new StringBuilder(row.label);
        for (WatchNext.Item it : items) key.append('|').append(it.title).append(it.position);
        rowFor = rowApp;
        if (key.toString().equals(rowKey)) {
            updatePeek();
            return; // unchanged: keep the posters already shown
        }
        rowKey = key.toString();
        watchLabel.setText(row.label);
        // the row's app, as a small rounded icon before its name
        App owner = rowApp != null && row.label.startsWith(rowApp.label) ? rowApp : null;
        if (owner != null) {
            watchLabel.setCompoundDrawablesRelative(roundedIcon(owner.square, dp(16), dp(4)), null, null, null);
        } else watchLabel.setCompoundDrawablesRelative(null, null, null, null);
        boolean hadFocus = watchRow.hasFocus();
        watchRow.removeAllViews();
        int w = dp(200), h = w * 9 / 16;
        for (WatchNext.Item it : items) {
            View card = watchCard(it, w, h);
            watchRow.addView(card);
        }
        if (hadFocus) {
            if (watchRow.getChildCount() > 0) focus(watchRow.getChildAt(0));
            else {
                closeShelf();
                focus(rowApp != null && rowApp.tile.getParent() == dock ? rowApp.tile : apps.get(0).tile);
            }
        }
        updatePeek();
    }

    /**
     * a card in the style of Apple TV's Up Next: the artwork, rounded, with the progress along its
     * foot; the title and a quieter second line under it. Only the artwork grows when highlighted.
     */
    private View watchCard(WatchNext.Item it, int w, int h) {
        int iw = dp(196), ih = iw * 9 / 16, r = dp(11);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setClipChildren(false);
        it.color = 0xFF5A5A6E;

        FrameLayout art = new FrameLayout(this);
        art.setBackground(rounded(0xFF1E1E26, r));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        art.setClipToOutline(true);
        art.setForeground(Glass.rim(r, rimWidth()));
        art.setElevation(dp(4));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (it.duration > 0 && it.position > 0) {
            // a shade under the bar, so it shows on light pictures
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0x99000000, 0x00000000}));
            art.addView(shade, new FrameLayout.LayoutParams(-1, ih / 3, Gravity.BOTTOM));
            ClipDrawable bar = new ClipDrawable(rounded(Color.WHITE, dp(2)), Gravity.START, ClipDrawable.HORIZONTAL);
            bar.setLevel(Math.round(Math.min(1f, it.position / (float) it.duration) * 10000));
            View track = new View(this);
            track.setBackground(new LayerDrawable(new Drawable[] {rounded(0x4DFFFFFF, dp(2)), bar}));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
            bp.setMargins(dp(10), 0, dp(10), dp(8));
            art.addView(track, bp);
        }
        t.addView(art, new LinearLayout.LayoutParams(iw, ih));

        TextView name = text(12, Color.WHITE, "sans-serif-medium");
        name.setText(it.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(5), 0, dp(1), 0xB3000000);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(iw, -2);
        np.topMargin = dp(9);
        np.leftMargin = dp(2);
        t.addView(name, np);
        String app = appLabel(it.pkg);
        String left = it.duration > it.position && it.position > 0
                ? "reste " + Math.max(1, (it.duration - it.position) / 60000) + " min" : "";
        String second = it.subtitle != null && !it.subtitle.isEmpty() ? it.subtitle + (left.isEmpty() ? "" : " · " + left)
                : !left.isEmpty() ? app + " · " + left : app;
        TextView sub = text(10, 0xBFFFFFFF, "sans-serif");
        sub.setText(second);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setShadowLayer(dp(5), 0, dp(1), 0xB3000000);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(iw, -2);
        sp.topMargin = dp(2);
        sp.leftMargin = dp(2);
        t.addView(sub, sp);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMargins(dp(8), dp(10), dp(8), dp(4));
        t.setLayoutParams(cp);

        t.setOnFocusChangeListener((v, has) -> {
            unclip(art);
            raise(art, has, 1.08f, Color.WHITE, r);
            art.animate().translationY(has ? -dp(4) : 0).setDuration(has ? 190 : 150).start();
            art.setElevation(has ? dp(14) : dp(4));
            name.animate().translationY(has ? dp(5) : 0).setDuration(190).start();
            sub.animate().translationY(has ? dp(5) : 0).setDuration(190).start();
            if (has) {
                openShelf();
                title.setText(it.title);
                glow.setColor(it.color);
            }
        });
        t.setOnClickListener(v -> {
            // a programme Pi Home saw playing: back to the player's own screen when it can
            if (Resume.open(it)) return;
            try {
                startActivity(WatchNext.intentOf(it));
            } catch (Exception e) {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
                if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });

        int px = Math.round(iw * 1.15f);
        io.execute(() -> {
            Bitmap b = WatchNext.poster(this, it.poster, px);
            if (b == null) return;
            int c = colorOfBitmap(b);
            ui.post(() -> {
                img.setImageBitmap(b);
                it.color = c;
            });
        });
        return t;
    }

    /** an app's square icon, small and rounded, to sit beside text */
    private Drawable roundedIcon(Bitmap src, int size, int radius) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(size / (float) src.getWidth(), size / (float) src.getHeight());
        sh.setLocalMatrix(m);
        p.setShader(sh);
        new Canvas(out).drawRoundRect(0, 0, size, size, radius, radius, p);
        Drawable d = new android.graphics.drawable.BitmapDrawable(getResources(), out);
        d.setBounds(0, 0, size, size);
        return d;
    }

    private String appLabel(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a.label;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------- backdrop

    private void showBackdrop(WatchNext.Item it) {
        backdropFor = it;
        int px = getResources().getDisplayMetrics().widthPixels / 2;
        io.execute(() -> {
            Bitmap b = WatchNext.poster(this, it.poster, px);
            ui.post(() -> {
                if (backdropFor != it || b == null) return;
                backdrop.setImageBitmap(b);
                if (!shelfOpen) return;
                backdropLayer.animate().alpha(1f).setDuration(450).withEndAction(this::scheduleGlass).start();
            });
        });
    }

    private void hideBackdrop() {
        backdropFor = null;
        backdropLayer.animate().alpha(0f).setDuration(300).withEndAction(this::scheduleGlass).start();
    }

    // ---------------------------------------------------------------- now playing

    private void watchSessions() {
        try {
            sessions = getSystemService(MediaSessionManager.class);
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, null, ui);
            pickSession(sessions.getActiveSessions(null));
        } catch (Exception e) {
            sessions = null; // MEDIA_CONTENT_CONTROL missing: no "now playing"
        }
    }

    private void pickSession(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(playingChanged);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                if (c.getPackageName().equals(getPackageName())) continue;
                c.registerCallback(playingChanged, ui);
                watched.add(c);
            }
        }
        choosePlaying();
    }

    /** while something plays, where it is gets saved every minute for "Reprendre" */
    private final Runnable resumeTick = new Runnable() {
        @Override
        public void run() {
            recordSessions();
            if (playing != null && playing.getPlaybackState() != null
                    && playing.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) ui.postDelayed(this, 60_000);
        }
    };

    private void recordSessions() {
        List<MediaController> list = new ArrayList<>(watched);
        io.execute(() -> {
            for (MediaController c : list) Resume.record(this, c);
        });
    }

    /** the playing session wins, else a paused one */
    private void choosePlaying() {
        MediaController best = null;
        {
            for (MediaController c : watched) {
                PlaybackState st = c.getPlaybackState();
                if (st == null || c.getMetadata() == null) continue;
                int s = st.getState();
                if (s == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null && (s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING)) best = c;
            }
        }
        playing = best;
        showNowPlaying();
        recordSessions();
        ui.removeCallbacks(resumeTick);
        if (best != null) ui.postDelayed(resumeTick, 60_000);
    }

    private void showNowPlaying() {
        MediaController c = playing;
        PlaybackState st = c == null ? null : c.getPlaybackState();
        MediaMetadata md = c == null ? null : c.getMetadata();
        int state = st == null ? PlaybackState.STATE_NONE : st.getState();
        boolean show = md != null && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING);
        if (!show) {
            if (nowPill.hasFocus()) focus(controlPill);
            nowPill.setVisibility(View.GONE);
            relinkTopBar();
            return;
        }
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        nowPill.setText((t != null ? t + " · " : "") + appLabel(c.getPackageName()));
        Drawable d = getDrawable(state == PlaybackState.STATE_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        nowPill.setCompoundDrawablesRelative(d, null, null, null);
        nowPill.setCompoundDrawableTintList(ColorStateList.valueOf(nowPill.hasFocus() ? Color.BLACK : Color.WHITE));
        nowPill.setVisibility(View.VISIBLE);
        relinkTopBar();
    }

    private void togglePlayback() {
        if (playing == null || playing.getPlaybackState() == null) return;
        if (playing.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) playing.getTransportControls().pause();
        else playing.getTransportControls().play();
    }

    // ---------------------------------------------------------------- weather

    private void loadWeather() {
        io.execute(() -> {
            Weather.Now w = Weather.fetch(this);
            ui.post(() -> {
                if (w == null) { // keep the last reading, try again shortly
                    ui.removeCallbacks(weatherRetry);
                    ui.postDelayed(weatherRetry, 60_000);
                    return;
                }
                weather.setText(w.text);
                Drawable d = getDrawable(w.icon).mutate();
                d.setBounds(0, 0, dp(22), dp(22));
                d.setTint(Color.WHITE);
                weather.setCompoundDrawablesRelative(d, null, null, null);
                weather.setVisibility(View.VISIBLE);
            });
        });
    }

    // ---------------------------------------------------------------- sleep timer

    private long sleepAt() {
        long at = prefs.getLong("sleepAt", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    private void showSleepMenu() {
        boolean timer = sleepAt() > 0;
        List<String> labels = new ArrayList<>(Arrays.asList("Maintenant", "Dans 30 min", "Dans 1 h", "Dans 2 h"));
        List<Runnable> actions = new ArrayList<>(Arrays.asList(() -> {
            Toast.makeText(this, "Mise en veille…", Toast.LENGTH_SHORT).show();
            request("sleep");
        }, () -> sleepIn(30), () -> sleepIn(60), () -> sleepIn(120)));
        if (timer) {
            labels.add("Annuler la minuterie");
            actions.add(() -> {
                prefs.edit().remove("sleepAt").apply();
                request("sleep-cancel");
                updateSleepBadge();
            });
        }
        labels.add("Fermer");
        actions.add(null);
        showMenu(timer ? "Veille · " + sleepLeft() : "Mise en veille",
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void sleepIn(int minutes) {
        prefs.edit().putLong("sleepAt", System.currentTimeMillis() + minutes * 60_000L).apply();
        request("sleep-in " + minutes * 60);
        updateSleepBadge();
        Toast.makeText(this, "Veille dans " + sleepLeft(), Toast.LENGTH_SHORT).show();
    }

    private String sleepLeft() {
        long min = Math.max(1, (sleepAt() - System.currentTimeMillis() + 59_999) / 60_000);
        return min >= 60 ? min / 60 + " h" + (min % 60 == 0 ? "" : " " + min % 60) : min + " min";
    }

    /** the 🌙 button shows the time left while a timer runs */
    private void updateSleepBadge() {
        boolean on = sleepAt() > 0;
        sleepPill.setTag(on ? sleepLeft() : "");
        pillText(sleepPill, sleepPill.hasFocus(), "Veille");
        if (!on && sleepPill.hasFocus()) focus(controlPill);
        sleepPill.setVisibility(on ? View.VISIBLE : View.GONE);
        relinkTopBar();
    }

    // ---------------------------------------------------------------- app updates

    /** manual: say so when everything is already up to date */
    private void checkUpdates(boolean manual) {
        if (manual) Toast.makeText(this, "Recherche de mises à jour…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            List<Updates.Update> found = Updates.check(this);
            ui.post(() -> {
                updates = found;
                updatePill.setTag(found.isEmpty() ? "" : String.valueOf(found.size()));
                updatePill.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
                pillText(updatePill, updatePill.hasFocus(), "Mises à jour");
                relinkTopBar();
                if (manual) {
                    if (found.isEmpty()) Toast.makeText(this, "SmartTube et Nuvio sont à jour", Toast.LENGTH_LONG).show();
                    else showUpdatesMenu();
                }
            });
        });
    }

    private void showUpdatesMenu() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (Updates.Update u : updates) {
            labels.add(u.label + " " + u.installed + " → " + u.latest + " (" + Math.round(u.size / 1048576.0) + " Mo)");
            actions.add(() -> install(Arrays.asList(u)));
        }
        if (updates.size() > 1) {
            labels.add("Tout mettre à jour");
            List<Updates.Update> all = new ArrayList<>(updates);
            actions.add(() -> install(all));
        }
        labels.add("Fermer");
        actions.add(null);
        showMenu(updates.isEmpty() ? "Tout est à jour" : "Mises à jour disponibles",
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    /** downloads here, then the root bridge installs (pm install -r) without asking */
    private void install(List<Updates.Update> list) {
        Toast.makeText(this, "Téléchargement…", Toast.LENGTH_LONG).show();
        io.execute(() -> {
            StringBuilder paths = new StringBuilder();
            for (Updates.Update u : list) {
                try {
                    paths.append(' ').append(Updates.download(this, u).getPath());
                    ui.post(() -> updating.add(u.pkg));
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(this, "Échec du téléchargement de " + u.label, Toast.LENGTH_LONG).show());
                }
            }
            if (paths.length() == 0) return;
            ui.post(() -> {
                Toast.makeText(this, "Installation…", Toast.LENGTH_LONG).show();
                request("install" + paths);
                // the PACKAGE_ADDED broadcast clears each one; whatever is left by then failed
                ui.postDelayed(() -> {
                    for (String pkg : new ArrayList<>(updating)) {
                        Toast.makeText(this, "La mise à jour de " + appLabel(pkg) + " a échoué", Toast.LENGTH_LONG).show();
                        updating.remove(pkg);
                    }
                }, 5 * 60_000L);
            });
        });
    }

    // ---------------------------------------------------------------- prayer times

    private void loadPrayers() {
        io.execute(() -> {
            String[] t = Prayers.today(this);
            String day = new java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).format(new java.util.Date());
            ui.post(() -> {
                if (t == null) return;
                prayerTimes = t;
                prayerDay = day;
                prayerTick();
            });
        });
    }

    /** every minute: the "next prayer" line, the reminder, and a new day's times after midnight */
    private void prayerTick() {
        String day = new java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).format(new java.util.Date());
        if (prayerTimes == null || !day.equals(prayerDay)) {
            if (prayerTimes != null) loadPrayers();
            return;
        }
        Prayers.Next next = Prayers.next(prayerTimes);
        prayerLine.setText(next != null
                ? "Prochaine prière · " + next.name + " " + String.format(java.util.Locale.FRANCE, "%02d:%02d",
                        next.at.get(Calendar.HOUR_OF_DAY), next.at.get(Calendar.MINUTE))
                : "Fajr demain · " + prayerTimes[0]);
        Drawable d = getDrawable(R.drawable.ic_mosque).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        d.setTint(0xD9FFFFFF);
        prayerLine.setCompoundDrawablesRelative(d, null, null, null);
        prayerLine.setVisibility(View.VISIBLE);

        String now = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
        for (int i = 0; i < prayerTimes.length; i++) {
            String key = day + Prayers.NAMES[i];
            if (prayerTimes[i].equals(now) && !key.equals(prayerShown)) {
                prayerShown = key;
                if (prefs.getBoolean("prayerReminder", true)) prayerReminder(Prayers.NAMES[i], prayerTimes[i]);
            }
        }
    }

    /** a banner over whatever is on screen, for 30 seconds */
    private void prayerReminder(String name, String time) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(14), dp(24), dp(14));
        card.setBackground(new Glass.Panel(card, null, dp(22), 0xE61C1C22, rimWidth()));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mosque);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        card.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(14), 0, 0, 0);
        TextView a = text(16, Color.WHITE, "sans-serif-medium");
        a.setText("C'est l'heure de la prière");
        TextView b = text(13, 0xB3FFFFFF, "sans-serif");
        b.setText(name + " · " + time);
        lines.addView(a);
        lines.addView(b);
        card.addView(lines);

        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "C'est l'heure de la prière · " + name + " " + time, Toast.LENGTH_LONG).show();
            return;
        }
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                -2, -2, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        // at the top right, like a notification: clear of the clock and of whatever app is open
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(24);
        lp.y = dp(20);
        card.setAlpha(0f);
        try {
            wm.addView(card, lp);
        } catch (Exception e) {
            return;
        }
        card.animate().alpha(1f).setDuration(400).start();
        ui.postDelayed(() -> card.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                wm.removeView(card);
            } catch (Exception ignored) {
            }
        }).start(), 30_000);
    }

    // ---------------------------------------------------------------- settings menu and Infos Pi

    /** a glass panel at the right: the network, Bluetooth, memory, sleep, updates and the Pi */
    private void showControlPanel() {
        LinearLayout card = menuCard("Centre de contrôle");
        GridLayout g = new GridLayout(this);
        g.setColumnCount(2);
        boolean bt = Settings.Global.getInt(getContentResolver(), "bluetooth_on", 0) == 1;
        View first = ctlTile(g, R.drawable.ic_wifi, 0xFF0A84FF, offline ? "Hors ligne" : "Réseau", offline ? "Wi-Fi et réseau" : netName,
                () -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        ctlTile(g, R.drawable.ic_bluetooth, 0xFF0A84FF, "Bluetooth", bt ? "Activé" : "Désactivé",
                () -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        View mem = ctlTile(g, R.drawable.ic_memory, Color.WHITE, "Mémoire", "", this::showPiInfo);
        memLine = (TextView) ((LinearLayout) ((LinearLayout) mem).getChildAt(1)).getChildAt(1);
        memBar = new ClipDrawable(rounded(Color.WHITE, dp(2)), Gravity.START, ClipDrawable.HORIZONTAL);
        View bar = new View(this);
        bar.setBackground(new LayerDrawable(new Drawable[] {rounded(0x33FFFFFF, dp(2)), memBar}));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(80), dp(3));
        bp.topMargin = dp(4);
        ((LinearLayout) ((LinearLayout) mem).getChildAt(1)).addView(bar, bp);
        showMemory();
        long at = sleepAt();
        ctlTile(g, R.drawable.ic_sleep, Color.WHITE, "Minuterie de veille", at > 0 ? "Dans " + sleepLeft() : "Désactivée", this::showSleepMenu);
        ctlTile(g, R.drawable.ic_update, 0xFF30D158, "Mises à jour",
                updates.isEmpty() ? "Tout est à jour" : updates.size() + (updates.size() > 1 ? " disponibles" : " disponible"),
                () -> {
                    if (updates.isEmpty()) checkUpdates(true);
                    else showUpdatesMenu();
                });
        ctlTile(g, R.drawable.ic_bluetooth, 0xFFFF9F0A, "Réparer le Bluetooth", "Redémarre la puce", () -> {
            // the chip itself is reset, not just Android's side (see lg-simplink.sh)
            Toast.makeText(this, "Redémarrage du Bluetooth… (une quinzaine de secondes)", Toast.LENGTH_LONG).show();
            request("bt-restart");
        });
        card.addView(g);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        smallTile(row, R.drawable.ic_settings, "Paramètres", () -> openSettings(Settings.ACTION_SETTINGS));
        smallTile(row, R.drawable.ic_info, "Infos Pi", this::showPiInfo);
        smallTile(row, R.drawable.ic_image, "Photo", this::showPhotoMenu);
        smallTile(row, R.drawable.ic_restart, "Redémarrer", () -> showMenu("Redémarrer le Pi ?",
                new String[] {"Redémarrer", "Annuler"},
                new Runnable[] {() -> {
                    Toast.makeText(this, "Redémarrage…", Toast.LENGTH_SHORT).show();
                    request("reboot");
                }, null}));
        smallTile(row, R.drawable.ic_power, "Veille", () -> {
            Toast.makeText(this, "Mise en veille…", Toast.LENGTH_SHORT).show();
            request("sleep");
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(4);
        card.addView(row, rp);
        openMenu(card, first, dp(358), Gravity.END | Gravity.TOP);
    }

    // ---------------------------------------------------------------- recent apps

    /** the apps used lately, latest first: OK opens one, Up closes it */
    private void showRecents() {
        Map<String, Long> last = new HashMap<>();
        try {
            android.app.usage.UsageStatsManager um = getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            // up to two years ahead: the Pi has no clock battery, and a boot with a wrong date can
            // leave Android filing today's use in a bucket dated in the future, which a query
            // ending now would miss; the use times inside stay right, and are filtered here
            for (android.app.usage.UsageStats u : um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST,
                    now - 7 * 86_400_000L, now + 2 * 365 * 86_400_000L)) {
                long t = u.getLastTimeUsed();
                if (t > now - 7 * 86_400_000L && t <= now + 60_000) last.merge(u.getPackageName(), t, Math::max);
            }
        } catch (Exception ignored) {
        }
        List<App> recent = new ArrayList<>();
        for (App a : apps) if (last.containsKey(a.pkg)) recent.add(a);
        recent.sort((x, y) -> Long.compare(last.get(y.pkg), last.get(x.pkg)));
        if (recent.size() > 8) recent = new ArrayList<>(recent.subList(0, 8));

        if (recent.isEmpty()) {
            LinearLayout card = menuCard("Apps récentes");
            TextView none = text(12, 0xB3FFFFFF, "sans-serif");
            none.setText("Aucune app utilisée ces derniers jours.");
            none.setPadding(dp(5), dp(4), dp(5), dp(8));
            card.addView(none);
            View close = menuButton("Fermer", null);
            card.addView(close);
            openMenu(card, close);
            return;
        }
        // as macOS's Stage Manager: no panel, the apps' windows float down the left edge, turned
        // towards the middle of the screen, each with its icon on its corner
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(dp(26), dp(10), dp(10), dp(10));
        strip.setBackground(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        View first = null;
        for (App a : recent) {
            View cell = recentCell(a, last.get(a.pkg), strip);
            strip.addView(cell);
            if (first == null) first = cell;
        }
        List<App> all = recent;
        TextView closeAll = text(10.5f, 0xCCFFFFFF, "sans-serif-medium");
        closeAll.setText("Tout fermer");
        closeAll.setFocusable(true);
        closeAll.setClickable(true);
        closeAll.setPadding(dp(12), dp(6), dp(12), dp(6));
        closeAll.setBackground(rounded(0x33FFFFFF, dp(14)));
        closeAll.setOnFocusChangeListener((v, has) -> {
            closeAll.setBackground(rounded(has ? Color.WHITE : 0x33FFFFFF, dp(14)));
            closeAll.setTextColor(has ? Color.BLACK : 0xCCFFFFFF);
        });
        closeAll.setOnClickListener(v -> {
            for (App a : all) stopApp(a.pkg);
            closeMenu(true);
            Toast.makeText(this, "Apps fermées", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(-2, -2);
        cap.topMargin = dp(10);
        strip.addView(closeAll, cap);
        LinearLayout card = strip;
        // the screen as it is, lightly blurred behind the windows: still recognisable
        menuLayer.removeAllViews();
        menuLayer.setBackgroundColor(Color.TRANSPARENT);
        behindRecents.capture(root.getWidth(), root.getHeight(), root::draw);
        openMenu(card, first, dp(250), Gravity.START | Gravity.CENTER_VERTICAL);
        menuLayer.setBackground(new Glass.Panel(menuLayer, behindRecents, 0, 0x40000000, 0));
    }

    private View recentCell(App a, long usedAt, LinearLayout column) {
        int w = dp(150), h = w * 9 / 16, r = dp(8), ic = dp(40);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        FrameLayout win = new FrameLayout(this);
        win.setClipChildren(false);
        // the window: the app's TV banner, else its icon on its own colour
        FrameLayout shot = tileFrame(r);
        shot.setFocusable(false);
        shot.setClickable(false);
        shot.setBackgroundColor(a.color);
        shot.setElevation(dp(10));
        if (a.banner != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner.getConstantState() != null ? a.banner.getConstantState().newDrawable() : a.banner);
            shot.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // no banner: the app's tile artwork, edge to edge, as its screen
            iconInto(shot, a, w);
        }
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(w, h);
        sp.leftMargin = dp(8);
        win.addView(shot, sp);
        // its icon over the bottom left corner
        FrameLayout badge = tileFrame(ic * 24 / 100);
        badge.setFocusable(false);
        badge.setClickable(false);
        badge.setElevation(dp(14));
        iconInto(badge, a, ic);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ic, ic, Gravity.BOTTOM | Gravity.START);
        bp.bottomMargin = -dp(10);
        win.addView(badge, bp);
        // turned towards the middle of the screen, in perspective
        win.setCameraDistance(dp(900));
        win.setPivotX(0);
        win.setPivotY(h / 2f);
        win.setRotationY(28f);
        cell.addView(win, new LinearLayout.LayoutParams(w + dp(8), h));
        // the name shows on the highlighted one only, as on the Mac's hover
        TextView name = text(11, Color.WHITE, "sans-serif-medium");
        name.setText(a.label + "  ·  " + ago(usedAt));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAlpha(0f);
        name.setPadding(dp(8), dp(14), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(w + dp(30), -2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMargins(0, dp(6), 0, dp(6));
        cell.setLayoutParams(cp);
        cell.setOnFocusChangeListener((v, has) -> {
            unclip(win);
            win.animate().rotationY(has ? 8f : 28f).scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .translationX(has ? dp(10) : 0).setDuration(240)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            shot.setForeground(has ? rounded(Color.TRANSPARENT, r, Color.WHITE) : Glass.rim(r, rimWidth()));
            name.animate().alpha(has ? 1f : 0f).setDuration(180).start();
        });
        cell.setOnClickListener(v -> {
            closeMenu(false);
            launch(a);
        });
        cell.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeMenu(true);
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            // left: the app is closed, its window slides off the screen's edge
            stopApp(a.pkg);
            int i = column.indexOfChild(cell);
            cell.animate().translationX(-dp(260)).alpha(0f).setDuration(220).withEndAction(() -> {
                column.removeView(cell);
                View next = column.getChildAt(Math.min(i, column.getChildCount() - 2));
                if (column.getChildCount() <= 1 || next == null) closeMenu(true);
                else focus(next);
            }).start();
            return true;
        });
        return cell;
    }

    private final Set<String> stopping = new HashSet<>();
    private final Runnable sendStops = () -> {
        if (stopping.isEmpty()) return;
        request("stop " + TextUtils.join(" ", stopping));
        stopping.clear();
    };

    /** closes an app: Android drops what it can at once, the root bridge force-stops it */
    private void stopApp(String pkg) {
        try {
            getSystemService(android.app.ActivityManager.class).killBackgroundProcesses(pkg);
        } catch (Exception ignored) {
        }
        // one request for several closes in a row: the bridge reads one file a second
        stopping.add(pkg);
        ui.removeCallbacks(sendStops);
        ui.postDelayed(sendStops, 600);
    }

    private static String ago(long t) {
        long min = Math.max(0, (System.currentTimeMillis() - t) / 60_000);
        if (min < 1) return "à l'instant";
        if (min < 60) return "il y a " + min + " min";
        if (min < 24 * 60) return "il y a " + (min / 60) + " h";
        long d = min / (24 * 60);
        return d == 1 ? "hier" : "il y a " + d + " jours";
    }

    // ---------------------------------------------------------------- search

    /**
     * a keyboard over the blurred screen: apps and Pi Home's own actions match as letters are
     * typed, and the words can be sent on to Pi TV or SmartTube. The field is a real text field:
     * the remote types with the keys below, the phone's Google TV app or a keyboard straight in.
     */
    private void showSearch() {
        LinearLayout card = menuCard("Rechercher");
        EditText field = new EditText(this);
        field.setTextSize(16);
        field.setTextColor(Color.WHITE);
        field.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        field.setHint("Apps, réglages, films…");
        field.setHintTextColor(0x80FFFFFF);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // the TV's own keyboard would cover the card: it opens only on OK, the keys below are there
        field.setShowSoftInputOnFocus(false);
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setBackground(rounded(0x26FFFFFF, dp(10)));
        field.setOnFocusChangeListener((v, has) -> field.setBackground(has
                ? rounded(0x26FFFFFF, dp(10), Color.WHITE) : rounded(0x26FFFFFF, dp(10))));
        field.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.showSoftInput(field, 0);
        });
        Drawable sd = getDrawable(R.drawable.ic_search).mutate();
        sd.setBounds(0, 0, dp(14), dp(14));
        sd.setTint(0x99FFFFFF);
        field.setCompoundDrawablesRelative(sd, null, null, null);
        field.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.bottomMargin = dp(8);
        card.addView(field, fp);

        LinearLayout body = new LinearLayout(this);
        body.setClipChildren(false);
        GridLayout keys = new GridLayout(this);
        keys.setColumnCount(10);
        keys.setClipChildren(false);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        Runnable[] refresh = new Runnable[1];
        // the on-screen keys write into the field, as if typed at its end
        java.util.function.Consumer<String> type = k -> {
            String t = field.getText().toString();
            if (k.equals("⌫")) {
                if (!t.isEmpty()) t = t.substring(0, t.length() - 1);
            } else if (k.equals("effacer")) t = "";
            else t += k;
            field.setText(t);
            field.setSelection(t.length());
        };
        View first = null;
        for (String k : "a z e r t y u i o p q s d f g h j k l m w x c v b n é è ' - 1 2 3 4 5 6 7 8 9 0".split(" ")) {
            View b = key(k, dp(29), true, type);
            keys.addView(b);
            if (first == null) first = b;
        }
        LinearLayout keysCol = new LinearLayout(this);
        keysCol.setOrientation(LinearLayout.VERTICAL);
        keysCol.setClipChildren(false);
        keysCol.addView(keys);
        LinearLayout bottom = new LinearLayout(this);
        bottom.setClipChildren(false);
        bottom.addView(key("espace", dp(29 * 5 + 2 * 8), false, k -> type.accept(" ")));
        bottom.addView(key("⌫", dp(29 * 2 + 2 * 2), false, type));
        bottom.addView(key("effacer", dp(29 * 3 + 2 * 4), false, type));
        keysCol.addView(bottom);
        body.addView(keysCol);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = dp(14);
        body.addView(results, rp);
        card.addView(body);

        refresh[0] = () -> {
            String text = field.getText().toString();
            boolean had = results.hasFocus();
            results.removeAllViews();
            String n = norm(text.trim());
            int count = 0;
            if (!n.isEmpty()) {
                for (App a : apps) {
                    if (count >= 4 || !norm(a.label).contains(n)) continue;
                    results.addView(resultRow(roundedIcon(a.square, dp(18), dp(5)), a.label, "App", () -> launch(a)));
                    count++;
                }
                for (Object[] act : actions()) {
                    if (count >= 6 || !norm((String) act[0] + " " + act[1]).contains(n)) continue;
                    results.addView(resultRow(tinted((Integer) act[2]), (String) act[0], "Pi Home", (Runnable) act[3]));
                    count++;
                }
                if (n.length() >= 2) {
                    String words = text.trim();
                    if (findApp("com.sall.pitv") != null) {
                        results.addView(resultRow(roundedIcon(findApp("com.sall.pitv").square, dp(18), dp(5)),
                                "« " + words + " » dans Pi TV", "Chaînes, films et séries", () -> {
                                    Intent i = getPackageManager().getLaunchIntentForPackage("com.sall.pitv");
                                    if (i == null) return;
                                    startActivity(i.putExtra("pitv.search", words).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                }));
                    }
                    App tube = findApp("org.smarttube.stable");
                    Intent ts = new Intent(Intent.ACTION_SEARCH).setPackage("org.smarttube.stable").putExtra(android.app.SearchManager.QUERY, words);
                    if (tube != null && !getPackageManager().queryIntentActivities(ts, 0).isEmpty()) {
                        results.addView(resultRow(roundedIcon(tube.square, dp(18), dp(5)), "« " + words + " » sur YouTube", "SmartTube",
                                () -> startActivity(ts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
                    }
                }
                if (results.getChildCount() == 0) {
                    TextView none = text(11, 0x99FFFFFF, "sans-serif");
                    none.setText("Rien pour « " + text.trim() + " »");
                    none.setPadding(dp(6), dp(6), dp(6), dp(6));
                    results.addView(none);
                }
            } else {
                TextView tip = text(11, 0x99FFFFFF, "sans-serif");
                tip.setText("Tapez quelques lettres : les apps et les réglages apparaissent ici, à droite.");
                tip.setPadding(dp(6), dp(6), dp(6), dp(6));
                results.addView(tip);
            }
            if (had && results.getChildAt(0).isFocusable()) focus(results.getChildAt(0));
        };
        // whatever writes into the field (the keys, the phone, a keyboard) refreshes the results
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refresh[0].run();
            }
        });
        // "Search" on the phone's or the TV's keyboard: to the first result
        field.setOnEditorActionListener((v, action, e) -> {
            View r = results.getChildAt(0);
            if (r != null && r.isFocusable()) focus(r);
            return true;
        });
        // letters typed on a keyboard while a key has the highlight go into the field too
        View.OnKeyListener typed = (v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DEL) {
                type.accept("⌫");
                return true;
            }
            int c = e.getUnicodeChar();
            if (c == 0 || Character.isISOControl(c)) return false;
            type.accept(String.valueOf((char) c).toLowerCase());
            return true;
        };
        for (int i = 0; i < keys.getChildCount(); i++) keys.getChildAt(i).setOnKeyListener(typed);
        for (int i = 0; i < bottom.getChildCount(); i++) bottom.getChildAt(i).setOnKeyListener(typed);
        refresh[0].run();
        openMenu(card, first, dp(560), Gravity.CENTER);
    }

    /** a keyboard key; inGrid: one of the letters, in the grid, else a wide key in the row below */
    private View key(String k, int w, boolean inGrid, java.util.function.Consumer<String> type) {
        TextView b = text(k.length() > 1 ? 10.5f : 13, Color.WHITE, "sans-serif-medium");
        b.setText(k);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(rounded(0x1AFFFFFF, dp(7)));
        b.setOnFocusChangeListener((v, has) -> {
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setBackground(rounded(has ? Color.WHITE : 0x1AFFFFFF, dp(7)));
        });
        b.setOnClickListener(v -> type.accept(k));
        ViewGroup.MarginLayoutParams p = new GridLayout.LayoutParams();
        p.width = w;
        p.height = dp(29);
        p.setMargins(dp(1), dp(1), dp(1), dp(1));
        b.setLayoutParams(inGrid ? p : new LinearLayout.LayoutParams(p));
        return b;
    }

    private View resultRow(Drawable icon, String label, String sub, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(6), dp(8), dp(6));
        r.setFocusable(true);
        r.setClickable(true);
        ImageView img = new ImageView(this);
        img.setImageDrawable(icon);
        r.addView(img, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(12, Color.WHITE, "sans-serif-medium");
        a.setText(label);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        TextView b = text(9.5f, 0x99FFFFFF, "sans-serif");
        b.setText(sub);
        lines.addView(a);
        lines.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(10);
        r.addView(lines, lp);
        r.setBackground(rounded(Color.TRANSPARENT, dp(9)));
        r.setOnFocusChangeListener((v, has) -> {
            r.setBackground(rounded(has ? Color.WHITE : Color.TRANSPARENT, dp(9)));
            a.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setTextColor(has ? 0x99000000 : 0x99FFFFFF);
            if (img.getDrawable() != null && icon.getClass() != android.graphics.drawable.BitmapDrawable.class)
                img.setImageTintList(ColorStateList.valueOf(has ? Color.BLACK : Color.WHITE));
        });
        r.setOnClickListener(v -> {
            closeMenu(true);
            action.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(2);
        r.setLayoutParams(p);
        return r;
    }

    private Drawable tinted(int res) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(Color.WHITE);
        return d;
    }

    /** Pi Home's own actions, for the search: {name, other words, icon, action} */
    private List<Object[]> actions() {
        return Arrays.asList(
                new Object[] {"Paramètres Android", "settings reglages", R.drawable.ic_settings, (Runnable) () -> openSettings(Settings.ACTION_SETTINGS)},
                new Object[] {"Wi-Fi et réseau", "wifi internet reseau ethernet", R.drawable.ic_wifi, (Runnable) () -> openSettings(Settings.ACTION_WIFI_SETTINGS)},
                new Object[] {"Bluetooth", "casque manette appairer", R.drawable.ic_bluetooth, (Runnable) () -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)},
                new Object[] {"Réparer le Bluetooth", "redemarrer bluetooth puce", R.drawable.ic_bluetooth, (Runnable) () -> {
                    Toast.makeText(this, "Redémarrage du Bluetooth… (une quinzaine de secondes)", Toast.LENGTH_LONG).show();
                    request("bt-restart");
                }},
                new Object[] {"Infos Pi", "temperature memoire stockage ip donnees", R.drawable.ic_info, (Runnable) this::showPiInfo},
                new Object[] {"Mises à jour", "update smarttube nuvio", R.drawable.ic_update, (Runnable) () -> checkUpdates(true)},
                new Object[] {"Minuterie de veille", "sleep dormir", R.drawable.ic_sleep, (Runnable) this::showSleepMenu},
                new Object[] {"Mettre en veille", "eteindre ecran sleep", R.drawable.ic_power, (Runnable) () -> request("sleep")},
                new Object[] {"Redémarrer le Pi", "reboot", R.drawable.ic_restart, (Runnable) () -> showMenu("Redémarrer le Pi ?",
                        new String[] {"Redémarrer", "Annuler"}, new Runnable[] {() -> request("reboot"), null})},
                new Object[] {"Photo suivante", "fond ecran wallpaper image", R.drawable.ic_image, (Runnable) () -> {
                    Wallpaper.skip(this);
                    loadWallpaper();
                }},
                new Object[] {"Apps récentes", "recent fermer", R.drawable.ic_recent, (Runnable) this::showRecents},
                new Object[] {"Toutes les apps", "applications", R.drawable.ic_apps, (Runnable) this::showAllApps},
                new Object[] {"Centre de contrôle", "controle panneau", R.drawable.ic_controls, (Runnable) this::showControlPanel});
    }

    /** lower case, without accents: "Réseau" is found with "reseau" */
    private static String norm(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.FRENCH), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /** the wallpaper: another one now, or where they come from */
    private void showPhotoMenu() {
        boolean bing = Wallpaper.BING.equals(Wallpaper.source(this));
        showMenu("Photo d'accueil", new String[] {"Photo suivante",
                        (bing ? "" : "✓  ") + "Lacs et montagnes (Wikimedia Commons)",
                        (bing ? "✓  " : "") + "Photo du jour (Bing)", "Fermer"},
                new Runnable[] {() -> {
                    Wallpaper.skip(this);
                    loadWallpaper();
                }, () -> {
                    Wallpaper.setSource(this, Wallpaper.NATURE);
                    loadWallpaper();
                }, () -> {
                    Wallpaper.setSource(this, Wallpaper.BING);
                    loadWallpaper();
                }, null});
    }

    private void showMemory() {
        if (memLine == null || !memLine.isAttachedToWindow() || Float.isNaN(lastRam)) return;
        memLine.setText("RAM " + Math.round(lastRam) + " %" + (Float.isNaN(lastCpu) ? "" : " · CPU " + Math.round(lastCpu) + " %"));
        memBar.setLevel(Math.round(Math.max(0, Math.min(100, lastRam)) * 100));
    }

    /** a wide control-panel tile: a coloured icon, a name and a state; white when highlighted */
    private View ctlTile(GridLayout g, int icon, int tint, String name, String state, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), dp(6), dp(10), dp(6));
        t.setFocusable(true);
        t.setClickable(true);
        ImageView img = new ImageView(this);
        img.setImageResource(icon);
        img.setImageTintList(ColorStateList.valueOf(tint));
        t.addView(img, new LinearLayout.LayoutParams(dp(17), dp(17)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(11.5f, Color.WHITE, "sans-serif-medium");
        a.setText(name);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        TextView b = text(9.5f, 0xB3FFFFFF, "sans-serif");
        b.setText(state);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(2);
        lines.addView(b, bl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(9);
        t.addView(lines, lp);
        int r = dp(22);
        t.setBackground(rounded(0x1FFFFFFF, r));
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(rounded(has ? Color.WHITE : 0x1FFFFFFF, r));
            a.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setTextColor(has ? 0x99000000 : 0xB3FFFFFF);
            if (tint == Color.WHITE) img.setImageTintList(ColorStateList.valueOf(has ? Color.BLACK : Color.WHITE));
            if (has) unclip(v);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(140).start();
        });
        t.setOnClickListener(v -> {
            closeMenu(true);
            action.run();
        });
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = dp(158);
        p.height = dp(44);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        g.addView(t, p);
        return t;
    }

    /** a small square tile under the wide ones: an icon and a short name */
    private void smallTile(LinearLayout row, int icon, String name, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setContentDescription(name);
        ImageView img = new ImageView(this);
        img.setImageResource(icon);
        img.setImageTintList(ColorStateList.valueOf(icon == R.drawable.ic_power ? 0xFFFF453A : Color.WHITE));
        t.addView(img, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView a = text(9, 0xCCFFFFFF, "sans-serif-medium");
        a.setText(name);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = dp(4);
        t.addView(a, ap);
        int r = dp(14);
        t.setBackground(rounded(0x1FFFFFFF, r));
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(rounded(has ? Color.WHITE : 0x1FFFFFFF, r));
            a.setTextColor(has ? Color.BLACK : 0xCCFFFFFF);
            if (icon != R.drawable.ic_power) img.setImageTintList(ColorStateList.valueOf(has ? Color.BLACK : Color.WHITE));
            if (has) unclip(v);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
        });
        t.setOnClickListener(v -> {
            closeMenu(true);
            action.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(t, p);
    }

    private void showPiInfo() {
        LinearLayout card = menuCard("Infos Pi");
        TextView body = text(12, Color.WHITE, "sans-serif");
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(dp(5), dp(4), dp(5), dp(8));
        card.addView(body);
        card.addView(menuButton("Fermer", null));
        String[] usage = {"Données : …"};
        Runnable refresh = new Runnable() {
            int ticks;

            @Override
            public void run() {
                if (!card.isAttachedToWindow()) return;
                if (ticks++ % 30 == 0) {
                    io.execute(() -> {
                        String u = PiInfo.dataUsage(MainActivity.this);
                        ui.post(() -> usage[0] = u);
                    });
                }
                float t = PiInfo.temperature();
                String temp = Float.isNaN(t) ? "inconnue"
                        : String.format(java.util.Locale.FRANCE, "%.1f °C%s", t, t >= 80 ? " · trop chaud !" : t >= 70 ? " · chaud" : "");
                body.setText("Température CPU : " + temp
                        + "\nMémoire : " + PiInfo.memory(MainActivity.this)
                        + "\nStockage : " + PiInfo.storage()
                        + "\nAdresse IP : " + PiInfo.address(MainActivity.this)
                        + "\nAllumé depuis : " + PiInfo.uptime()
                        + "\n" + usage[0]
                        + "\n" + PiInfo.system());
                ui.postDelayed(this, 2000);
            }
        };
        openMenu(card, card.getChildAt(card.getChildCount() - 1));
        card.post(refresh);
    }

    // ---------------------------------------------------------------- app menu

    private void showAppMenu(App a) {
        int i = apps.indexOf(a), n = Math.min(dockSize(), apps.size());
        List<String> labels = new ArrayList<>(Arrays.asList("Déplacer"));
        List<Runnable> actions = new ArrayList<>(Arrays.asList(() -> startMove(a)));
        if (i < n && n > 1) {
            labels.add("Retirer du dock");
            actions.add(() -> {
                apps.remove(a);
                apps.add(n - 1, a);
                prefs.edit().putInt("dockSize", n - 1).apply();
                saveOrder();
                buildTiles(apps.get(0));
            });
        } else if (i >= n && n < MAX_DOCK) {
            labels.add("Ajouter au dock");
            actions.add(() -> {
                apps.remove(a);
                apps.add(n, a);
                prefs.edit().putInt("dockSize", n + 1).apply();
                saveOrder();
                buildTiles(a);
            });
        }
        labels.addAll(Arrays.asList("Masquer", "Renommer", "Infos de l'app", "Annuler"));
        actions.addAll(Arrays.asList(() -> hide(a), () -> showRename(a), () -> appInfo(a), null));
        showMenu(a.label, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void hide(App a) {
        int i = apps.indexOf(a);
        Set<String> hidden = new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(",")));
        hidden.remove("");
        hidden.add(a.pkg);
        prefs.edit().putString("hidden", TextUtils.join(",", hidden)).apply();
        loadApps();
        App next = apps.isEmpty() ? null : apps.get(Math.max(0, Math.min(i, apps.size() - 1)));
        buildTiles(next);
    }

    private void unhide(App a) {
        Set<String> hidden = new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(",")));
        hidden.remove(a.pkg);
        hidden.remove("");
        prefs.edit().putString("hidden", TextUtils.join(",", hidden)).apply();
        loadApps();
        App back = null;
        for (App x : apps) if (x.pkg.equals(a.pkg)) back = x;
        buildTiles(back);
    }

    private void appInfo(App a) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private void showRename(App a) {
        LinearLayout card = menuCard("Renommer « " + a.originalLabel + " »");
        EditText field = new EditText(this);
        field.setText(a.label);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        styleText(field, 16, Color.WHITE, "sans-serif");
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        field.setBackground(rounded(0x33FFFFFF, dp(12)));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(12);
        fp.bottomMargin = dp(6);
        card.addView(field, fp);
        Runnable save = () -> {
            String name = field.getText().toString().trim();
            SharedPreferences.Editor e = prefs.edit();
            if (name.isEmpty() || name.equals(a.originalLabel)) e.remove("label." + a.pkg);
            else e.putString("label." + a.pkg, name);
            e.apply();
            loadApps();
            buildTiles(findApp(a.pkg));
        };
        field.setOnEditorActionListener((v, action, ev) -> {
            closeMenu(false);
            save.run();
            return true;
        });
        card.addView(menuButton("Enregistrer", save));
        card.addView(menuButton("Nom d'origine", () -> {
            prefs.edit().remove("label." + a.pkg).apply();
            loadApps();
            buildTiles(findApp(a.pkg));
        }));
        card.addView(menuButton("Annuler", null));
        openMenu(card, field);
    }

    private App findApp(String pkg) {
        for (App x : apps) if (x.pkg.equals(pkg)) return x;
        return null;
    }

    // ---------------------------------------------------------------- menu overlay

    private void showMenu(String heading, String[] labels, Runnable[] actions) {
        LinearLayout card = menuCard(heading);
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            View b = menuButton(labels[i], actions[i]);
            if (first == null) first = b;
            card.addView(b);
        }
        openMenu(card, first);
    }

    private LinearLayout menuCard(String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(10));
        card.setBackground(new Glass.Panel(card, behindMenu, dp(20), 0xA61C1C22, Math.max(1, dp(1.1f))).lens(dp(8)));
        TextView h = text(13, Color.WHITE, "sans-serif-medium");
        h.setText(heading);
        h.setPadding(dp(5), 0, dp(5), dp(6));
        card.addView(h);
        return card;
    }

    private View menuButton(String label, Runnable action) {
        TextView b = text(12, Color.WHITE, "sans-serif");
        b.setText(label);
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(rounded(Color.TRANSPARENT, dp(9)));
        b.setOnFocusChangeListener((v, has) -> {
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setBackground(rounded(has ? Color.WHITE : Color.TRANSPARENT, dp(9)));
        });
        b.setOnClickListener(v -> {
            closeMenu(true);
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(2);
        b.setLayoutParams(p);
        return b;
    }

    private void openMenu(LinearLayout card, View focusFirst) {
        openMenu(card, focusFirst, dp(270), Gravity.CENTER);
    }

    private void openMenu(LinearLayout card, View focusFirst, int width, int gravity) {
        View f = getCurrentFocus();
        // a window opened from another keeps the first one's place to go back to
        if (menuLayer.getChildCount() == 0 || f == null || !menuLayer.hasFocus()) focusBeforeMenu = f;
        menuLayer.removeAllViews();
        // the all-apps grid stays known only while its own window is the one showing
        boolean ownGrid = false;
        for (android.view.ViewParent v = allGrid == null ? null : allGrid.getParent(); v != null; v = v.getParent()) if (v == card) ownGrid = true;
        if (!ownGrid) allGrid = null;
        // the screen as it is, blurred behind the card
        menuLayer.setBackgroundColor(Color.TRANSPARENT);
        behindMenu.capture(root.getWidth(), root.getHeight(), root::draw);
        menuLayer.setBackgroundColor(0x73000000);
        menuLayer.setClickable(true);
        boolean side = (gravity & Gravity.START) == Gravity.START;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, side ? -1 : -2, gravity);
        // a side panel: the screen's whole height, inset from its edges
        if (side) lp.setMargins(dp(20), dp(20), 0, dp(20));
        // the control panel opens under its button, at the top right
        if ((gravity & Gravity.END) == Gravity.END) {
            lp.rightMargin = dp(32);
            lp.topMargin = dp(72);
        }
        menuLayer.addView(card, lp);
        page.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.setAlpha(0f);
        if ((gravity & Gravity.END) == Gravity.END) card.setTranslationX(dp(48));
        else if (side) card.setTranslationX(-dp(48));
        else {
            card.setScaleX(0.94f);
            card.setScaleY(0.94f);
        }
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0).setDuration(180)
                .withEndAction(() -> {
                    if (card.getBackground() != null) card.getBackground().invalidateSelf();
                }).start();
        if (focusFirst != null) focusFirst.post(() -> focus(focusFirst));
    }

    /** restoreFocus: put the highlight back where it was before the menu opened */
    private void closeMenu(boolean restoreFocus) {
        pinDigit = null;
        if (menuLayer.getChildCount() == 0) return;
        menuLayer.removeAllViews();
        menuLayer.setBackgroundColor(Color.TRANSPARENT);
        menuLayer.setClickable(false);
        allGrid = null;
        page.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = focusBeforeMenu;
        if (restoreFocus && back != null && back.isAttachedToWindow()) focus(back);
        else if (restoreFocus && appsTile != null) focus(appsTile);
    }

    // ---------------------------------------------------------------- move mode

    private void startMove(App a) {
        moving = a;
        setHint();
        // an app of the all-apps window moves there
        if (apps.indexOf(a) >= Math.min(dockSize(), apps.size())) showAllApps();
        buildTiles(a);
    }

    private void endMove() {
        App a = moving;
        moving = null;
        saveOrder();
        setHint();
        buildTiles(a);
    }

    private void moveBy(int key) {
        int i = apps.indexOf(moving);
        int n = apps.size(), d = Math.min(dockSize(), n);
        int j = i;
        // within the dock, or within the all-apps window
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) j = i - 1;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) j = i + 1;
        else if (i >= d && key == KeyEvent.KEYCODE_DPAD_DOWN) j = i + COLS;
        else if (i >= d && key == KeyEvent.KEYCODE_DPAD_UP) j = i - COLS;
        j = i < d ? Math.max(0, Math.min(d - 1, j)) : Math.max(d, Math.min(n - 1, j));
        if (j == i) return;
        apps.remove(i);
        apps.add(j, moving);
        buildTiles(moving);
    }

    private void setHint() {
        if (moving != null) hint.setText("Déplacement : flèches pour bouger · OK pour valider");
        else hint.setText("OK pour ouvrir · Bas pour revenir au dock");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP && swallowUp.remove(key)) return true;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);

        // Play opens the highlighted tile on the home screen, like OK
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY && menuLayer.getChildCount() == 0) {
            swallowUp.add(key);
            View f = getCurrentFocus();
            if (moving != null) endMove();
            else if (f != null) f.performClick();
            return true;
        }
        boolean menuKey = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PAUSE
                || key == KeyEvent.KEYCODE_MENU;
        if (moving != null) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    swallowUp.add(key);
                    moveBy(key);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    swallowUp.add(key);
                    endMove();
                    return true;
            }
            if (menuKey) {
                swallowUp.add(key);
                endMove();
                return true;
            }
        }
        // the app menu works on the all-apps window's icons too
        if (menuKey && allGrid != null && appOf(getCurrentFocus()) != null) {
            swallowUp.add(key);
            showAppMenu(appOf(getCurrentFocus()));
            return true;
        }
        if (menuLayer.getChildCount() > 0) {
            if (pinDigit != null && key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                swallowUp.add(key);
                pinDigit.accept(key - KeyEvent.KEYCODE_0);
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK || menuKey) {
                swallowUp.add(key);
                closeMenu(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (menuKey) {
            swallowUp.add(key);
            if (moving != null) endMove();
            else {
                App a = appOf(getCurrentFocus());
                if (a != null) showAppMenu(a);
            }
            return true;
        }
        View f = getCurrentFocus();
        boolean inDock = f != null && f.getParent() == dock;
        if (key == KeyEvent.KEYCODE_DPAD_UP && inDock) {
            // up from an app: its content, in place of the clock; else the top bar
            if (appOf(f) != null && appOf(f) == rowFor && watchRow.getChildCount() > 0) {
                openShelf();
                focus(watchRow.getChildAt(0));
            } else focus(controlPill);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && (watchRow.hasFocus() || topBar.hasFocus())) {
            View back = rowApp != null && rowApp.tile.getParent() == dock ? rowApp.tile : dock.getChildAt(0);
            if (back != null) focus(back);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP && watchRow.hasFocus()) {
            focus(controlPill);
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            swallowUp.add(key);
            // Back twice in a row, as Home twice: the recent apps
            long now = android.os.SystemClock.elapsedRealtime();
            boolean twice = now - lastBack < DOUBLE_PRESS;
            lastBack = twice ? 0 : now;
            if (twice) {
                showRecents();
                return true;
            }
            // back from the content to the dock; nothing to go back to from Home
            if (watchRow.hasFocus() || topBar.hasFocus()) {
                View back = rowApp != null && rowApp.tile.getParent() == dock ? rowApp.tile : dock.getChildAt(0);
                if (back != null) focus(back);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- system actions

    /**
     * Sleep and restart need root, which the LG remote bridge (lg-simplink.sh) has: it polls
     * this file and carries out the request.
     */
    private void request(String what) {
        try (FileOutputStream o = openFileOutput("request", MODE_PRIVATE)) {
            o.write(what.getBytes());
        } catch (Exception ignored) {
        }
    }

    private void watchNetwork() {
        cm = getSystemService(ConnectivityManager.class);
        // FLAG_INCLUDE_LOCATION_INFO: without it Android blanks the network name in updates
        netCallback = new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                String label;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) label = "Ethernet";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) label = wifiName(caps);
                else label = "Connecté";
                boolean online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                runOnUiThread(() -> {
                    setWifi(label, false);
                    // posters fail to download until the network is up, e.g. right after boot
                    if (online && !wasOnline) {
                        loadWatchNext();
                        loadWeather();
                        if (prayerTimes == null) loadPrayers();
                    }
                    wasOnline = online;
                });
            }

            @Override
            public void onLost(Network n) {
                runOnUiThread(() -> {
                    setWifi("Wi-Fi", true);
                    wasOnline = false;
                });
            }
        };
        setWifi("Wi-Fi", true);
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @SuppressWarnings("deprecation")
    private String wifiName(NetworkCapabilities caps) {
        if (caps != null && caps.getTransportInfo() instanceof WifiInfo) {
            String ssid = ((WifiInfo) caps.getTransportInfo()).getSSID();
            if (ssid != null && !ssid.contains("unknown ssid") && ssid.replace("\"", "").length() > 0)
                return ssid.replace("\"", "");
        }
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid != null && !ssid.contains("unknown ssid")) {
                ssid = ssid.replace("\"", "");
                if (!ssid.isEmpty()) return ssid;
            }
        } catch (Exception ignored) {
        }
        return "Wi-Fi";
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void focus(View v) {
        if (v.isInTouchMode()) v.requestFocusFromTouch();
        else v.requestFocus();
    }

    private App appOf(View v) {
        return v != null && v.getTag() instanceof App ? (App) v.getTag() : null;
    }

    /** the most vivid colour of a banner or icon, for the glow */
    private int colorOf(String pkg, Drawable d) {
        Integer cached = colors.get(pkg);
        if (cached != null) return cached;
        int out = 0xFF5A5A6E;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 24, 24);
            d.draw(new Canvas(b));
            out = colorOfBitmap(b);
            b.recycle();
        } catch (Exception ignored) {
        }
        colors.put(pkg, out);
        return out;
    }

    static int colorOfBitmap(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 24, 24, true);
        float r = 0, g = 0, bl = 0, wsum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                float w = hsv[1] * hsv[2] * hsv[2] + 0.02f;
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                bl += Color.blue(p) * w;
                wsum += w;
            }
        }
        if (b != src) b.recycle();
        if (wsum == 0) return 0xFF5A5A6E;
        Color.colorToHSV(Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum)), hsv);
        hsv[2] = Math.max(hsv[2], 0.8f);
        return Color.HSVToColor(hsv);
    }

    private static int darken(int c) {
        return darken(c, 0.45f);
    }

    private static int darken(int c, float by) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= by;
        return Color.HSVToColor(hsv);
    }

    private GradientDrawable rounded(int fill, int radius) {
        return rounded(fill, radius, 0);
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(3), stroke);
        return g;
    }

    private TextView text(float sp, int color, String font) {
        TextView t = new TextView(this);
        styleText(t, sp, color, font);
        return t;
    }

    private void styleText(TextView t, float sp, int color, String font) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * SCALE);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(font, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
    }

    /**
     * every size on the home screen, text included, goes through this: one place to make the
     * whole screen roomier or tighter
     */
    static final float SCALE = 0.8f;

    private int dp(float v) {
        return Math.round(v * SCALE * getResources().getDisplayMetrics().density);
    }
}
