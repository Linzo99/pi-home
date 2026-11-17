package com.sall.pitv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Favourites, where each film or episode was left, and the channels watched last; one file per
 * source. Items are stored with what is needed to show and play them, so the rows work before
 * the catalogue has loaded.
 */
final class Library {
    static final class Progress {
        final Model.Item item;
        long pos, dur, time;

        Progress(Model.Item item) {
            this.item = item;
        }

        boolean finished() {
            return dur > 0 && pos > dur * 0.92;
        }
    }

    private static Library instance;
    /** private mode: nothing watched is written down, in this file or anywhere else */
    static volatile boolean incognito;
    /** where things were left during private mode: this session only, never shown in a row */
    private final Map<String, Progress> quiet = new java.util.HashMap<>();
    private final File file;
    private final Map<String, Model.Item> favs = new LinkedHashMap<>();
    private final Map<String, Progress> progress = new LinkedHashMap<>();
    private final List<Model.Item> recentLive = new ArrayList<>();

    static synchronized Library get(Context c) {
        incognito = c.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("incognito", false);
        String id = Source.load(c).id();
        if (instance == null || !instance.file.getName().equals("library-" + id + ".json")) {
            instance = new Library(new File(c.getFilesDir(), "library-" + id + ".json"));
        }
        return instance;
    }

    private Library(File f) {
        file = f;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray fa = o.optJSONArray("favs");
            for (int i = 0; fa != null && i < fa.length(); i++) {
                Model.Item it = Model.Item.fromJson(fa.getJSONObject(i));
                favs.put(it.key(), it);
            }
            JSONObject pr = o.optJSONObject("progress");
            if (pr != null) {
                for (Iterator<String> k = pr.keys(); k.hasNext(); ) {
                    JSONObject p = pr.getJSONObject(k.next());
                    Progress g = new Progress(Model.Item.fromJson(p.getJSONObject("item")));
                    g.pos = p.optLong("pos");
                    g.dur = p.optLong("dur");
                    g.time = p.optLong("time");
                    progress.put(g.item.key(), g);
                }
            }
            JSONArray rl = o.optJSONArray("recentLive");
            for (int i = 0; rl != null && i < rl.length(); i++) recentLive.add(Model.Item.fromJson(rl.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }

    boolean isFav(Model.Item i) {
        return favs.containsKey(i.key());
    }

    /** returns whether it is now a favourite */
    boolean toggleFav(Model.Item i) {
        boolean now;
        if (favs.remove(i.key()) == null) {
            favs.put(i.key(), i);
            now = true;
        } else now = false;
        save();
        return now;
    }

    List<Model.Item> favs(int kind) {
        List<Model.Item> out = new ArrayList<>();
        for (Model.Item i : favs.values()) if (i.kind == kind) out.add(i);
        java.util.Collections.reverse(out);
        return out;
    }

    Progress progress(Model.Item i) {
        return progress(i.key());
    }

    Progress progress(String key) {
        Progress q = quiet.get(key);
        return q != null ? q : progress.get(key);
    }

    void setProgress(Model.Item i, long pos, long dur) {
        // adult titles are never written down: only this session remembers where they were left
        if (incognito || i.adult) {
            Progress p = quiet.get(i.key());
            if (p == null) quiet.put(i.key(), p = new Progress(i));
            p.pos = pos;
            p.dur = dur;
            p.time = System.currentTimeMillis();
            return;
        }
        Progress p = progress.get(i.key());
        if (p == null) p = new Progress(i);
        progress.remove(i.key());
        p.pos = pos;
        p.dur = dur;
        p.time = System.currentTimeMillis();
        progress.put(i.key(), p);
        // the 200 most recent are plenty
        while (progress.size() > 200) progress.remove(progress.keySet().iterator().next());
        save();
    }

    /** films and episodes started but not finished, the latest first; one entry per series */
    List<Progress> continueWatching() {
        List<Progress> all = new ArrayList<>(progress.values());
        all.sort((a, b) -> Long.compare(b.time, a.time));
        List<Progress> out = new ArrayList<>();
        java.util.Set<String> shows = new java.util.HashSet<>();
        for (Progress p : all) {
            if (p.item.kind == Model.EPISODE) {
                if (!shows.add(p.item.seriesId)) continue;
            }
            if (p.finished() || p.pos < 30_000 || p.item.adult) continue;
            out.add(p);
            if (out.size() >= 20) break;
        }
        return out;
    }

    /** the episode last played in a series, if any */
    Progress lastEpisode(String seriesId) {
        Progress best = null;
        List<Progress> all = new ArrayList<>(progress.values());
        all.addAll(quiet.values());
        for (Progress p : all) {
            if (p.item.kind == Model.EPISODE && seriesId.equals(p.item.seriesId) && (best == null || p.time > best.time)) best = p;
        }
        return best;
    }

    void watchedLive(Model.Item ch) {
        if (incognito) return;
        for (int i = 0; i < recentLive.size(); i++) {
            if (recentLive.get(i).key().equals(ch.key())) {
                recentLive.remove(i);
                break;
            }
        }
        recentLive.add(0, ch);
        while (recentLive.size() > 15) recentLive.remove(recentLive.size() - 1);
        save();
    }

    List<Model.Item> recentLive() {
        return new ArrayList<>(recentLive);
    }

    /** switches private mode on or off; what was left during it is forgotten when it ends */
    static void setIncognito(Context c, boolean on) {
        incognito = on;
        c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("incognito", on).apply();
        if (!on && instance != null) synchronized (instance) {
            instance.quiet.clear();
        }
    }

    /**
     * forgets what was watched, for every source: where films and episodes were left, the
     * channels watched last and the link each title last played from. The favourites stay.
     */
    static synchronized void clearHistory(Context c) {
        File[] files = c.getFilesDir().listFiles((d, n) -> n.startsWith("library-") && n.endsWith(".json"));
        for (File f : files == null ? new File[0] : files) {
            Library l = instance != null && instance.file.equals(f) ? instance : new Library(f);
            l.progress.clear();
            l.quiet.clear();
            l.recentLive.clear();
            l.save();
        }
        android.content.SharedPreferences p = c.getSharedPreferences("player", Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("src.")) e.remove(k);
        e.apply();
        TvHome.sync(c);
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            JSONArray fa = new JSONArray();
            for (Model.Item i : favs.values()) fa.put(i.toJson());
            o.put("favs", fa);
            JSONObject pr = new JSONObject();
            for (Progress p : progress.values()) {
                pr.put(p.item.key(), new JSONObject().put("item", p.item.toJson()).put("pos", p.pos).put("dur", p.dur).put("time", p.time));
            }
            o.put("progress", pr);
            JSONArray rl = new JSONArray();
            for (Model.Item i : recentLive) rl.put(i.toJson());
            o.put("recentLive", rl);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
    }
}
