package com.sall.pitv.plugins;

import com.sall.pitv.R;
import com.sall.pitv.ui.*;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The number the Nuvio scrapers ask for: a TMDB id.  Cinemeta, which is one of the default
 * add-ons, gives it for an IMDb id or a title search, so a key is not needed.
 */
public final class Tmdb {
    private static final String BASE = "https://v3-cinemeta.strem.io";
    private static final Pattern IMDB = Pattern.compile("(tt\\d{6,})");
    private static final Pattern TMDB = Pattern.compile("(?:^|[^a-z])tmdb[:/](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> cache = new LinkedHashMap<String, String>(200, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 200;
        }
    };

    private Tmdb() {
    }

    /** null when Cinemeta doesn't know the title either */
    static String resolve(Model.Item item) {
        String key = item.key() + "|" + item.name + "|" + item.seriesName;
        synchronized (cache) {
            String hit = cache.get(key);
            if (hit != null) return hit.isEmpty() ? null : hit;
        }
        String id;
        try {
            id = find(item);
        } catch (Exception e) {
            Log.w("PiTV", "tmdb " + item.name, e);
            id = null;
        }
        synchronized (cache) {
            cache.put(key, id == null ? "" : id);
        }
        return id;
    }

    private static String find(Model.Item item) throws Exception {
        String raw = item.kind == Model.EPISODE && item.seriesId != null && !item.seriesId.isEmpty()
                ? item.seriesId : item.sid;
        Matcher tm = TMDB.matcher(raw == null ? "" : raw);
        if (tm.find()) return tm.group(1);
        if (raw != null && raw.matches("\\d+")) return raw;

        String type = item.kind == Model.MOVIE ? "movie" : "series";
        Matcher im = IMDB.matcher((raw == null ? "" : raw) + " " + (item.sid == null ? "" : item.sid));
        if (im.find()) {
            String viaMeta = fromMeta(type, im.group(1));
            if (viaMeta != null) return viaMeta;
        }
        String title = item.kind == Model.EPISODE && item.seriesName != null && !item.seriesName.isEmpty()
                ? item.seriesName : item.name;
        if (title == null || title.isEmpty()) return null;
        return fromSearch(type, title, item.year);
    }

    private static String fromMeta(String type, String imdb) throws Exception {
        JSONObject meta = new JSONObject(Net.text(BASE + "/meta/" + type + "/" + imdb + ".json")).optJSONObject("meta");
        if (meta == null) return null;
        String id = digits(meta.optString("moviedb_id"));
        if (id != null) return id;
        // some entries have only the IMDb id: the search below finds the TMDB one
        return null;
    }

    private static String fromSearch(String type, String title, String year) throws Exception {
        String q = URLEncoder.encode(clean(title), StandardCharsets.UTF_8);
        JSONArray metas = new JSONObject(Net.text(BASE + "/catalog/" + type + "/top/search=" + q + ".json")).optJSONArray("metas");
        String best = null;
        int bestScore = -1;
        for (int i = 0; metas != null && i < metas.length(); i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            int score = score(m.optString("name"), m.optString("releaseInfo"), title, year);
            if (score <= bestScore) continue;
            String id = digits(m.optString("moviedb_id"));
            if (id == null) {
                String imdb = m.optString("imdb_id", m.optString("id"));
                if (imdb.startsWith("tt")) id = fromMeta(type, imdb);
            }
            if (id != null) {
                bestScore = score;
                best = id;
            }
        }
        return bestScore >= 30 ? best : null;
    }

    private static int score(String name, String release, String wanted, String year) {
        String a = norm(name), b = norm(wanted);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int score;
        if (a.equals(b)) score = 100;
        else if (a.startsWith(b) || b.startsWith(a)) score = 60;
        else if (a.contains(b) || b.contains(a)) score = 30;
        else return 0;
        String y = year == null ? "" : year.replaceAll("\\D.*$", "");
        if (!y.isEmpty() && release != null && release.startsWith(y)) score += 20;
        return score;
    }

    private static String clean(String title) {
        return title.replaceAll("\\s*\\((?:VF|VOSTFR|VO|VOST|HD|4K)\\)\\s*$", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String digits(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.matches("\\d+") ? s : null;
    }
}
