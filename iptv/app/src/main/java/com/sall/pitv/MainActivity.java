package com.sall.pitv;

import android.app.Activity;
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
    static final int HOME = 0, LIVE = 1, MOVIES = 2, SERIES = 3, FAVS = 4, SEARCH = 5, SETTINGS = 6;
    private static final String[] NAMES = {"Accueil", "TV en direct", "Films", "Séries", "Ma liste", "Recherche", "Paramètres"};
    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_live, R.drawable.ic_movie, R.drawable.ic_series,
            R.drawable.ic_fav, R.drawable.ic_search, R.drawable.ic_settings};

    final Handler ui = new Handler(Looper.getMainLooper());
    Catalog cat;
    Library lib;
    private FrameLayout root, content;
    private ImageView backdrop;
    private View backdropShade;
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
    private DetailsView details;
    private String backdropUrl;
    private final Runnable heroUpdate = this::applyHero;
    private Object heroItem;
    private TextView heroTitle, heroMeta, heroPlot;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cat = Catalog.get(this);
        lib = Library.get(this);
        setContentView(build());
        cat.listen(this);
        cat.load(false);
        Images.get(this).trim();
        show(cat.source.isSet() ? HOME : SETTINGS, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lib = Library.get(this);
        // positions changed while playing
        if (pageRefresh != null && details == null) pageRefresh.run();
        if (details != null) details.refresh();
        if (settingsPage != null) settingsPage.resume();
    }

    @Override
    protected void onPause() {
        if (settingsPage != null) settingsPage.pause();
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
        for (int i = 0; i < NAMES.length; i++) rail.addView(railItem(i), Ui.margins(-1, -2, 0, dp(3), 0, dp(3)));
        // up and down stay in the menu: the page's fields can be nearer than the next entry
        for (TextView t : railItems) t.setId(View.generateViewId());
        for (int i = 0; i < railItems.size(); i++) {
            railItems.get(i).setNextFocusUpId(railItems.get(Math.max(0, i - 1)).getId());
            railItems.get(i).setNextFocusDownId(railItems.get(Math.min(railItems.size() - 1, i + 1)).getId());
        }
        root.addView(rail, new FrameLayout.LayoutParams(dp(64), -1));
        expandRail(false);

        statusLine = Ui.line(this, 11, Ui.DIM, false);
        statusLine.setBackground(Ui.rounded(0xCC1F1F1F, dp(12)));
        statusLine.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusLine.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sp.setMargins(0, 0, dp(20), dp(16));
        root.addView(statusLine, sp);
        return root;
    }

    private TextView railItem(int i) {
        TextView t = Ui.line(this, 14, Ui.DIM, true);
        t.setText(NAMES[i]);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        Drawable d = getDrawable(ICONS[i]).mutate();
        d.setBounds(0, 0, dp(22), dp(22));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(dp(16));
        t.setOnFocusChangeListener((v, has) -> {
            // coming into the menu from a page: start on that page's entry
            if (has && !railOpen && i != page && page >= 0) {
                railOpen = true;
                railItems.get(page).requestFocus();
                return;
            }
            styleRail();
            ui.post(() -> {
                railOpen = rail.hasFocus();
                expandRail(railOpen);
            });
        });
        t.setOnClickListener(v -> {
            show(i, false);
            focusPage();
        });
        t.setOnKeyListener((v, key, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (page != i) show(i, false);
                focusPage();
                return true;
            }
            return false;
        });
        railItems.add(t);
        return t;
    }

    private void styleRail() {
        for (int i = 0; i < railItems.size(); i++) {
            TextView t = railItems.get(i);
            boolean f = t.hasFocus(), cur = i == page;
            int c = f ? Color.BLACK : cur ? Color.WHITE : 0x99FFFFFF;
            t.setTextColor(c);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(f ? Color.BLACK : cur ? Ui.RED : 0x99FFFFFF));
            t.setBackground(f ? Ui.rounded(Color.WHITE, dp(8)) : null);
        }
    }

    /** the menu shows its labels over a dark shade while it has the highlight */
    private void expandRail(boolean open) {
        ViewGroup.LayoutParams p = rail.getLayoutParams();
        int w = dp(open ? 210 : 64);
        if (p.width != w) {
            p.width = w;
            rail.setLayoutParams(p);
        }
        rail.setBackground(open ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF2000000, 0xE6000000, 0x00000000}) : null);
        content.animate().alpha(open ? 0.45f : 1f).setDuration(150).start();
        styleRail();
    }

    void focusRail() {
        railItems.get(Math.max(0, page)).requestFocus();
    }

    private void focusPage() {
        if (page == LIVE && livePage != null) livePage.focus();
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
        page = which;
        content.removeAllViews();
        heroTitle = null;
        pageRefresh = null;
        livePage = null;
        if (settingsPage != null) settingsPage.pause();
        settingsPage = null;
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
                settingsPage = new SettingsPage(this);
                pageView = settingsPage.view;
                pageRefresh = settingsPage::refresh;
                settingsPage.resume();
                break;
            default:
                pageView = browsePage(which);
        }
        content.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        styleRail();
        if (focus) ui.post(this::focusPage);
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
            if (i.kind == Model.MOVIE && i.plot == null && Source.XTREAM.equals(cat.source.type)) {
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
            Images.get(this).load(backdrop, url, backdrop.getLayoutParams().width / 2, backdrop.getLayoutParams().height / 2, false);
            backdrop.animate().alpha(1f).setDuration(300).start();
        }).start();
    }

    // ---------------------------------------------------------------- browse pages

    private View browsePage(int which) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.addView(hero(), new LinearLayout.LayoutParams(-1, dp(232)));
        Rows rows = new Rows(this, rowCallbacks());
        v.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView empty = Ui.text(this, 15, Ui.DIM, false);
        empty.setPadding(dp(48), 0, dp(48), 0);
        v.addView(empty);
        pageRefresh = () -> {
            List<Rows.Row> r = rowsFor(which);
            rows.setRows(r);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText(!cat.loaded ? "Chargement…" : cat.error != null ? cat.error
                    : which == FAVS ? "Appuyez sur Pause sur une affiche ou une chaîne pour l'ajouter à votre liste."
                    : "Rien à afficher pour l'instant.");
            if (rows.isEmpty()) {
                heroTitle.setText(NAMES[which]);
                heroMeta.setText("");
                heroPlot.setText("");
            }
        };
        pageRefresh.run();
        return v;
    }

    private List<Rows.Row> rowsFor(int which) {
        List<Rows.Row> r = new ArrayList<>();
        switch (which) {
            case HOME: {
                List<Library.Progress> cw = lib.continueWatching();
                r.add(new Rows.Row("Continuer à regarder", Rows.WIDE, cw));
                r.add(new Rows.Row("Chaînes regardées récemment", Rows.CHANNEL, lib.recentLive()));
                r.add(new Rows.Row("Ma liste", Rows.POSTER, merge(lib.favs(Model.MOVIE), lib.favs(Model.SERIES))));
                r.add(new Rows.Row("Films ajoutés récemment", Rows.POSTER, Catalog.recent(cat.allMovies, 30)));
                r.add(new Rows.Row("Séries ajoutées récemment", Rows.POSTER, Catalog.recent(cat.allSeries, 30)));
                r.add(new Rows.Row("Chaînes favorites", Rows.CHANNEL, lib.favs(Model.LIVE)));
                // a taste of each kind, so Home is never empty
                int n = 0;
                for (Model.Category c : cat.movies) {
                    if (n++ >= 4) break;
                    r.add(new Rows.Row(c.name, Rows.POSTER, c.items.subList(0, Math.min(40, c.items.size()))));
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
                for (Model.Category c : cat.movies) r.add(new Rows.Row(c.name + "  ·  " + c.items.size(), Rows.POSTER, c.items));
                break;
            case SERIES:
                r.add(new Rows.Row("Ajoutées récemment", Rows.POSTER, Catalog.recent(cat.allSeries, 40)));
                for (Model.Category c : cat.series) r.add(new Rows.Row(c.name + "  ·  " + c.items.size(), Rows.POSTER, c.items));
                break;
            case FAVS:
                r.add(new Rows.Row("Chaînes", Rows.CHANNEL, lib.favs(Model.LIVE)));
                r.add(new Rows.Row("Films", Rows.POSTER, lib.favs(Model.MOVIE)));
                r.add(new Rows.Row("Séries", Rows.POSTER, lib.favs(Model.SERIES)));
                break;
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
                open(item);
            }

            @Override
            public void onMenu(Object item) {
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

    // ---------------------------------------------------------------- search

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
        Runnable run = () -> {
            String s = q.getText().toString();
            List<Rows.Row> r = new ArrayList<>();
            r.add(new Rows.Row("Chaînes", Rows.CHANNEL, cat.search(s, Model.LIVE, 60)));
            r.add(new Rows.Row("Films", Rows.POSTER, cat.search(s, Model.MOVIE, 60)));
            r.add(new Rows.Row("Séries", Rows.POSTER, cat.search(s, Model.SERIES, 60)));
            rows.setRows(r);
            hint.setText(s.trim().isEmpty() ? "Tapez quelques lettres, puis descendez vers les résultats."
                    : rows.isEmpty() ? "Aucun résultat pour « " + s.trim() + " »" : "");
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
                ui.removeCallbacks(run);
                ui.postDelayed(run, 300);
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
    }

    @Override
    public void onStatus(String s) {
        statusLine.setText(s);
        statusLine.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        if (settingsPage != null) settingsPage.refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (details != null) {
                if (!details.back()) closeDetails(true);
                return true;
            }
            if (livePage != null && livePage.back()) return true;
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

    int dp(float v) {
        return Ui.dp(this, v);
    }
}
