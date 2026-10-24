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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
    static final int DOCK = 4;
    static final int COLS = 5;
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
    }

    private final List<App> apps = new ArrayList<>();
    private final List<App> hiddenApps = new ArrayList<>();
    private final Map<String, Integer> colors = new HashMap<>();
    private final Set<Integer> swallowUp = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private SharedPreferences prefs;
    private GlowView glow;
    private ScrollView scroll;
    private LinearLayout dock, watchRow;
    private GridLayout grid;
    private TextView title, hint, gridLabel, watchLabel, wifiPill, greeting, weather, nowPill, sleepPill;
    private TextView prayerLine, updatePill, restartPill, settingsPill;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessions != null) pickSession(sessions.getActiveSessions(null));
        if (cm != null && "Wi-Fi".contentEquals(wifiPill.getText())) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiPill.setText(wifiName(caps));
        }
        updateSleepBadge();
        loadWatchNext();
        updateTimeOfDay(false);
    }

    @Override
    protected void onPause() {
        App a = appOf(getCurrentFocus());
        if (a != null) returnTo = a;
        refocus = true;
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
        // Home pressed while already home: back to the first dock app
        closeMenu(false);
        if (moving != null) endMove();
        scroll.smoothScrollTo(0, 0);
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
                App a = returnTo != null && apps.contains(returnTo) ? returnTo : apps.get(0);
                focus(a.tile);
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        glow = new GlowView(this);
        glow.setColorNow(prefs.getInt("glow", 0xFF3A3A48));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        // the highlighted card's artwork behind the title, faded into the black below and left
        FrameLayout back = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        back.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View fadeDown = new View(this);
        fadeDown.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x33000000, 0x80000000, 0xFF000000}));
        back.addView(fadeDown, new FrameLayout.LayoutParams(-1, -1));
        View fadeLeft = new View(this);
        fadeLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6000000, 0x66000000, 0x00000000}));
        back.addView(fadeLeft, new FrameLayout.LayoutParams(-1, -1));
        back.setAlpha(0f);
        backdrop.setAlpha(1f);
        backdropLayer = back;
        root.addView(back, new FrameLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels * 62 / 100));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(26), dp(56), dp(48));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        content.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));

        title = text(40, Color.WHITE, "sans-serif-medium");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(26);
        content.addView(title, tp);

        hint = text(12, 0x99FFFFFF, "sans-serif");
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(12), dp(12), dp(12), dp(12));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setBackground(rounded(0x17FFFFFF, dp(26)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = dp(16);
        content.addView(dock, dl);

        watchLabel = sectionLabel("Continuer à regarder");
        content.addView(watchLabel, sectionParams());
        watchScroll = new HorizontalScrollView(this);
        watchScroll.setHorizontalScrollBarEnabled(false);
        watchScroll.setClipChildren(false);
        watchScroll.setClipToPadding(false);
        watchRow = new LinearLayout(this);
        watchRow.setOrientation(LinearLayout.HORIZONTAL);
        watchRow.setClipChildren(false);
        watchRow.setClipToPadding(false);
        watchRow.setPadding(dp(4), 0, dp(24), 0);
        watchScroll.addView(watchRow);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(4);
        content.addView(watchScroll, wp);
        watchLabel.setVisibility(View.GONE);
        watchScroll.setVisibility(View.GONE);

        gridLabel = sectionLabel("Toutes les apps");
        content.addView(gridLabel, sectionParams());

        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), 0, 0, 0);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.topMargin = dp(4);
        content.addView(grid, gp);

        night = new View(this);
        night.setBackgroundColor(Color.BLACK);
        night.setAlpha(0f);
        night.setFocusable(false);
        night.setClickable(false);
        root.addView(night, new FrameLayout.LayoutParams(-1, -1));

        menuLayer = new FrameLayout(this);
        root.addView(menuLayer, new FrameLayout.LayoutParams(-1, -1));

        setHint();
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout hello = new LinearLayout(this);
        hello.setOrientation(LinearLayout.VERTICAL);
        greeting = text(22, 0xD9FFFFFF, "sans-serif-light");
        hello.addView(greeting);
        weather = text(12, 0x99FFFFFF, "sans-serif");
        weather.setCompoundDrawablePadding(dp(6));
        weather.setSingleLine(true);
        weather.setEllipsize(TextUtils.TruncateAt.END);
        weather.setVisibility(View.GONE);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.topMargin = dp(4);
        hello.addView(weather, wp);
        prayerLine = text(12, 0x99FFFFFF, "sans-serif");
        prayerLine.setCompoundDrawablePadding(dp(6));
        prayerLine.setSingleLine(true);
        prayerLine.setEllipsize(TextUtils.TruncateAt.END);
        prayerLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = dp(3);
        hello.addView(prayerLine, pp);
        bar.addView(hello, new LinearLayout.LayoutParams(0, -2, 1f));

        nowPill = pill(R.drawable.ic_pause, "", false);
        nowPill.setMaxWidth(dp(220));
        nowPill.setSingleLine(true);
        nowPill.setEllipsize(TextUtils.TruncateAt.END);
        nowPill.setVisibility(View.GONE);
        nowPill.setOnClickListener(v -> togglePlayback());
        bar.addView(nowPill, new LinearLayout.LayoutParams(-2, -2));

        updatePill = pill(R.drawable.ic_update, "Mises à jour", true);
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(v -> showUpdatesMenu());
        bar.addView(updatePill, pillParams());

        wifiPill = pill(R.drawable.ic_wifi, "Wi-Fi", false);
        wifiPill.setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        bar.addView(wifiPill, pillParams());

        sleepPill = pill(R.drawable.ic_sleep, "Veille", true);
        sleepPill.setOnClickListener(v -> showSleepMenu());
        bar.addView(sleepPill, pillParams());

        TextView restart = restartPill = pill(R.drawable.ic_restart, "Redémarrer", true);
        restart.setOnClickListener(v -> showMenu("Redémarrer le Pi ?",
                new String[] {"Redémarrer", "Annuler"},
                new Runnable[] {() -> {
                    Toast.makeText(this, "Redémarrage…", Toast.LENGTH_SHORT).show();
                    request("reboot");
                }, null}));
        bar.addView(restart, pillParams());

        TextView settings = settingsPill = pill(R.drawable.ic_settings, "Paramètres", true);
        settings.setOnClickListener(v -> showSettingsMenu());
        bar.addView(settings, pillParams());

        for (View v : new View[] {nowPill, updatePill, wifiPill, sleepPill, restart, settings}) v.setId(View.generateViewId());
        relinkTopBar();

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        styleText(time, 30, Color.WHITE, "sans-serif-light");
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        styleText(date, 11, 0x99FFFFFF, "sans-serif");
        clock.addView(time);
        clock.addView(date);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.leftMargin = dp(22);
        bar.addView(clock, cp);
        return bar;
    }

    /**
     * left/right stay within the bar, over the buttons that are showing; the compact ones are
     * small enough that the automatic focus search would otherwise jump down to the dock
     */
    private void relinkTopBar() {
        List<View> row = new ArrayList<>();
        for (View v : new View[] {nowPill, updatePill, wifiPill, sleepPill, restartPill, settingsPill}) {
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
        TextView t = text(13, Color.WHITE, "sans-serif-medium");
        t.setText(compact ? "" : label);
        t.setContentDescription(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        Drawable d = getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, has) -> {
            int fg = has ? Color.BLACK : Color.WHITE;
            t.setTextColor(fg);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(fg));
            t.setBackground(rounded(has ? Color.WHITE : 0x24FFFFFF, dp(20)));
            if (compact) pillText(t, has, label);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
        });
        t.setBackground(rounded(0x24FFFFFF, dp(20)));
        t.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        if (compact) pillText(t, false, label);
        else {
            t.setPadding(dp(14), dp(7), dp(16), dp(7));
            t.setCompoundDrawablePadding(dp(8));
        }
        return t;
    }

    private void pillText(TextView t, boolean focused, String label) {
        String status = t.getTag() instanceof String ? (String) t.getTag() : "";
        String text = focused ? (status.isEmpty() ? label : label + " · " + status) : status;
        t.setText(text);
        boolean empty = text.isEmpty();
        t.setCompoundDrawablePadding(empty ? 0 : dp(8));
        t.setPadding(dp(empty ? 10 : 14), dp(7), dp(empty ? 10 : 16), dp(7));
    }

    private TextView sectionLabel(String s) {
        TextView t = text(13, 0x80FFFFFF, "sans-serif-medium");
        t.setText(s);
        return t;
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(26);
        p.leftMargin = dp(12);
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
                a.banner = ri.activityInfo.loadBanner(pm);
                if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                a.icon = ri.loadIcon(pm);
                a.color = colorOf(pkg, a.banner != null ? a.banner : a.icon);
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

    private static boolean containsPkg(List<App> list, String pkg) {
        for (App a : list) if (a.pkg.equals(pkg)) return true;
        return false;
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (App a : apps) sb.append(sb.length() == 0 ? "" : ",").append(a.pkg);
        prefs.edit().putString("order", sb.toString()).apply();
    }

    private void buildTiles(App focus) {
        View current = getCurrentFocus();
        App keep = focus != null ? focus : appOf(current);
        dock.removeAllViews();
        grid.removeAllViews();
        for (int i = 0; i < apps.size(); i++) {
            App a = apps.get(i);
            boolean inDock = i < DOCK;
            a.tile = tile(a, inDock);
            if (inDock) dock.addView(a.tile);
            else grid.addView(a.tile);
        }
        if (!hiddenApps.isEmpty()) grid.addView(hiddenTile());
        gridLabel.setVisibility(grid.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        dock.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);

        App target = keep != null && apps.contains(keep) ? keep : (apps.isEmpty() ? null : apps.get(0));
        if (target != null && (current == null || keep != null)) target.tile.post(() -> focus(target.tile));
    }

    private FrameLayout tileFrame(int w, int h) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        t.setClipToOutline(true);
        return t;
    }

    private View tile(App a, boolean inDock) {
        int w = inDock ? dp(168) : dp(148);
        int h = w * 9 / 16;
        FrameLayout t = tileFrame(w, h);
        t.setTag(a);

        if (a.banner != null) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            t.setBackground(rounded(darken(a.color), dp(12)));
            ImageView img = new ImageView(this);
            img.setImageDrawable(a.icon);
            int s = h * 42 / 100;
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL);
            ip.topMargin = h * 16 / 100;
            t.addView(img, ip);
            t.addView(tileName(a.label, inDock ? 12 : 11), tileNameParams(h));
        }
        t.setLayoutParams(tileParams(inDock, w, h));

        t.setOnFocusChangeListener((v, has) -> {
            float s = has ? (moving == a ? 1.16f : 1.10f) : 1f;
            raise(v, has, s, moving == a ? 0xFF7FD3FF : Color.WHITE);
            if (has) {
                title.setText(a.label);
                setHint();
                glow.setColor(a.color);
                followApp(a);
                hideBackdrop();
            }
        });
        t.setOnClickListener(v -> {
            if (moving != null) { endMove(); return; }
            launch(a);
        });
        t.setOnLongClickListener(v -> { showAppMenu(a); return true; });
        return t;
    }

    private View hiddenTile() {
        int w = dp(148), h = w * 9 / 16;
        FrameLayout t = tileFrame(w, h);
        t.setBackground(rounded(0x1FFFFFFF, dp(12)));
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_hidden);
        img.setImageTintList(ColorStateList.valueOf(0xB3FFFFFF));
        int s = h * 34 / 100;
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL);
        ip.topMargin = h * 20 / 100;
        t.addView(img, ip);
        t.addView(tileName("Apps masquées (" + hiddenApps.size() + ")", 11), tileNameParams(h));
        t.setLayoutParams(tileParams(false, w, h));
        t.setOnFocusChangeListener((v, has) -> {
            raise(v, has, 1.10f, Color.WHITE);
            if (has) {
                title.setText("Apps masquées");
                hideBackdrop();
                hint.setText("OK pour en afficher une à nouveau");
                glow.setColor(0xFF5A5A6E);
            }
        });
        t.setOnClickListener(v -> {
            String[] labels = new String[hiddenApps.size() + 1];
            Runnable[] actions = new Runnable[labels.length];
            for (int i = 0; i < hiddenApps.size(); i++) {
                App a = hiddenApps.get(i);
                labels[i] = "Afficher « " + a.label + " »";
                actions[i] = () -> unhide(a);
            }
            labels[labels.length - 1] = "Annuler";
            showMenu("Apps masquées", labels, actions);
        });
        return t;
    }

    private TextView tileName(String s, float sp) {
        TextView name = text(sp, 0xE6FFFFFF, "sans-serif-medium");
        name.setText(s);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        return name;
    }

    private FrameLayout.LayoutParams tileNameParams(int h) {
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(dp(8), 0, dp(8), h * 12 / 100);
        return np;
    }

    private ViewGroup.LayoutParams tileParams(boolean inDock, int w, int h) {
        ViewGroup.MarginLayoutParams lp = inDock
                ? new LinearLayout.LayoutParams(w, h)
                : new GridLayout.LayoutParams(new ViewGroup.MarginLayoutParams(w, h));
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        return lp;
    }

    private void raise(View v, boolean has, float scale, int border) {
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).translationZ(has ? dp(12) : 0)
                .setDuration(has ? 260 : 160)
                .setInterpolator(has ? new OvershootInterpolator(2.2f) : new DecelerateInterpolator()).start();
        v.setForeground(has ? rounded(Color.TRANSPARENT, dp(12), border) : null);
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
            if (r == null || r.items.isEmpty()) {
                // apps that publish nothing show everyone's "continue watching" instead
                r = new WatchNext.Row();
                r.label = "Continuer à regarder";
                r.items = WatchNext.load(this, new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(","))));
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
        if (key.toString().equals(rowKey)) return; // unchanged: keep the posters already shown
        rowKey = key.toString();
        watchLabel.setText(row.label);
        boolean hadFocus = watchRow.hasFocus();
        watchRow.removeAllViews();
        int w = dp(200), h = w * 9 / 16;
        for (WatchNext.Item it : items) {
            View card = watchCard(it, w, h);
            watchRow.addView(card);
        }
        int vis = items.isEmpty() ? View.GONE : View.VISIBLE;
        watchLabel.setVisibility(vis);
        watchScroll.setVisibility(vis);
        if (hadFocus) {
            if (watchRow.getChildCount() > 0) focus(watchRow.getChildAt(0));
            else if (!apps.isEmpty()) focus(apps.get(0).tile);
        }
    }

    private View watchCard(WatchNext.Item it, int w, int h) {
        FrameLayout t = tileFrame(w, h);
        t.setBackground(rounded(0xFF1E1E26, dp(12)));
        it.color = 0xFF5A5A6E;

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        t.addView(img, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xE6000000, 0x00000000}));
        t.addView(shade, new FrameLayout.LayoutParams(-1, h * 60 / 100, Gravity.BOTTOM));

        TextView name = text(12, Color.WHITE, "sans-serif-medium");
        name.setText(it.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(dp(10), 0, dp(10), dp(12));
        t.addView(name, np);

        if (it.duration > 0 && it.position > 0) {
            FrameLayout track = new FrameLayout(this);
            track.setBackgroundColor(0x40FFFFFF);
            View bar = new View(this);
            bar.setBackgroundColor(Color.WHITE);
            float done = Math.min(1f, it.position / (float) it.duration);
            track.addView(bar, new FrameLayout.LayoutParams((int) ((w - dp(20)) * done), -1));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(w - dp(20), dp(3), Gravity.BOTTOM);
            tp.setMargins(dp(10), 0, dp(10), dp(6));
            t.addView(track, tp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        t.setLayoutParams(lp);

        String app = appLabel(it.pkg);
        String left = it.duration > it.position && it.position > 0
                ? " · reste " + Math.max(1, (it.duration - it.position) / 60000) + " min" : "";
        t.setOnFocusChangeListener((v, has) -> {
            raise(v, has, 1.08f, Color.WHITE);
            if (has) showBackdrop(it);
            if (has) {
                title.setText(it.title);
                hint.setText((it.subtitle != null ? it.subtitle + " · " : "") + app + left);
                glow.setColor(it.color);
            }
        });
        t.setOnClickListener(v -> {
            try {
                startActivity(WatchNext.intentOf(it));
            } catch (Exception e) {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
                if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });

        int px = w;
        io.execute(() -> {
            Bitmap b = WatchNext.poster(this, it.poster, px);
            if (b == null) return;
            int c = colorOfBitmap(b);
            ui.post(() -> {
                img.setImageBitmap(b);
                it.color = c;
                if (t.hasFocus()) glow.setColor(c);
            });
        });
        return t;
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
                backdropLayer.animate().alpha(0.9f).setDuration(450).start();
            });
        });
    }

    private void hideBackdrop() {
        backdropFor = null;
        backdropLayer.animate().alpha(0f).setDuration(300).start();
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
    }

    private void showNowPlaying() {
        MediaController c = playing;
        PlaybackState st = c == null ? null : c.getPlaybackState();
        MediaMetadata md = c == null ? null : c.getMetadata();
        int state = st == null ? PlaybackState.STATE_NONE : st.getState();
        boolean show = md != null && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING);
        if (!show) {
            if (nowPill.hasFocus()) focus(wifiPill);
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
                d.setBounds(0, 0, dp(14), dp(14));
                d.setTint(0x99FFFFFF);
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
        sleepPill.setTag(sleepAt() > 0 ? sleepLeft() : "");
        pillText(sleepPill, sleepPill.hasFocus(), "Veille");
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
        d.setBounds(0, 0, dp(14), dp(14));
        d.setTint(0x99FFFFFF);
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
        card.setBackground(rounded(0xF21C1C22, dp(22)));
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
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(96); // below the top bar, above the title
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

    private void showSettingsMenu() {
        boolean sounds = Settings.System.getInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1;
        boolean reminder = prefs.getBoolean("prayerReminder", true);
        showMenu("Paramètres",
                new String[] {"Paramètres Android", "Infos Pi", "Rechercher des mises à jour",
                        "Rappel des prières : " + (reminder ? "activé" : "désactivé"),
                        "Sons de navigation : " + (sounds ? "activés" : "désactivés"), "Fermer"},
                new Runnable[] {() -> openSettings(Settings.ACTION_SETTINGS), this::showPiInfo, () -> checkUpdates(true), () -> {
                    prefs.edit().putBoolean("prayerReminder", !reminder).apply();
                    Toast.makeText(this, reminder ? "Rappel des prières désactivé" : "Rappel des prières activé", Toast.LENGTH_SHORT).show();
                }, () -> {
                    try {
                        Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, sounds ? 0 : 1);
                        Toast.makeText(this, sounds ? "Sons désactivés" : "Sons activés", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Impossible de changer les sons", Toast.LENGTH_SHORT).show();
                    }
                }, null});
    }

    private void showPiInfo() {
        LinearLayout card = menuCard("Infos Pi");
        TextView body = text(15, Color.WHITE, "sans-serif");
        body.setLineSpacing(dp(6), 1f);
        body.setPadding(dp(6), dp(8), dp(6), dp(10));
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
        showMenu(a.label,
                new String[] {"Déplacer", "Masquer", "Renommer", "Infos de l'app", "Annuler"},
                new Runnable[] {() -> startMove(a), () -> hide(a), () -> showRename(a), () -> appInfo(a), null});
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
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        card.setBackground(rounded(0xF21C1C22, dp(24)));
        TextView h = text(18, Color.WHITE, "sans-serif-medium");
        h.setText(heading);
        h.setPadding(dp(6), 0, dp(6), dp(6));
        card.addView(h);
        return card;
    }

    private View menuButton(String label, Runnable action) {
        TextView b = text(15, Color.WHITE, "sans-serif");
        b.setText(label);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(rounded(Color.TRANSPARENT, dp(12)));
        b.setOnFocusChangeListener((v, has) -> {
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
            b.setBackground(rounded(has ? Color.WHITE : Color.TRANSPARENT, dp(12)));
        });
        b.setOnClickListener(v -> {
            closeMenu(true);
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(4);
        b.setLayoutParams(p);
        return b;
    }

    private void openMenu(LinearLayout card, View focusFirst) {
        focusBeforeMenu = getCurrentFocus();
        menuLayer.removeAllViews();
        menuLayer.setBackgroundColor(0x99000000);
        menuLayer.setClickable(true);
        menuLayer.addView(card, new FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER));
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        if (focusFirst != null) focusFirst.post(() -> focus(focusFirst));
    }

    /** restoreFocus: put the highlight back where it was before the menu opened */
    private void closeMenu(boolean restoreFocus) {
        if (menuLayer.getChildCount() == 0) return;
        menuLayer.removeAllViews();
        menuLayer.setBackgroundColor(Color.TRANSPARENT);
        menuLayer.setClickable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = focusBeforeMenu;
        if (restoreFocus && back != null && back.isAttachedToWindow()) focus(back);
        else if (restoreFocus && !apps.isEmpty()) focus(apps.get(0).tile);
    }

    // ---------------------------------------------------------------- move mode

    private void startMove(App a) {
        moving = a;
        setHint();
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
        int n = apps.size();
        int j = i;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) j = i - 1;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) j = i + 1;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) j = i < DOCK ? DOCK + i : i + COLS;
        else if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (i < DOCK) j = i;
            else if (i - DOCK < COLS) j = Math.min(i - DOCK, DOCK - 1);
            else j = i - COLS;
        }
        j = Math.max(0, Math.min(n - 1, j));
        if (j == i) return;
        apps.remove(i);
        apps.add(j, moving);
        buildTiles(moving);
    }

    private void setHint() {
        if (moving != null) hint.setText("Déplacement : flèches pour bouger · OK pour valider");
        else hint.setText("OK pour ouvrir · Pause pour le menu de l'app");
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
        if (menuLayer.getChildCount() > 0) {
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
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            swallowUp.add(key);
            return true; // nothing to go back to from Home
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
                    wifiPill.setText(label);
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
                    wifiPill.setText("Hors ligne");
                    wasOnline = false;
                });
            }
        };
        wifiPill.setText("Hors ligne");
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
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.45f;
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(font, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
