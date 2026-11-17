package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** A film's or series' page: backdrop, story, Play / Resume, My list, and the episodes. */
public final class DetailsView {
    final FrameLayout view;
    /** the card that opened it, to highlight again on Back */
    final View returnFocus;
    private final MainActivity a;
    private final Model.Item item;
    private final TextView meta, plot, cast;
    private final ImageView bd;
    private final int bdW, bdH;
    private final LinearLayout buttons;
    private TextView playButton, favButton;
    private LinearLayout seasonBar;
    private Rows episodesRow;
    private Map<Integer, List<Model.Item>> seasons = new LinkedHashMap<>();
    private int season = -1;
    /** the episode last highlighted, to highlight again after playing */
    private int episodeIndex;

    DetailsView(MainActivity a, Model.Item item) {
        this.a = a;
        this.item = item;
        returnFocus = a.getCurrentFocus();
        view = new FrameLayout(a);
        view.setBackgroundColor(Color.BLACK);
        view.setClickable(true);

        bd = new ImageView(a);
        bd.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int w = a.getResources().getDisplayMetrics().widthPixels * 75 / 100, h = a.getResources().getDisplayMetrics().heightPixels * 85 / 100;
        bdW = w;
        bdH = h;
        view.addView(bd, new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.END));
        View l = new View(a);
        l.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF000000, 0xE6000000, 0x00000000}));
        view.addView(l, new FrameLayout.LayoutParams(w * 60 / 100, h, Gravity.TOP | Gravity.END) {{
            rightMargin = w * 40 / 100;
        }});
        View d = new View(a);
        d.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xCC000000, 0xFF000000}));
        view.addView(d, new FrameLayout.LayoutParams(w, h / 2, Gravity.TOP | Gravity.END) {{
            topMargin = h / 2;
        }});

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(64), a.dp(48), a.dp(48), 0);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        TextView title = Ui.text(a, 32, Color.WHITE, true);
        title.setText(item.name);
        title.setMaxLines(2);
        title.setShadowLayer(a.dp(6), 0, 0, 0x99000000);
        col.addView(title, Ui.margins(a.dp(520), -2, 0, 0, 0, 0));
        meta = Ui.line(a, 13, 0xFF46D369, true);
        col.addView(meta, Ui.margins(a.dp(520), -2, 0, a.dp(10), 0, 0));
        plot = Ui.text(a, 13, 0xD9FFFFFF, false);
        plot.setLineSpacing(a.dp(3), 1f);
        plot.setMaxLines(item.kind == Model.SERIES ? 3 : 6);
        plot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(plot, Ui.margins(a.dp(460), -2, 0, a.dp(12), 0, 0));
        cast = Ui.text(a, 11, Ui.FAINT, false);
        cast.setMaxLines(2);
        cast.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(cast, Ui.margins(a.dp(460), -2, 0, a.dp(8), 0, 0));
        buttons = new LinearLayout(a);
        buttons.setClipChildren(false);
        col.addView(buttons, Ui.margins(-2, -2, 0, a.dp(18), 0, 0));
        view.addView(col, new FrameLayout.LayoutParams(-1, -2));

        if (item.kind == Model.SERIES) {
            LinearLayout bottom = new LinearLayout(a);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setClipChildren(false);
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            seasonBar = new LinearLayout(a);
            seasonBar.setPadding(a.dp(64), a.dp(4), a.dp(48), a.dp(4));
            seasonBar.setClipChildren(false);
            hs.addView(seasonBar);
            bottom.addView(hs);
            episodesRow = new Rows(a, new Rows.Callbacks() {
                @Override
                public void onFocus(Object o) {
                    Model.Item e = o instanceof Library.Progress ? ((Library.Progress) o).item : (Model.Item) o;
                    List<Model.Item> l = seasons.get(season);
                    if (l != null) for (int k = 0; k < l.size(); k++) if (l.get(k).id.equals(e.id)) episodeIndex = k;
                    meta.setText("S" + e.season + " · É" + e.episode + " — " + e.name);
                    plot.setText(e.plot != null && !e.plot.isEmpty() ? e.plot : item.plot == null ? "" : item.plot);
                }

                @Override
                public void onClick(Object o) {
                    Model.Item e = o instanceof Library.Progress ? ((Library.Progress) o).item : (Model.Item) o;
                    playEpisode(e, false);
                }
            });
            episodesRow.setPadding(a.dp(16), 0, 0, 0);
            episodesRow.episodeCards = true;
            bottom.addView(episodesRow, new LinearLayout.LayoutParams(-1, a.dp(150)));
            view.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        fill();
        buildButtons();
        load();
        // the links are looked for while the page is read: they're often there when Play is pressed
        view.postDelayed(this::lookAhead, 2_000);
    }

    /** starts the plugins' search for what Play would play, if the page is still open */
    private void lookAhead() {
        if (!view.isAttachedToWindow() || !item.fromAddon()) return;
        Model.Item target = item.kind == Model.MOVIE ? item : nextToPlay();
        if (target == null) {
            // a series whose episodes are still loading
            view.postDelayed(this::lookAhead, 1_000);
            return;
        }
        Plugins plugins = Plugins.get(a);
        for (Plugins.Scraper sc : plugins.scrapers()) {
            if (sc.enabled) {
                plugins.future(target);
                return;
            }
        }
    }

    private String shownBackdrop;

    private void fill() {
        String img = item.backdrop != null && !item.backdrop.isEmpty() ? item.backdrop : item.image;
        if (img != null && !img.equals(shownBackdrop)) {
            shownBackdrop = img;
            Images.get(a).load(bd, img, bdW / 2, bdH / 2, false);
        }
        meta.setText(item.meta());
        plot.setText(item.plot == null ? "" : item.plot);
        cast.setText(item.cast == null || item.cast.isEmpty() ? "" : "Avec " + item.cast);
    }

    private void buildButtons() {
        buttons.removeAllViews();
        Library.Progress p = resumePoint();
        String label;
        if (item.kind == Model.SERIES) {
            label = p == null ? "Lecture" : (p.finished() ? "Suivant : " : "Reprendre ") + "S" + p.item.season + " É" + p.item.episode;
            if (p != null && p.finished()) {
                Model.Item next = nextEpisode(p.item);
                label = next == null ? "Revoir S" + p.item.season + " É" + p.item.episode : "Lecture S" + next.season + " É" + next.episode;
            }
        } else {
            label = p != null && !p.finished() && p.pos > 30_000 ? "Reprendre · " + Ui.duration(p.dur - p.pos) + " restantes" : "Lecture";
        }
        playButton = Ui.button(a, R.drawable.ic_play, label, v -> play(false, false));
        playButton.setTag("first");
        // a long press: the list of links, to choose one
        playButton.setOnLongClickListener(v -> {
            play(false, true);
            return true;
        });
        buttons.addView(playButton);
        if (item.kind == Model.MOVIE && p != null && p.pos > 30_000) {
            buttons.addView(Ui.button(a, R.drawable.ic_replay, "Depuis le début", v -> play(true, false)), Ui.margins(-2, -2, a.dp(12), 0, 0, 0));
        }
        if (item.fromAddon()) {
            buttons.addView(Ui.button(a, R.drawable.ic_list, "Sources", v -> play(false, true)), Ui.margins(-2, -2, a.dp(12), 0, 0, 0));
        }
        boolean fav = a.lib.isFav(item);
        favButton = Ui.button(a, fav ? R.drawable.ic_fav : R.drawable.ic_fav_border, fav ? "Dans Ma liste" : "Ma liste", v -> {
            boolean now = a.lib.toggleFav(item);
            favButton.setText(now ? "Dans Ma liste" : "Ma liste");
            Ui.setIcon(favButton, now ? R.drawable.ic_fav : R.drawable.ic_fav_border);
        });
        buttons.addView(favButton, Ui.margins(-2, -2, a.dp(12), 0, 0, 0));
    }

    private Library.Progress resumePoint() {
        if (item.kind == Model.SERIES) return a.lib.lastEpisode(item.id);
        return a.lib.progress(item);
    }

    private void load() {
        Source s = a.cat.source;
        if (item.fromAddon()) {
            if (item.kind == Model.SERIES) meta.setText("Chargement des épisodes…");
            a.cat.async(() -> Addons.get(a).details(item), m -> {
                if (!view.isAttachedToWindow()) return;
                fill();
                if (item.kind != Model.SERIES) return;
                if (m == null || m.isEmpty()) {
                    // an anime film, or a series the add-ons don't list episodes for: one title
                    item.kind = Model.MOVIE;
                    buildButtons();
                    focus();
                    return;
                }
                showSeasons(m);
            });
            return;
        }
        if (item.kind == Model.MOVIE) {
            if (Source.XTREAM.equals(s.type) && (item.plot == null || item.plot.isEmpty() || item.backdrop == null)) {
                a.cat.async(() -> {
                    Xtream.details(s, item);
                    return true;
                }, ok -> {
                    if (view.isAttachedToWindow()) fill();
                });
            }
            return;
        }
        if (item.episodes != null) {
            Map<Integer, List<Model.Item>> m = new LinkedHashMap<>();
            for (Model.Item e : item.episodes) {
                List<Model.Item> l = m.get(e.season);
                if (l == null) m.put(e.season, l = new ArrayList<>());
                l.add(e);
            }
            showSeasons(m);
            return;
        }
        meta.setText("Chargement des épisodes…");
        a.cat.async(() -> Xtream.episodes(s, item), m -> {
            if (!view.isAttachedToWindow()) return;
            fill();
            if (m == null || m.isEmpty()) {
                meta.setText("Aucun épisode disponible");
                return;
            }
            showSeasons(m);
        });
    }

    private void showSeasons(Map<Integer, List<Model.Item>> m) {
        seasons = m;
        seasonBar.removeAllViews();
        Library.Progress p = a.lib.lastEpisode(item.id);
        int start = p != null && m.containsKey(p.item.season) ? p.item.season : m.keySet().iterator().next();
        for (int s : m.keySet()) {
            TextView chip = Ui.button(a, 0, "Saison " + s, v -> pickSeason(s));
            chip.setOnFocusChangeListener(chipFocus(chip, s));
            seasonBar.addView(chip, Ui.margins(-2, -2, 0, 0, a.dp(10), 0));
        }
        pickSeason(start);
        boolean onButtons = view.findFocus() == null || buttons.hasFocus();
        buildButtons();
        if (onButtons) focus();
    }

    /** a season is chosen as soon as its tab is highlighted */
    private View.OnFocusChangeListener chipFocus(TextView chip, int s) {
        View.OnFocusChangeListener base = chip.getOnFocusChangeListener();
        return (v, has) -> {
            base.onFocusChange(v, has);
            if (has && s != season) pickSeason(s);
        };
    }

    private void pickSeason(int s) {
        season = s;
        for (int i = 0; i < seasonBar.getChildCount(); i++) {
            TextView c = (TextView) seasonBar.getChildAt(i);
            if (!c.hasFocus()) c.setTextColor(c.getText().toString().equals("Saison " + s) ? Color.WHITE : 0x99FFFFFF);
        }
        List<Object> eps = new ArrayList<>();
        for (Model.Item e : seasons.get(s)) {
            Library.Progress p = a.lib.progress(e);
            eps.add(p != null ? p : e);
        }
        List<Rows.Row> r = new ArrayList<>();
        r.add(new Rows.Row(seasons.get(s).size() + " épisodes", Rows.WIDE, eps));
        episodesRow.setRows(r);
    }

    /** Play: a film, or the series' episode it's at; [pick] shows the links to choose one */
    private void play(boolean fromStart, boolean pick) {
        if (quickCancel != null) return;
        if (item.kind == Model.MOVIE) {
            Library.Progress p = a.lib.progress(item);
            long pos = fromStart || p == null || p.finished() ? 0 : p.pos, asked = System.currentTimeMillis();
            if (item.fromAddon()) findStream(item, () -> PlayerActivity.play(a, List.of(item), 0, since(item, pos, asked)), pick);
            else PlayerActivity.play(a, List.of(item), 0, pos);
            return;
        }
        Model.Item ep = nextToPlay();
        if (ep == null) {
            Toast.makeText(a, "Épisodes en cours de chargement…", Toast.LENGTH_SHORT).show();
            return;
        }
        playEpisode(ep, pick);
    }

    /** a series' episode Play goes to: the one left off, the next if it was finished, else the first */
    private Model.Item nextToPlay() {
        if (seasons.isEmpty()) return null;
        Library.Progress p = a.lib.lastEpisode(item.id);
        Model.Item ep;
        if (p == null) ep = seasons.values().iterator().next().get(0);
        else if (p.finished()) {
            ep = nextEpisode(p.item);
            if (ep == null) ep = find(p.item);
        } else ep = find(p.item);
        return ep != null ? ep : seasons.values().iterator().next().get(0);
    }

    /** plays an episode, with the rest of the series queued after it */
    private void playEpisode(Model.Item ep, boolean pick) {
        List<Model.Item> all = new ArrayList<>();
        for (List<Model.Item> l : seasons.values()) all.addAll(l);
        int idx = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(ep.id)) idx = i;
        Library.Progress p = a.lib.progress(all.get(idx));
        long pos = p != null && !p.finished() ? p.pos : 0;
        if (!ep.fromAddon()) {
            PlayerActivity.play(a, all, idx, pos);
            return;
        }
        // the next episodes find their stream when they come, like the one chosen here
        for (Model.Item e : all) e.url = null;
        Model.Item first = all.get(idx);
        int at = idx;
        long asked = System.currentTimeMillis();
        findStream(first, () -> PlayerActivity.play(a, all, at, since(first, pos, asked)), pick);
    }

    /**
     * where a link chosen in the list starts: where the title was left if it played since Play was
     * pressed (another link after Back), else [pos]
     */
    private long since(Model.Item i, long pos, long asked) {
        Library.Progress p = a.lib.progress(i);
        if (p == null || p.time < asked) return pos;
        return p.finished() ? 0 : p.pos;
    }

    private void findStream(Model.Item target, Runnable play, boolean pick) {
        if (pick) chooseStream(target, play);
        else quickStream(target, play);
    }

    /** the quiet search Play runs; Back stops it */
    private Runnable quickCancel;

    /**
     * Play without the list: the source that played this title last time the moment it answers,
     * else on a title never played the add-on or script of the last link played anywhere (its best
     * link for the connection), else after a few seconds (or once every source has answered) the best link the connection
     * carries. The player falls back on the others, including those found after it started.
     */
    private void quickStream(Model.Item target, Runnable play) {
        String last = PlayerActivity.lastSource(a, target);
        String latest = last != null ? null : PlayerActivity.latestSource(a);
        String provider = latest == null ? null : latest.substring(0, Math.max(0, latest.indexOf('|')));
        CharSequence label = playButton.getText();
        playButton.setText("Recherche d'un lien…");
        List<Addons.Stream> found = new ArrayList<>();
        int[] pending = {0};
        boolean[] over = {false};
        long t0 = System.currentTimeMillis();
        Runnable stop = () -> {
            over[0] = true;
            quickCancel = null;
            if (playButton.isAttachedToWindow()) playButton.setText(label);
        };
        quickCancel = stop;
        Runnable decide = () -> {
            if (over[0]) return;
            Addons.Stream pick = null;
            if (last != null) for (Addons.Stream x : found) if (last.equals(x.source())) {
                pick = x;
                break;
            }
            if (pick == null && provider != null && !provider.isEmpty()) {
                List<Addons.Stream> exact = new ArrayList<>(), same = new ArrayList<>();
                for (Addons.Stream x : found) if (provider.equals(x.addon)) (latest.equals(x.source()) ? exact : same).add(x);
                pick = PlayerActivity.bestFor(a, exact.isEmpty() ? same : exact);
            }
            if (pick == null && (System.currentTimeMillis() - t0 >= 5_000 || pending[0] == 0)) pick = PlayerActivity.bestFor(a, PlayerActivity.inPreferredLanguage(a, found));
            if (pick == null) {
                if (pending[0] == 0) {
                    stop.run();
                    Toast.makeText(a, "Aucun lien trouvé pour ce titre", Toast.LENGTH_LONG).show();
                }
                return;
            }
            stop.run();
            target.url = pick.url;
            target.bingeGroup = pick.bingeGroup;
            target.headers = pick.headers;
            target.source = pick.source();
            target.alternatives = found;
            play.run();
        };
        java.util.function.Consumer<List<Addons.Stream>> add = l -> {
            if (l == null || l.isEmpty()) return;
            found.addAll(l);
            found.sort(java.util.Comparator.comparingInt(Addons.Stream::quality).reversed());
        };
        String subLang = a.getSharedPreferences("player", android.content.Context.MODE_PRIVATE).getString("subLang", null);
        Addons ad = Addons.get(a);
        a.cat.async(() -> {
            target.subs = ad.subtitles(target, subLang);
            return true;
        }, ok -> {
        });
        pending[0] += ad.streams(target, (name, l) -> a.ui.post(() -> {
            pending[0]--;
            add.accept(l);
            decide.run();
        })).size();
        Plugins plugins = Plugins.get(a);
        boolean anyPlugin = false;
        for (Plugins.Scraper sc : plugins.scrapers()) if (sc.enabled) anyPlugin = true;
        if (anyPlugin) {
            pending[0]++;
            a.cat.async(() -> {
                Future<List<Addons.Stream>> f = plugins.future(target, part -> a.ui.post(() -> {
                    add.accept(part);
                    decide.run();
                }));
                try {
                    f.get(45, TimeUnit.SECONDS);
                } catch (Exception e) {
                    f.cancel(true);
                }
                return true;
            }, ok -> {
                pending[0]--;
                decide.run();
            });
        }
        a.ui.postDelayed(decide, 5_000);
        decide.run();
    }

    /**
     * asks every source at once for a film's or an episode's links, in a window that shows each
     * one's search as it goes; the link picked plays
     */
    private void chooseStream(Model.Item target, Runnable play) {
        String subLang = a.getSharedPreferences("player", android.content.Context.MODE_PRIVATE).getString("subLang", null);
        String title = target.kind == Model.EPISODE
                ? item.name + "  ·  S" + target.season + " É" + target.episode + (target.name == null ? "" : " — " + target.name)
                : target.name;
        StreamPicker[] holder = new StreamPicker[1];
        StreamPicker picker = new StreamPicker(a, title, s -> {
            target.url = s.url;
            target.bingeGroup = s.bingeGroup;
            target.headers = s.headers;
            target.source = s.source();
            // the player falls back on the others, and sees those found after it started
            target.alternatives = holder[0].streams();
            play.run();
        });
        holder[0] = picker;
        picker.backdrop(item.backdrop != null && !item.backdrop.isEmpty() ? item.backdrop : item.image);
        // opened to choose: nothing plays by itself (Play is the one that resumes the last source)
        Addons ad = Addons.get(a);
        a.cat.async(() -> {
            target.subs = ad.subtitles(target, subLang);
            return true;
        }, ok -> {
        });
        for (String name : ad.streams(target, (name, found) -> a.ui.post(() -> picker.done(name, found)))) picker.pending(name);
        Plugins plugins = Plugins.get(a);
        boolean anyPlugin = false;
        for (Plugins.Scraper sc : plugins.scrapers()) if (sc.enabled) anyPlugin = true;
        if (anyPlugin) {
            String name = "Plugins Nuvio";
            picker.pending(name);
            a.cat.async(() -> {
                // each script's links show as soon as it answers; the row waits for the rest
                Future<List<Addons.Stream>> f = plugins.future(target, part -> a.ui.post(() -> picker.partial(name, part)));
                try {
                    f.get(45, TimeUnit.SECONDS);
                    return new ArrayList<Addons.Stream>();
                } catch (Exception e) {
                    f.cancel(true);
                    return null;
                }
            }, found -> picker.done(name, found));
        }
        picker.show();
    }

    private Model.Item find(Model.Item e) {
        for (List<Model.Item> l : seasons.values()) for (Model.Item x : l) if (x.id.equals(e.id)) return x;
        return null;
    }

    private Model.Item nextEpisode(Model.Item e) {
        boolean next = false;
        for (List<Model.Item> l : seasons.values()) {
            for (Model.Item x : l) {
                if (next) return x;
                if (x.id.equals(e.id)) next = true;
            }
        }
        return null;
    }

    void focus() {
        view.post(() -> playButton.requestFocus());
    }

    /** after playing: the resume points have moved */
    void refresh() {
        boolean onPlay = playButton != null && playButton.hasFocus();
        buildButtons();
        if (season >= 0 && seasons.containsKey(season)) {
            boolean inEps = episodesRow.hasFocus();
            pickSeason(season);
            if (inEps) episodesRow.focusAt(episodeIndex);
        }
        if (onPlay || view.findFocus() == null) focus();
    }

    /** Back: false to close the page */
    boolean back() {
        // Back during Play's search stops it, and stays on the page
        if (quickCancel != null) {
            quickCancel.run();
            return true;
        }
        return false;
    }
}
