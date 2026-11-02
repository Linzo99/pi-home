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
    private final File file;
    private final Map<String, Model.Item> favs = new LinkedHashMap<>();
    private final Map<String, Progress> progress = new LinkedHashMap<>();
    private final List<Model.Item> recentLive = new ArrayList<>();

    static synchronized Library get(Context c) {
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
        return progress.get(i.key());
    }

    void setProgress(Model.Item i, long pos, long dur) {
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
            if (p.finished() || p.pos < 30_000) continue;
            out.add(p);
            if (out.size() >= 20) break;
        }
        return out;
    }

    /** the episode last played in a series, if any */
    Progress lastEpisode(String seriesId) {
        Progress best = null;
        for (Progress p : progress.values()) {
            if (p.item.kind == Model.EPISODE && seriesId.equals(p.item.seriesId) && (best == null || p.time > best.time)) best = p;
        }
        return best;
    }

    void watchedLive(Model.Item ch) {
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
