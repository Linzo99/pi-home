package com.sall.pitv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything the source offers. Kept on disk as the provider sent it, so the app opens on the
 * last copy at once and refreshes in the background when it is more than 12 hours old, or when
 * asked; a failed refresh keeps the old copy.
 */
final class Catalog {
    interface Listener {
        void onCatalog();

        void onStatus(String status);
    }

    static final long MAX_AGE = 12 * 3600_000L;
    private static Catalog instance;

    final Epg epg = new Epg();
    Source source;
    List<Model.Category> live = new ArrayList<>(), movies = new ArrayList<>(), series = new ArrayList<>();
    List<Model.Item> allLive = new ArrayList<>(), allMovies = new ArrayList<>(), allSeries = new ArrayList<>();
    Map<String, Model.Item> byKey = new HashMap<>();
    boolean loaded;
    String status = "";
    /** set when loading failed with nothing to show */
    String error;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    static synchronized Catalog get(Context c) {
        if (instance == null) instance = new Catalog(c.getApplicationContext());
        return instance;
    }

    private Catalog(Context c) {
        app = c;
        source = Source.load(c);
        Net.agent = source.agent;
    }

    void listen(Listener l) {
        listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    /** loads from the cache, then refreshes whatever is stale (all of it when force) */
    void load(boolean force) {
        worker.execute(() -> {
            Source s = Source.load(app);
            source = s;
            Net.agent = s.agent;
            if (!s.isSet()) {
                publish(new Data(), null);
                return;
            }
            File dir = new File(app.getFilesDir(), "cat/" + s.id());
            dir.mkdirs();
            boolean haveCache = Source.XTREAM.equals(s.type) ? new File(dir, "live.json").exists() : new File(dir, "playlist.m3u").exists();
            if (haveCache && !force) {
                try {
                    Data d = parse(s, dir);
                    publish(d, null);
                    loadGuide(s, dir, false, d);
                } catch (Exception e) {
                    Log.w("PiTV", "cache", e);
                }
            }
            if (!force && haveCache && !stale(s, dir)) return;
            try {
                status("Mise à jour de la liste…");
                download(s, dir, force);
                Data d = parse(s, dir);
                publish(d, null);
                status("");
                loadGuide(s, dir, force, d);
            } catch (Exception e) {
                Log.w("PiTV", "refresh", e);
                if (!loaded || byKey.isEmpty()) publish(new Data(), "Impossible de charger la liste : " + e.getMessage());
                status("Mise à jour impossible — liste précédente affichée");
            }
        });
    }

    private boolean stale(Source s, File dir) {
        File f = new File(dir, Source.XTREAM.equals(s.type) ? "live.json" : "playlist.m3u");
        return System.currentTimeMillis() - f.lastModified() > MAX_AGE;
    }

    private void download(Source s, File dir, boolean force) throws Exception {
        if (Source.XTREAM.equals(s.type)) {
            String why = Xtream.check(s);
            if (why != null) throw new Exception(why);
            String[][] parts = {
                    {"live_cat.json", "get_live_categories", "Chaînes"}, {"live.json", "get_live_streams", "Chaînes"},
                    {"vod_cat.json", "get_vod_categories", "Films"}, {"vod.json", "get_vod_streams", "Films"},
                    {"series_cat.json", "get_series_categories", "Séries"}, {"series.json", "get_series", "Séries"}};
            for (String[] p : parts) {
                status("Téléchargement : " + p[2] + "…");
                Net.toFile(s.api(p[1]), new File(dir, p[0]));
            }
        } else {
            status("Téléchargement de la playlist…");
            Net.toFile(s.m3u.trim(), new File(dir, "playlist.m3u"));
        }
    }

    private static final class Data {
        final List<Model.Category> live = new ArrayList<>(), movies = new ArrayList<>(), series = new ArrayList<>();
        final List<Model.Item> allLive = new ArrayList<>(), allMovies = new ArrayList<>(), allSeries = new ArrayList<>();
        final Map<String, Model.Item> byKey = new HashMap<>();
        String epgUrl;
    }

    private Data parse(Source s, File dir) throws Exception {
        Data d = new Data();
        if (Source.XTREAM.equals(s.type)) {
            group(d.live, d.allLive, Xtream.categories(new File(dir, "live_cat.json"), Model.LIVE),
                    Xtream.streams(new File(dir, "live.json"), Model.LIVE, s));
            group(d.movies, d.allMovies, opt(() -> Xtream.categories(new File(dir, "vod_cat.json"), Model.MOVIE)),
                    opt(() -> Xtream.streams(new File(dir, "vod.json"), Model.MOVIE, s)));
            group(d.series, d.allSeries, opt(() -> Xtream.categories(new File(dir, "series_cat.json"), Model.SERIES)),
                    opt(() -> Xtream.streams(new File(dir, "series.json"), Model.SERIES, s)));
        } else {
            M3u.Result r = M3u.parse(new File(dir, "playlist.m3u"));
            flatten(r.live, d.live, d.allLive);
            flatten(r.movies, d.movies, d.allMovies);
            flatten(r.series, d.series, d.allSeries);
            d.epgUrl = r.epg;
        }
        for (List<Model.Item> l : List.of(d.allLive, d.allMovies, d.allSeries)) {
            for (Model.Item i : l) {
                i.folded = fold(i.name);
                d.byKey.put(i.key(), i);
            }
        }
        return d;
    }

    private interface Loader<T> {
        List<T> run() throws Exception;
    }

    /** films and series are optional: some subscriptions are live only */
    private static <T> List<T> opt(Loader<T> l) {
        try {
            return l.run();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** puts items in their categories, in the provider's order; the uncategorised go last */
    private static void group(List<Model.Category> out, List<Model.Item> all, List<Model.Category> cats, List<Model.Item> items) {
        Map<String, Model.Category> m = new LinkedHashMap<>();
        for (Model.Category c : cats) m.put(c.id, c);
        Model.Category other = null;
        for (Model.Item i : items) {
            Model.Category c = i.category == null ? null : m.get(i.category);
            if (c == null) {
                if (other == null) other = new Model.Category(cats.isEmpty() ? items.get(0).kind : cats.get(0).kind, "", "Autres");
                c = other;
            }
            c.items.add(i);
        }
        for (Model.Category c : m.values()) if (!c.items.isEmpty()) out.add(c);
        if (other != null) out.add(other);
        all.addAll(items);
    }

    private static void flatten(Map<String, Model.Category> m, List<Model.Category> out, List<Model.Item> all) {
        java.util.Set<Model.Item> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Model.Category c : m.values()) {
            out.add(c);
            for (Model.Item i : c.items) if (seen.add(i)) all.add(i);
        }
        // in playlist order, not category order
        all.sort((x, y) -> Integer.compare(x.num, y.num));
    }

    private void loadGuide(Source s, File dir, boolean force, Data d) {
        String url = !s.epg.isEmpty() ? s.epg : null;
        if (url == null && Source.M3U.equals(s.type)) url = d.epgUrl;
        if (url == null) return;
        File f = new File(dir, "guide.xml");
        try {
            if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE) {
                status("Téléchargement du guide des programmes…");
                Net.toFile(url, f);
                status("");
            }
        } catch (Exception e) {
            Log.w("PiTV", "guide download", e);
            status("Guide des programmes indisponible");
            if (!f.exists()) return;
        }
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Model.Item ch : d.allLive) {
            if (ch.epgId != null && !ch.epgId.isEmpty()) ids.add(Epg.lower(ch.epgId));
            names.add(Epg.norm(ch.name));
        }
        epg.loadXmltv(f, ids, names);
        ui.post(() -> {
            for (Listener l : listeners) l.onCatalog();
        });
    }

    private void publish(Data d, String err) {
        ui.post(() -> {
            live = d.live;
            movies = d.movies;
            series = d.series;
            allLive = d.allLive;
            allMovies = d.allMovies;
            allSeries = d.allSeries;
            byKey = d.byKey;
            loaded = true;
            error = err;
            for (Listener l : listeners) l.onCatalog();
        });
    }

    private void status(String s) {
        ui.post(() -> {
            status = s;
            for (Listener l : listeners) l.onStatus(s);
        });
    }

    /** runs work off the main thread, then done on it */
    <T> void async(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> done) {
        pool.execute(() -> {
            T r;
            try {
                r = work.call();
            } catch (Exception e) {
                Log.w("PiTV", "async", e);
                r = null;
            }
            T res = r;
            ui.post(() -> done.accept(res));
        });
    }

    /** the most recently added films or series */
    static List<Model.Item> recent(List<Model.Item> all, int n) {
        List<Model.Item> l = new ArrayList<>(all);
        l.sort((a, b) -> Long.compare(b.added, a.added));
        return l.subList(0, Math.min(n, l.size()));
    }

    List<Model.Item> search(String q, int kind, int max) {
        String f = fold(q.trim());
        List<Model.Item> out = new ArrayList<>();
        if (f.isEmpty()) return out;
        List<Model.Item> src = kind == Model.LIVE ? allLive : kind == Model.MOVIE ? allMovies : allSeries;
        // names that start with the words first, then those that contain them
        List<Model.Item> later = new ArrayList<>();
        for (Model.Item i : src) {
            if (i.folded == null) continue;
            if (i.folded.startsWith(f)) out.add(i);
            else if (i.folded.contains(f) && later.size() < max) later.add(i);
            if (out.size() >= max) break;
        }
        for (Model.Item i : later) if (out.size() < max) out.add(i);
        return out;
    }

    static String fold(String s) {
        if (s == null) return null;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }
}
