package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The menu on the left, the pages, the hero at the top and the details of a film or series. */
public class MainActivity extends Activity implements Catalog.Listener {
    /** a film, episode or channel to open, by key: the launcher's entries (TvHome) */
    public static final String EXTRA_OPEN = "pitv.open";
    /** words typed in Pi Home's search: Pi TV opens on its Search page with them */
    static final String EXTRA_SEARCH = "pitv.search";
    static final int HOME = 0, LIVE = 1, MOVIES = 2, SERIES = 3, ANIME = 4, COLLECTIONS = 5, FAVS = 6, SEARCH = 7, SETTINGS = 8, ADULT = 9;
    private static final String[] NAMES = {"Accueil", "TV en direct", "Films", "Séries", "Animes", "Collections", "Ma liste", "Recherche", "Paramètres", "Adulte"};
    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_live, R.drawable.ic_movie, R.drawable.ic_series, R.drawable.ic_anime,
            R.drawable.ic_collections, R.drawable.ic_fav, R.drawable.ic_search, R.drawable.ic_settings, R.drawable.ic_lock};
    /** Paramètres' tabs: the IPTV provider, the Stremio add-ons, or the plugins */
    private static final int TAB_IPTV = 0, TAB_ADDONS = 1, TAB_PLUGINS = 2;
    private AddonsPage addonsPage;
    private int settingsTab = TAB_IPTV;
    private FrameLayout settingsBody;
    private final List<TextView> settingsTabs = new ArrayList<>();

    public final Handler ui = new Handler(Looper.getMainLooper());
    Catalog cat;
    Library lib;
    private FrameLayout root, content;
    private ImageView backdrop;
    private View backdropShade;
    /** the menu's pages: Films, Séries and Animes are tabs of Collections */
    private static final int[] NAV = {HOME, LIVE, COLLECTIONS, FAVS, SEARCH, ADULT, SETTINGS};
    private LinearLayout rail;
    private final List<TextView> railItems = new ArrayList<>();
    private TextView statusLine;
    private int page = -1;
    private boolean railOpen;
    /** set by Settings: go to Home once the new source has loaded */
    boolean homeWhenLoaded;
    private View pageView;
    /** the page's refresh when the catalogue changes */
    private Runnable pageRefresh;
    private LivePage livePage;
    private SettingsPage settingsPage;
    private PluginsPage pluginsPage;
    private DetailsView details;
    private String backdropUrl;
    private final Runnable heroUpdate = this::applyHero;
    private Object heroItem;
    private TextView heroTitle, heroMeta, heroPlot;
    /** a collection's folder, open over the Collections page: its view and the tile it came from */
    private View folderView, folderReturn;
    private TextView[] pageHero;
    /** a catalogue's full page, over the page or the folder it was opened from */
    private View gridView, gridUnder, gridReturn;
    private TextView[] gridHero;
    private String gridBackdrop;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cat = Catalog.get(this);
        lib = Library.get(this);
        setContentView(build());
        cat.listen(this);
        cat.load(false);
        Images.get(this).trim();
        // the add-ons' films and series are enough to start with
        show(cat.source.isSet() || !Addons.get(this).urls().isEmpty() ? HOME : SETTINGS, true);
        openFromLauncher(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openFromLauncher(intent);
    }

    /** an entry chosen on the launcher's row: its film or episode resumes, its channel plays */
    private void openFromLauncher(Intent intent) {
        String query = intent == null ? null : intent.getStringExtra(EXTRA_SEARCH);
        if (query != null) {
            intent.removeExtra(EXTRA_SEARCH);
            show(SEARCH, true);
            View field = content.findViewWithTag("first");
            if (field instanceof EditText) {
                ((EditText) field).setText(query);
                ((EditText) field).setSelection(query.length());
            }
            return;
        }
        String key = intent == null ? null : intent.getStringExtra(EXTRA_OPEN);
        if (key == null) return;
        intent.removeExtra(EXTRA_OPEN);
        lib = Library.get(this);
        Library.Progress p = lib.progress(key);
        if (p != null) {
            open(p);
            return;
        }
        for (Model.Item ch : lib.recentLive()) {
            if (ch.key().equals(key)) {
                playChannel(ch, null);
                return;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lib = Library.get(this);
        updateBadges();
        // positions changed while playing
        if (pageRefresh != null && details == null) pageRefresh.run();
        if (details != null) details.refresh();
        if (settingsPage != null) settingsPage.resume();
        if (pluginsPage != null) pluginsPage.resume();
        if (livePage != null) livePage.resumePreview();
        TvHome.sync(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Adult.started();
    }

    @Override
    protected void onStop() {
        Adult.stopped();
        super.onStop();
        // shut again while away: the next time Pi TV comes up, the Adulte page isn't left open
        ui.postDelayed(() -> {
            if (!Adult.unlocked && page == ADULT) show(HOME, false);
            updateBadges();
        }, 3_500);
    }

    @Override
    protected void onPause() {
        if (settingsPage != null) settingsPage.pause();
        if (pluginsPage != null) pluginsPage.pause();
        if (livePage != null) livePage.stopPreview();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cat.unlisten(this);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- shell

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int bw = getResources().getDisplayMetrics().widthPixels * 72 / 100, bh = getResources().getDisplayMetrics().heightPixels * 70 / 100;
        root.addView(backdrop, new FrameLayout.LayoutParams(bw, bh, Gravity.TOP | Gravity.END));
        FrameLayout shade = new FrameLayout(this);
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF000000, 0xCC000000, 0x00000000}));
        shade.addView(left, new FrameLayout.LayoutParams(bw * 55 / 100, -1));
        View down = new View(this);
        down.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x99000000, 0xFF000000}));
        shade.addView(down, new FrameLayout.LayoutParams(-1, bh / 2, Gravity.BOTTOM));
        backdropShade = shade;
        root.addView(shade, new FrameLayout.LayoutParams(bw, bh, Gravity.TOP | Gravity.END));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        cp.leftMargin = dp(64);
        root.addView(content, cp);

        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_VERTICAL);
        rail.setPadding(dp(12), dp(16), dp(12), dp(16));
        for (int i = 0; i < NAV.length; i++) rail.addView(railItem(i), Ui.margins(-1, -2, 0, dp(3), 0, dp(3)));
        // up and down stay in the menu: the page's fields can be nearer than the next entry
        for (TextView t : railItems) t.setId(View.generateViewId());
        for (int i = 0; i < railItems.size(); i++) {
            railItems.get(i).setNextFocusUpId(railItems.get(Math.max(0, i - 1)).getId());
            railItems.get(i).setNextFocusDownId(railItems.get(Math.min(railItems.size() - 1, i + 1)).getId());
        }
        root.addView(rail, new FrameLayout.LayoutParams(dp(64), -1));
        expandRail(false);

        // at the menu's foot: private mode on, adult add-ons open
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.VERTICAL);
        badges.setGravity(Gravity.CENTER_HORIZONTAL);
        incognitoBadge = badge(R.drawable.ic_incognito, 0xCCFFFFFF);
        adultBadge = badge(R.drawable.ic_lock_open, Ui.RED);
        badges.addView(incognitoBadge, Ui.margins(dp(22), dp(22), 0, dp(6), 0, 0));
        badges.addView(adultBadge, Ui.margins(dp(22), dp(22), 0, dp(6), 0, 0));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(64), -2, Gravity.BOTTOM | Gravity.START);
        bp.bottomMargin = dp(18);
        root.addView(badges, bp);
        updateBadges();

        statusLine = Ui.line(this, 11, Ui.DIM, false);
        statusLine.setBackground(Ui.rounded(0xCC1F1F1F, dp(12)));
        statusLine.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusLine.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sp.setMargins(0, 0, dp(20), dp(16));
        root.addView(statusLine, sp);
        return root;
    }

    private ImageView incognitoBadge, adultBadge;

    private ImageView badge(int icon, int tint) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setFocusable(false);
        return v;
    }

    /** shows, at the menu's foot, whether private mode is on and the adult add-ons are open */
    void updateBadges() {
        if (incognitoBadge == null) return;
        incognitoBadge.setVisibility(Library.incognito ? View.VISIBLE : View.GONE);
        adultBadge.setVisibility(Adult.unlocked ? View.VISIBLE : View.GONE);
    }

    private TextView railItem(int i) {
        int which = NAV[i];
        TextView t = Ui.line(this, 14, Ui.DIM, true);
        t.setText(NAMES[which]);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        Drawable d = getDrawable(ICONS[which]).mutate();
        d.setBounds(0, 0, dp(22), dp(22));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(dp(16));
        t.setOnFocusChangeListener((v, has) -> {
            // coming into the menu from a page: start on that page's entry
            int cur = navIndex(page);
            if (has && !railOpen && i != cur && cur >= 0) {
                railOpen = true;
                railItems.get(cur).requestFocus();
                return;
            }
            styleRail();
            ui.post(() -> {
                railOpen = rail.hasFocus();
                expandRail(railOpen);
            });
        });
        t.setOnClickListener(v -> {
            show(which, false);
            focusPage();
        });
        t.setOnKeyListener((v, key, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (page != which) show(which, false);
                focusPage();
                return true;
            }
            return false;
        });
        railItems.add(t);
        return t;
    }

    private static int navIndex(int which) {
        for (int i = 0; i < NAV.length; i++) if (NAV[i] == which) return i;
        return -1;
    }

    /** the highlighted entry white, the open page's in glass with a red icon, open or not */
    private void styleRail() {
        for (int i = 0; i < railItems.size(); i++) {
            TextView t = railItems.get(i);
            boolean f = t.hasFocus(), cur = NAV[i] == page;
            t.setTextColor(f ? Color.BLACK : cur ? Color.WHITE : 0x99FFFFFF);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(f ? Color.BLACK : cur ? Ui.RED : 0x99FFFFFF));
            t.setBackground(f ? Ui.rounded(Color.WHITE, dp(12)) : cur ? Ui.glass(this, dp(12), 1) : null);
        }
    }

    /** the menu shows its labels over a dark shade while it has the highlight */
    private void expandRail(boolean open) {
        ViewGroup.LayoutParams p = rail.getLayoutParams();
        int w = dp(open ? 220 : 64);
        if (p.width != w) {
            p.width = w;
            rail.setLayoutParams(p);
        }
        rail.setBackground(open ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF2000000, 0xE6000000, 0x00000000}) : null);
        content.animate().alpha(open ? 0.45f : 1f).setDuration(150).start();
        styleRail();
    }

    void focusRail() {
        int i = navIndex(page);
        railItems.get(Math.max(0, i < 0 ? navIndex(HOME) : i)).requestFocus();
    }

    private void focusPage() {
        if (gridView != null) {
            Grid g = findGrid(gridView);
            if (g != null && g.count() > 0) g.focusFirst();
            else gridView.requestFocus();
        } else if (folderView != null) {
            Rows r = findRows(folderView);
            if (r != null && !r.isEmpty()) r.focusFirst();
            else folderView.requestFocus();
        } else if (page == COLLECTIONS && collChips != null && collChips.get(collTab).isAttachedToWindow()) {
            collChips.get(collTab).requestFocus();
        } else if (page == LIVE && livePage != null) livePage.focus();
        else if (page == SETTINGS && pluginsPage != null) pluginsPage.focus();
        else if (page == SETTINGS && addonsPage != null) addonsPage.focus();
        else if (page == SETTINGS && settingsPage != null) settingsPage.focus();
        else if (pageView != null) {
            View f = pageView.findViewWithTag("first");
            if (f != null) f.requestFocus();
            else if (pageView instanceof ViewGroup && pageView.findFocus() == null) {
                Rows r = findRows(pageView);
                if (r != null && !r.isEmpty()) r.focusFirst();
                else pageView.requestFocus();
            }
        }
    }

    private static Grid findGrid(View v) {
        if (v instanceof Grid) return (Grid) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Grid r = findGrid(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Rows findRows(View v) {
        if (v instanceof Rows) return (Rows) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Rows r = findRows(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    void show(int which, boolean focus) {
        if (which == page && pageView != null) return;
        // the adult add-ons: only after their code
        if (which == ADULT && !Adult.unlocked) {
            Adult.open(this, () -> {
                updateBadges();
                show(ADULT, true);
            });
            return;
        }
        page = which;
        content.removeAllViews();
        heroTitle = null;
        pageRefresh = null;
        if (livePage != null) livePage.stopPreview();
        livePage = null;
        if (settingsPage != null) settingsPage.pause();
        settingsPage = null;
        if (pluginsPage != null) pluginsPage.pause();
        pluginsPage = null;
        addonsPage = null;
        folderView = null;
        gridView = null;
        setBackdrop(null);
        switch (which) {
            case LIVE:
                livePage = new LivePage(this);
                pageView = livePage.view;
                pageRefresh = livePage::refresh;
                break;
            case SEARCH:
                pageView = searchPage();
                break;
            case SETTINGS:
                pageView = settingsPage();
                break;
            default:
                pageView = browsePage(which);
        }
        content.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        styleRail();
        if (focus) ui.post(this::focusPage);
    }

    // ---------------------------------------------------------------- settings

    /** Paramètres: a bar of tabs over the chosen one's page */
    private View settingsPage() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(40), dp(22), dp(40), 0);
        settingsTabs.clear();
        String[] names = {"IPTV", "Add-ons", "Plugins"};
        for (int i = 0; i < names.length; i++) {
            int tab = i;
            TextView t = Ui.line(this, 15, Color.WHITE, true);
            t.setText(names[i]);
            t.setPadding(dp(18), dp(8), dp(18), dp(8));
            t.setFocusable(true);
            t.setOnFocusChangeListener((x, has) -> {
                styleSettingsTabs();
                // a tab opens as soon as it's highlighted, like the seasons of a series
                if (has && tab != settingsTab) showSettingsTab(tab);
            });
            t.setOnClickListener(x -> focusPage());
            // down goes into the tab's page, not to the menu on the left; up has nowhere to go
            t.setOnKeyListener((x, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                focusPage();
                return true;
            });
            bar.addView(t, Ui.margins(-2, -2, i == 0 ? 0 : dp(8), 0, 0, 0));
            settingsTabs.add(t);
        }
        v.addView(bar);
        settingsBody = new FrameLayout(this);
        v.addView(settingsBody, new LinearLayout.LayoutParams(-1, 0, 1f));
        showSettingsTab(settingsTab);
        return v;
    }

    private void showSettingsTab(int tab) {
        settingsTab = tab;
        if (settingsPage != null) settingsPage.pause();
        if (pluginsPage != null) pluginsPage.pause();
        settingsPage = null;
        pluginsPage = null;
        addonsPage = null;
        pageRefresh = null;
        settingsBody.removeAllViews();
        View body;
        if (tab == TAB_PLUGINS) {
            pluginsPage = new PluginsPage(this);
            body = pluginsPage.view;
        } else if (tab == TAB_ADDONS) {
            addonsPage = new AddonsPage(this);
            body = addonsPage.view;
        } else {
            settingsPage = new SettingsPage(this);
            body = settingsPage.view;
            pageRefresh = settingsPage::refresh;
            settingsPage.resume();
        }
        settingsBody.addView(body, new FrameLayout.LayoutParams(-1, -1));
        styleSettingsTabs();
    }

    private void styleSettingsTabs() {
        for (int i = 0; i < settingsTabs.size(); i++) {
            TextView t = settingsTabs.get(i);
            boolean f = t.hasFocus(), on = i == settingsTab;
            t.setBackground(f ? Ui.rounded(Color.WHITE, dp(20)) : on ? Ui.glass(this, dp(20), 1) : null);
            t.setTextColor(f ? Color.BLACK : on ? Color.WHITE : Ui.DIM);
        }
    }

    // ---------------------------------------------------------------- hero and backdrop

    private LinearLayout hero() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(48), dp(36), dp(48), dp(10));
        heroTitle = Ui.text(this, 30, Color.WHITE, true);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroTitle.setShadowLayer(dp(6), 0, 0, 0x99000000);
        h.addView(heroTitle, Ui.margins(dp(470), -2, 0, 0, 0, 0));
        heroMeta = Ui.line(this, 13, 0xFF46D369, true);
        h.addView(heroMeta, Ui.margins(dp(470), -2, 0, dp(10), 0, 0));
        heroPlot = Ui.text(this, 13, Ui.DIM, false);
        heroPlot.setMaxLines(3);
        heroPlot.setLineSpacing(dp(3), 1f);
        heroPlot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(heroPlot, Ui.margins(dp(420), -2, 0, dp(10), 0, 0));
        return h;
    }

    /** the highlighted card fills the hero after a short pause, so moving fast stays smooth */
    void hero(Object o) {
        heroItem = o;
        ui.removeCallbacks(heroUpdate);
        ui.postDelayed(heroUpdate, 160);
    }

    private void applyHero() {
        if (heroTitle == null || heroItem == null) return;
        if (heroItem instanceof Folders.Folder) {
            Folders.Folder d = (Folders.Folder) heroItem;
            heroTitle.setText(d.title);
            heroMeta.setText(d.shelf);
            heroPlot.setText("");
            heroPlot.setVisibility(View.GONE);
            setBackdrop(d.backdrop);
            return;
        }
        Model.Item i = heroItem instanceof Library.Progress ? ((Library.Progress) heroItem).item : (Model.Item) heroItem;
        Model.Item full = cat.byKey.get(i.key());
        if (full != null) i = full;
        if (i.kind == Model.EPISODE) {
            heroTitle.setText(i.seriesName != null ? i.seriesName : i.name);
            heroMeta.setText("Saison " + i.season + " · Épisode " + i.episode);
            heroPlot.setText(i.plot == null ? i.name : i.plot);
        } else if (i.kind == Model.LIVE) {
            heroTitle.setText(i.name);
            Model.Prog now = cat.epg.now(i);
            heroMeta.setText(now == null ? "En direct" : "En direct · " + Ui.clock(now.start) + " – " + Ui.clock(now.stop));
            heroPlot.setText(now == null ? "" : now.title + (now.desc != null && !now.desc.isEmpty() ? " — " + now.desc : ""));
        } else {
            heroTitle.setText(i.name);
            heroMeta.setText(i.meta());
            heroPlot.setText(i.plot == null ? "" : i.plot);
            Model.Item it = i;
            if (i.kind == Model.MOVIE && i.plot == null && !i.fromAddon() && Source.XTREAM.equals(cat.source.type)) {
                i.plot = "";
                cat.async(() -> {
                    Xtream.details(cat.source, it);
                    return true;
                }, ok -> {
                    if (heroItem != null && heroTitle != null) applyHero();
                });
            }
        }
        heroPlot.setVisibility(heroPlot.getText().length() == 0 ? View.GONE : View.VISIBLE);
        setBackdrop(i.kind == Model.LIVE ? null : i.backdrop != null && !i.backdrop.isEmpty() ? i.backdrop : i.image);
    }

    void setBackdrop(String url) {
        if (url != null && url.equals(backdropUrl)) return;
        backdropUrl = url;
        if (url == null) {
            backdrop.animate().alpha(0f).setDuration(200).start();
            return;
        }
        backdrop.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            if (!url.equals(backdropUrl)) return;
            int w = backdrop.getLayoutParams().width / 2, h = backdrop.getLayoutParams().height / 2;
            Images.get(this).load(backdrop, url, w, h, false);
            backdrop.animate().alpha(1f).setDuration(300).start();
        }).start();
    }

    // ---------------------------------------------------------------- browse pages

    private View browsePage(int which) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = null;
        if (which == COLLECTIONS) {
            tabs = new LinearLayout(this);
            tabs.setPadding(dp(40), dp(22), dp(40), 0);
            v.addView(tabs);
        }
        v.addView(hero(), new LinearLayout.LayoutParams(-1, dp(which == COLLECTIONS ? 190 : 232)));
        Rows rows = new Rows(this, rowCallbacks());
        v.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView empty = Ui.text(this, 15, Ui.DIM, false);
        empty.setPadding(dp(48), 0, dp(48), 0);
        v.addView(empty);
        if (tabs != null) collectionTabs(tabs, rows);
        pageRefresh = () -> {
            List<Rows.Row> r = rowsFor(which);
            rows.setRows(r);
            // the top of the page shows the first title until one is highlighted
            if (heroReset || heroItem == null || heroTitle.getText().length() == 0) {
                heroReset = false;
                for (Rows.Row x : r) {
                    if (!x.items.isEmpty()) {
                        hero(x.items.get(0));
                        break;
                    }
                }
            }
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText(!cat.loaded ? "Chargement…" : cat.error != null ? cat.error
                    : which == FAVS ? "Appuyez sur Pause sur une affiche ou une chaîne pour l'ajouter à votre liste."
                    : which == ANIME || which == COLLECTIONS && collTab == 3 ? "Aucun add-on d'animes : ajoutez-en un dans Paramètres."
                    : which == COLLECTIONS && collTab == 0 ? "Aucune collection : importez un export de collections Nuvio depuis le téléphone (Paramètres)."
                    : which == ADULT ? "Aucun add-on adulte : marquez-en un « 18+ » dans Paramètres › Add-ons, ou tapez « erome » ou « tastyblacks » pour ajouter les intégrés."
                    : "Rien à afficher pour l'instant.");
            if (rows.isEmpty()) {
                heroTitle.setText(which == COLLECTIONS ? COLL_TABS[collTab] : NAMES[which]);
                heroMeta.setText("");
                heroPlot.setText("");
            }
        };
        pageRefresh.run();
        return v;
    }

    /** an add-on's rows, without the adult ones (on the Adulte page only) */
    private static List<Addons.Row> notAdult(List<Addons.Row> rows) {
        List<Addons.Row> out = new ArrayList<>();
        for (Addons.Row r : rows) if (!r.adult) out.add(r);
        return out;
    }

    private static List<Model.Item> notAdultItems(java.util.Collection<Model.Item> items) {
        List<Model.Item> out = new ArrayList<>();
        for (Model.Item i : items) if (!i.adult) out.add(i);
        return out;
    }

    private List<Rows.Row> rowsFor(int which) {
        List<Rows.Row> r = new ArrayList<>();
        switch (which) {
            case HOME: {
                List<Library.Progress> cw = lib.continueWatching();
                cw.removeIf(p -> p.item.adult);
                r.add(new Rows.Row("Continuer à regarder", Rows.WIDE, cw));
                // the collections, near the top as in Nuvio
                r.addAll(shelfRows());
                r.add(new Rows.Row("Chaînes regardées récemment", Rows.CHANNEL, lib.recentLive()));
                r.add(new Rows.Row("Ma liste", Rows.POSTER, notAdultItems(merge(lib.favs(Model.MOVIE), lib.favs(Model.SERIES)))));
                r.add(new Rows.Row("Films ajoutés récemment", Rows.POSTER, Catalog.recent(cat.allMovies, 30)));
                r.add(new Rows.Row("Séries ajoutées récemment", Rows.POSTER, Catalog.recent(cat.allSeries, 30)));
                // the first catalogue of each add-on kind
                Addons ad = Addons.get(this);
                for (List<Addons.Row> l : List.of(notAdult(ad.movies), notAdult(ad.series), notAdult(ad.anime))) {
                    if (!l.isEmpty()) r.add(new Rows.Row(l.get(0).name, Rows.POSTER, l.get(0).items).all(l.get(0)));
                }
                r.add(new Rows.Row("Chaînes favorites", Rows.CHANNEL, lib.favs(Model.LIVE)));
                // a taste of each kind, so Home is never empty
                int n = 0;
                for (Model.Category c : cat.movies) {
                    if (n++ >= 4) break;
                    r.add(new Rows.Row(c.name, Rows.POSTER, c.items.subList(0, Math.min(40, c.items.size()))).all(c));
                }
                if (cat.allMovies.isEmpty() && cat.allSeries.isEmpty()) {
                    for (Model.Category c : cat.live) {
                        if (n++ >= 6) break;
                        r.add(new Rows.Row(c.name, Rows.CHANNEL, c.items.subList(0, Math.min(40, c.items.size()))));
                    }
                }
                break;
            }
            case MOVIES:
                r.add(new Rows.Row("Ajoutés récemment", Rows.POSTER, Catalog.recent(cat.allMovies, 40)));
                for (Addons.Row a : notAdult(Addons.get(this).movies)) r.add(new Rows.Row(a.name, Rows.POSTER, a.items).all(a));
                for (Model.Category c : cat.movies) r.add(new Rows.Row(c.name + "  ·  " + c.items.size(), Rows.POSTER, c.items).all(c));
                break;
            case SERIES:
                r.add(new Rows.Row("Ajoutées récemment", Rows.POSTER, Catalog.recent(cat.allSeries, 40)));
                for (Addons.Row a : notAdult(Addons.get(this).series)) r.add(new Rows.Row(a.name, Rows.POSTER, a.items).all(a));
                for (Model.Category c : cat.series) r.add(new Rows.Row(c.name + "  ·  " + c.items.size(), Rows.POSTER, c.items).all(c));
                break;
            case ANIME:
                for (Addons.Row a : notAdult(Addons.get(this).anime)) r.add(new Rows.Row(a.name, Rows.POSTER, a.items).all(a));
                break;
            case COLLECTIONS:
                if (collTab == 0) r.addAll(shelfRows());
                else r.addAll(rowsFor(collTab == 1 ? MOVIES : collTab == 2 ? SERIES : ANIME));
                break;
            case FAVS:
                r.add(new Rows.Row("Chaînes", Rows.CHANNEL, lib.favs(Model.LIVE)));
                r.add(new Rows.Row("Films", Rows.POSTER, notAdultItems(lib.favs(Model.MOVIE))));
                r.add(new Rows.Row("Séries", Rows.POSTER, notAdultItems(lib.favs(Model.SERIES))));
                break;
            case ADULT: {
                // their saved titles, then every catalogue of the adult add-ons
                List<Model.Item> mine = new ArrayList<>();
                for (Model.Item i : merge(lib.favs(Model.MOVIE), lib.favs(Model.SERIES))) if (i.adult) mine.add(i);
                r.add(new Rows.Row("Ma liste", Rows.POSTER, mine));
                Addons ad = Addons.get(this);
                for (List<Addons.Row> l : List.of(ad.movies, ad.series, ad.anime)) {
                    for (Addons.Row a : l) if (a.adult) r.add(new Rows.Row(a.name, Rows.POSTER, a.items).all(a));
                }
                break;
            }
        }
        return r;
    }

    /** Collections' sub-tabs: the shelves, then the add-ons' films, series and anime */
    private static final String[] COLL_TABS = {"Collections", "Films", "Séries", "Animes"};
    private int collTab;
    /** the sub-tabs on screen: coming from the menu lands on the open one */
    private List<TextView> collChips;
    /** the hero goes back to the page's first title (a sub-tab was changed) */
    private boolean heroReset;

    /** glass chips over the Collections page; a chip opens as soon as it's highlighted */
    private void collectionTabs(LinearLayout box, Rows rows) {
        List<TextView> chips = new ArrayList<>();
        collChips = chips;
        boolean[] inside = {false};
        for (int i = 0; i < COLL_TABS.length; i++) {
            int tab = i;
            TextView t = Ui.line(this, 14, Color.WHITE, true);
            t.setText(COLL_TABS[i]);
            t.setPadding(dp(18), dp(7), dp(18), dp(7));
            t.setFocusable(true);
            t.setClickable(true);
            Runnable style = () -> {
                for (int k = 0; k < chips.size(); k++) {
                    TextView c = chips.get(k);
                    boolean f = c.hasFocus(), on = k == collTab;
                    c.setTextColor(f ? Color.BLACK : on ? Color.WHITE : 0xB3FFFFFF);
                    c.setBackground(f ? Ui.rounded(Color.WHITE, dp(18)) : on ? Ui.glass(this, dp(18), 1) : null);
                }
            };
            t.setOnFocusChangeListener((x, has) -> {
                // coming up from the rows: onto the open sub-tab, not the nearest
                if (has && !inside[0] && tab != collTab) {
                    inside[0] = true;
                    chips.get(collTab).requestFocus();
                    return;
                }
                if (has && tab != collTab) {
                    collTab = tab;
                    heroReset = true;
                    if (pageRefresh != null) pageRefresh.run();
                }
                style.run();
                ui.post(() -> inside[0] = box.hasFocus());
            });
            t.setOnClickListener(x -> {
                if (!rows.isEmpty()) rows.focusFirst();
            });
            t.setOnKeyListener((x, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!rows.isEmpty()) rows.focusFirst();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && tab == 0) {
                    focusRail();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_RIGHT && tab == COLL_TABS.length - 1;
            });
            chips.add(t);
            box.addView(t, Ui.margins(-2, -2, 0, 0, dp(6), 0));
        }
        style(chips);
    }

    private void style(List<TextView> chips) {
        for (int k = 0; k < chips.size(); k++) {
            TextView c = chips.get(k);
            boolean on = k == collTab;
            c.setTextColor(on ? Color.WHITE : 0xB3FFFFFF);
            c.setBackground(on ? Ui.glass(this, dp(18), 1) : null);
        }
    }

    /** a row of tiles per collection */
    private List<Rows.Row> shelfRows() {
        List<Rows.Row> r = new ArrayList<>();
        for (Folders.Collection c : Folders.load(this)) {
            r.add(new Rows.Row(c.title, c.folders.get(0).landscape ? Rows.FOLDER_WIDE : Rows.FOLDER_TALL, c.folders));
        }
        return r;
    }

    private static List<Model.Item> merge(List<Model.Item> a, List<Model.Item> b) {
        List<Model.Item> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    Rows.Callbacks rowCallbacks() {
        return new Rows.Callbacks() {
            @Override
            public void onFocus(Object item) {
                hero(item);
            }

            @Override
            public void onClick(Object item) {
                if (item instanceof Folders.Folder) openFolder((Folders.Folder) item);
                else open(item);
            }

            @Override
            public void onAll(Rows.Row row) {
                openGrid(row);
            }

            @Override
            public void onMenu(Object item) {
                if (item instanceof Folders.Folder) return;
                Model.Item i = item instanceof Library.Progress ? ((Library.Progress) item).item : (Model.Item) item;
                if (i.kind == Model.EPISODE) return;
                boolean fav = lib.toggleFav(i);
                Toast.makeText(MainActivity.this, fav ? "Ajouté à Ma liste" : "Retiré de Ma liste", Toast.LENGTH_SHORT).show();
            }
        };
    }

    /** a card was chosen: channels and episodes play, films and series open their page */
    void open(Object o) {
        if (o instanceof Library.Progress) {
            Library.Progress p = (Library.Progress) o;
            Model.Item i = p.item;
            if (i.fromAddon()) {
                // the stream address saved may have expired: the page asks the add-ons again
                openDetails(i.kind == Model.EPISODE ? addonSeries(i) : i);
                return;
            }
            if (i.kind == Model.EPISODE) {
                Model.Item s = cat.byKey.get(Model.SERIES + ":" + i.seriesId);
                if (s != null) {
                    openDetails(s);
                    return;
                }
            }
            PlayerActivity.play(this, List.of(i), 0, p.pos);
            return;
        }
        Model.Item i = (Model.Item) o;
        if (i.kind == Model.LIVE) playChannel(i, null);
        else if (i.kind == Model.EPISODE) PlayerActivity.play(this, List.of(i), 0, -1);
        else {
            Model.Item full = cat.byKey.get(i.key());
            openDetails(full != null ? full : i);
        }
    }

    /** an add-on episode's series, as the catalogues showed it if they did */
    private Model.Item addonSeries(Model.Item ep) {
        Model.Item s = Addons.get(this).byKey.get(Model.SERIES + ":" + ep.seriesId);
        if (s != null) return s;
        Model.Item f = null;
        for (Model.Item x : lib.favs(Model.SERIES)) if (x.id.equals(ep.seriesId)) f = x;
        if (f != null) return f;
        s = new Model.Item();
        s.kind = Model.SERIES;
        s.id = s.sid = ep.seriesId;
        s.stype = ep.stype;
        s.name = ep.seriesName != null ? ep.seriesName : ep.name;
        s.image = ep.image;
        return s;
    }

    /** plays a channel, zapping through its category (or the given list) */
    void playChannel(Model.Item ch, List<Model.Item> list) {
        if (list == null) {
            list = new ArrayList<>();
            Model.Item full = cat.byKey.get(ch.key());
            if (full != null) ch = full;
            for (Model.Category c : cat.live) {
                if (c.id.equals(ch.category)) {
                    list = c.items;
                    break;
                }
            }
            if (list.isEmpty()) list = List.of(ch);
        }
        int idx = 0;
        for (int k = 0; k < list.size(); k++) if (list.get(k).key().equals(ch.key())) idx = k;
        // the preview lets go of the video decoder before full screen takes it
        if (livePage != null) livePage.stopPreview();
        PlayerActivity.play(this, list, idx, -1);
    }

    void openDetails(Model.Item i) {
        closeDetails(false);
        details = new DetailsView(this, i);
        // the page and the menu underneath keep out of the highlight's way
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rail.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(details.view, new FrameLayout.LayoutParams(-1, -1));
        details.focus();
    }

    void closeDetails(boolean refocus) {
        if (details == null) return;
        View back = details.returnFocus;
        root.removeView(details.view);
        details = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rail.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (pageRefresh != null && page != SEARCH) pageRefresh.run();
        if (refocus) {
            if (back != null && back.isAttachedToWindow()) back.requestFocus();
            else focusPage();
        }
    }

    // ---------------------------------------------------------------- collections

    /** a folder's page: its backdrop and name over a row per catalogue, filled as they arrive */
    private void openFolder(Folders.Folder d) {
        folderReturn = getCurrentFocus();
        pageHero = new TextView[] {heroTitle, heroMeta, heroPlot};
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.addView(hero(), new LinearLayout.LayoutParams(-1, dp(232)));
        heroTitle.setText(d.title);
        heroMeta.setText(d.shelf);
        heroPlot.setText("Chargement…");
        Rows rows = new Rows(this, rowCallbacks());
        v.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1f));
        pageView.setVisibility(View.INVISIBLE);
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        folderView = v;
        setBackdrop(d.backdrop);
        Addons ad = Addons.get(this);
        // in the folder's order, whichever answers first
        Rows.Row[] slots = new Rows.Row[d.sources.size()];
        int[] pending = {slots.length};
        for (int k = 0; k < slots.length; k++) {
            String[] src = d.sources.get(k);
            int at = k;
            cat.async(() -> ad.catalog(src[0], src[1], src[2], src[3]), row -> {
                if (folderView != v) return;
                pending[0]--;
                // Netflix's films and Netflix's series: the same name twice, told apart by kind
                String kind = "movie".equals(src[1]) ? "Films" : "anime".equals(src[1]) ? "Animes" : "Séries";
                if (row != null) slots[at] = new Rows.Row(row.name + "  ·  " + kind, Rows.POSTER, row.items).all(row);
                boolean first = rows.isEmpty();
                List<Rows.Row> shown = new ArrayList<>();
                for (Rows.Row x : slots) if (x != null) shown.add(x);
                rows.setRows(shown);
                boolean answered = false;
                for (Rows.Row x : slots) answered |= x != null;
                heroPlot.setText(!rows.isEmpty() ? "" : pending[0] > 0 ? "Chargement…"
                        : answered ? "Ce dossier est vide : ses catalogues ne renvoient rien pour l'instant (certains demandent un compte Trakt relié à l'add-on)."
                        : "Rien à afficher : l'add-on « " + src[0] + " » n'est pas dans vos add-ons.");
                heroPlot.setVisibility(heroPlot.getText().length() == 0 ? View.GONE : View.VISIBLE);
                // onto the first poster once the rows are laid out (not the empty list around them)
                if (!rows.isEmpty() && (first || !(getCurrentFocus() != null && inside(getCurrentFocus(), rows)))) {
                    rows.postDelayed(() -> {
                        View f = getCurrentFocus();
                        if (folderView == v && (f == null || f == rows || !inside(f, rows))) rows.focusFirst();
                    }, 80);
                }
            });
        }
        v.requestFocus();
    }

    private void closeFolder() {
        content.removeView(folderView);
        folderView = null;
        heroTitle = pageHero[0];
        heroMeta = pageHero[1];
        heroPlot = pageHero[2];
        pageView.setVisibility(View.VISIBLE);
        if (folderReturn != null && folderReturn.isAttachedToWindow()) folderReturn.requestFocus();
        else focusPage();
    }

    /** a row's full page: its titles in a grid under the highlighted one's hero, the next ones loading on the way down */
    private void openGrid(Rows.Row r) {
        List<Model.Item> first = new ArrayList<>();
        Grid.Pager pager = null;
        if (r.all instanceof Addons.Row) {
            Addons.Row ar = (Addons.Row) r.all;
            first.addAll(ar.items);
            Addons ad = Addons.get(this);
            if (ar.pages != null) pager = skip -> ad.more(ar, skip);
        } else if (r.all instanceof Model.Category) {
            first.addAll(((Model.Category) r.all).items);
        }
        gridReturn = getCurrentFocus();
        gridHero = new TextView[] {heroTitle, heroMeta, heroPlot};
        gridBackdrop = backdropUrl;
        gridUnder = folderView != null ? folderView : pageView;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.addView(hero(), new LinearLayout.LayoutParams(-1, dp(200)));
        // one line of title, two of story: the grid starts just below
        heroTitle.setMaxLines(1);
        heroPlot.setMaxLines(2);
        heroTitle.setText(r.title);
        heroMeta.setText("");
        heroPlot.setText("");
        TextView label = Ui.line(this, 15, Color.WHITE, true);
        label.setPadding(dp(48), 0, dp(48), dp(4));
        v.addView(label);
        // the row's title without the count some carry ("Action  ·  412")
        String name = r.all instanceof Model.Category ? ((Model.Category) r.all).name : r.title;
        Grid g = new Grid(this, first, pager, rowCallbacks());
        g.changed = () -> label.setText(name + "  ·  " + g.count() + (g.loading() ? "+" : "") + " titres");
        g.changed.run();
        v.addView(g, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (gridUnder != null) gridUnder.setVisibility(View.INVISIBLE);
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        gridView = v;
        g.post(g::focusFirst);
    }

    private void closeGrid() {
        content.removeView(gridView);
        gridView = null;
        heroTitle = gridHero[0];
        heroMeta = gridHero[1];
        heroPlot = gridHero[2];
        setBackdrop(gridBackdrop);
        if (gridUnder != null) gridUnder.setVisibility(View.VISIBLE);
        if (gridReturn != null && gridReturn.isAttachedToWindow()) gridReturn.requestFocus();
        else focusPage();
    }

    // ---------------------------------------------------------------- search

    /** the add-ons are asked once the typing has paused this long (ms), for this many letters */
    private static final int ADDON_SEARCH_PAUSE = 800, ADDON_SEARCH_MIN = 3;

    private View searchPage() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(28), 0, 0);
        EditText q = new EditText(this);
        q.setTag("first");
        q.setHint("Rechercher une chaîne, un film, une série…");
        q.setHintTextColor(Ui.FAINT);
        q.setTextColor(Color.WHITE);
        q.setTextSize(18);
        q.setSingleLine(true);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setBackground(Ui.rounded(0xFF262626, dp(8)));
        q.setPadding(dp(16), dp(10), dp(16), dp(10));
        Drawable d = getDrawable(R.drawable.ic_search).mutate();
        d.setBounds(0, 0, dp(20), dp(20));
        d.setTint(Ui.FAINT);
        q.setCompoundDrawablesRelative(d, null, null, null);
        q.setCompoundDrawablePadding(dp(12));
        q.setOnFocusChangeListener((x, has) -> q.setBackground(has ? Ui.rounded(0xFF333333, dp(8)) : Ui.rounded(0xFF262626, dp(8))));
        v.addView(q, Ui.margins(dp(520), -2, dp(48), 0, 0, 0));
        TextView hint = Ui.text(this, 12, Ui.FAINT, false);
        hint.setPadding(dp(48), dp(10), dp(48), dp(6));
        v.addView(hint);
        Rows rows = new Rows(this, rowCallbacks());
        v.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1f));
        // the provider's list is searched here, at once; the add-ons are asked over the network,
        // so only once the typing has paused and the words are long enough to mean something
        Runnable local = () -> {
            String s = q.getText().toString();
            List<Rows.Row> r = new ArrayList<>();
            r.add(new Rows.Row("Chaînes", Rows.CHANNEL, cat.search(s, Model.LIVE, 60)));
            r.add(new Rows.Row("Films", Rows.POSTER, cat.search(s, Model.MOVIE, 60)));
            r.add(new Rows.Row("Séries", Rows.POSTER, cat.search(s, Model.SERIES, 60)));
            rows.setRows(r);
            String t = s.trim();
            hint.setText(t.isEmpty() ? "Tapez quelques lettres, puis descendez vers les résultats."
                    : !rows.isEmpty() ? ""
                    : t.length() < ADDON_SEARCH_MIN ? "Encore une lettre ou deux pour chercher dans les add-ons…"
                    : "Recherche dans les add-ons…");
        };
        Runnable remote = () -> {
            String s = q.getText().toString();
            if (s.trim().length() < ADDON_SEARCH_MIN) return;
            // the add-ons search on their side, all at once: each one's row comes as it answers
            Addons.get(this).search(s.trim(), new Addons.Searching() {
                @Override
                public void found(Addons.Addon a, int kind, List<Model.Item> items) {
                    ui.post(() -> {
                        if (!q.getText().toString().equals(s)) return;
                        rows.addRow(new Rows.Row(a.name + (kind == Model.MOVIE ? " · Films" : " · Séries"), Rows.POSTER, items));
                        hint.setText("");
                    });
                }

                @Override
                public void done() {
                    ui.post(() -> {
                        if (q.getText().toString().equals(s) && rows.isEmpty())
                            hint.setText("Aucun résultat pour « " + s.trim() + " »");
                    });
                }
            });
        };
        Runnable run = () -> {
            ui.removeCallbacks(local);
            ui.removeCallbacks(remote);
            local.run();
            remote.run();
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(local);
                ui.removeCallbacks(remote);
                ui.postDelayed(local, 150);
                ui.postDelayed(remote, ADDON_SEARCH_PAUSE);
            }
        });
        q.setOnKeyListener((x, key, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN && !rows.isEmpty()) {
                rows.focusFirst();
                return true;
            }
            return false;
        });
        q.setOnEditorActionListener((x, action, e) -> {
            run.run();
            if (!rows.isEmpty()) rows.focusFirst();
            return true;
        });
        pageRefresh = run;
        run.run();
        return v;
    }

    // ---------------------------------------------------------------- catalogue and keys

    @Override
    public void onCatalog() {
        if (homeWhenLoaded && cat.loaded) {
            homeWhenLoaded = false;
            if (cat.error == null && !cat.byKey.isEmpty()) {
                show(HOME, true);
                return;
            }
        }
        if (pageRefresh != null && details == null) {
            boolean hadFocus = pageView != null && pageView.hasFocus();
            pageRefresh.run();
            if (hadFocus || getCurrentFocus() == null) ui.post(this::focusPage);
        }
        if (page == SETTINGS && settingsPage != null) settingsPage.refresh();
        // the add-ons' names and descriptions, once their manifests are read
        if (page == SETTINGS && addonsPage != null) addonsPage.refresh();
    }

    @Override
    public void onStatus(String s) {
        statusLine.setText(s);
        statusLine.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        if (settingsPage != null) settingsPage.refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && page == SETTINGS && settingsBody != null) {
            // up stays in the tab's page, and from its top goes to its own tab: not to whichever
            // tab is nearest, nor to the menu on the left
            View f = getCurrentFocus();
            if (f != null && inside(f, settingsBody)) {
                View next = android.view.FocusFinder.getInstance().findNextFocus((ViewGroup) settingsBody, f, View.FOCUS_UP);
                (next != null ? next : settingsTabs.get(settingsTab)).requestFocus();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && page == SETTINGS && settingsBody != null) {
            // right moves along the line, and at its end stays put: not to a switch further down,
            // nor to the tabs
            View f = getCurrentFocus();
            if (f != null && inside(f, settingsBody) && !(f instanceof EditText)) {
                View next = android.view.FocusFinder.getInstance().findNextFocus((ViewGroup) settingsBody, f, View.FOCUS_RIGHT);
                // unless the page sends it somewhere on purpose (a panel beside it, higher up)
                if (next != null && (sameLine(f, next) || f.getNextFocusRightId() == next.getId())) next.requestFocus();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && page == SETTINGS && settingsBody != null) {
            // down stays in the tab's page: Android's own search would jump to the menu on the left
            View f = getCurrentFocus();
            if (f != null && inside(f, settingsBody)) {
                View next = android.view.FocusFinder.getInstance().findNextFocus((ViewGroup) settingsBody, f, View.FOCUS_DOWN);
                if (next != null) next.requestFocus();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (details != null) {
                if (!details.back()) closeDetails(true);
                return true;
            }
            if (livePage != null && livePage.back()) return true;
            if (gridView != null) {
                closeGrid();
                return true;
            }
            if (folderView != null) {
                closeFolder();
                return true;
            }
            if (!rail.hasFocus()) {
                focusRail();
                return true;
            }
            if (page != HOME) {
                show(HOME, false);
                focusRail();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** whether two views overlap vertically on screen */
    private static boolean sameLine(View a, View b) {
        int[] p = new int[2], q = new int[2];
        a.getLocationOnScreen(p);
        b.getLocationOnScreen(q);
        return p[1] < q[1] + b.getHeight() && q[1] < p[1] + a.getHeight();
    }

    private static boolean inside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    public int dp(float v) {
        return Ui.dp(this, v);
    }
}
