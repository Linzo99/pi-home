package com.sall.pitv;

import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Xtream Codes API (player_api.php). Lists can hold tens of thousands of entries, so they are
 * read as a stream; fields come as strings or numbers depending on the panel, so every value is
 * read leniently.
 */
final class Xtream {
    /** checks the login; returns null when fine, else the reason */
    static String check(Source s) {
        try {
            JSONObject o = new JSONObject(Net.text(s.api(null)));
            JSONObject u = o.optJSONObject("user_info");
            if (u == null) return "Réponse inattendue du serveur";
            if (u.optInt("auth", 0) != 1) return "Identifiant ou mot de passe refusé";
            String status = u.optString("status", "Active");
            if (!status.equalsIgnoreCase("Active")) return "Abonnement : " + status;
            return null;
        } catch (Exception e) {
            return "Serveur injoignable (" + e.getMessage() + ")";
        }
    }

    static List<Model.Category> categories(File f, int kind) throws IOException {
        List<Model.Category> out = new ArrayList<>();
        try (JsonReader r = reader(f)) {
            if (r.peek() != JsonToken.BEGIN_ARRAY) return out;
            r.beginArray();
            while (r.hasNext()) {
                String id = null, name = null;
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if (k.equals("category_id")) id = str(r);
                    else if (k.equals("category_name")) name = str(r);
                    else r.skipValue();
                }
                r.endObject();
                if (id != null) out.add(new Model.Category(kind, id, name == null ? id : name.trim()));
            }
            r.endArray();
        }
        return out;
    }

    static List<Model.Item> streams(File f, int kind, Source s) throws IOException {
        List<Model.Item> out = new ArrayList<>();
        try (JsonReader r = reader(f)) {
            if (r.peek() != JsonToken.BEGIN_ARRAY) return out;
            r.beginArray();
            while (r.hasNext()) {
                Model.Item i = new Model.Item();
                i.kind = kind;
                String ext = null;
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "stream_id":
                        case "series_id":
                            i.id = str(r);
                            break;
                        case "num":
                            i.num = (int) num(str(r));
                            break;
                        case "name":
                            i.name = str(r);
                            break;
                        case "stream_icon":
                        case "cover":
                            i.image = str(r);
                            break;
                        case "backdrop_path":
                            i.backdrop = first(r);
                            break;
                        case "category_id":
                            i.category = str(r);
                            break;
                        case "epg_channel_id":
                            i.epgId = str(r);
                            break;
                        case "added":
                        case "last_modified":
                            i.added = num(str(r));
                            break;
                        case "rating":
                            i.rating = str(r);
                            break;
                        case "releaseDate":
                        case "release_date":
                        case "year":
                            String y = str(r);
                            if (y != null && y.length() >= 4) i.year = y.substring(0, 4);
                            break;
                        case "plot":
                            i.plot = str(r);
                            break;
                        case "genre":
                            i.genre = str(r);
                            break;
                        case "cast":
                            i.cast = str(r);
                            break;
                        case "container_extension":
                            ext = str(r);
                            break;
                        default:
                            r.skipValue();
                    }
                }
                r.endObject();
                if (i.id == null || i.name == null) continue;
                i.name = i.name.trim();
                if (kind == Model.LIVE) i.url = s.liveUrl(i.id, "ts");
                else if (kind == Model.MOVIE) i.url = s.movieUrl(i.id, ext);
                out.add(i);
            }
            r.endArray();
        }
        return out;
    }

    /** a series' seasons and episodes, plus the details missing from the list */
    static Map<Integer, List<Model.Item>> episodes(Source s, Model.Item series) throws Exception {
        JSONObject o = new JSONObject(Net.text(s.api("get_series_info") + "&series_id=" + series.id));
        JSONObject info = o.optJSONObject("info");
        if (info != null) {
            if (empty(series.plot)) series.plot = info.optString("plot", null);
            if (empty(series.genre)) series.genre = info.optString("genre", null);
            if (empty(series.cast)) series.cast = info.optString("cast", null);
            JSONArray bd = info.optJSONArray("backdrop_path");
            if (empty(series.backdrop) && bd != null && bd.length() > 0) series.backdrop = bd.optString(0);
        }
        Map<Integer, List<Model.Item>> seasons = new LinkedHashMap<>();
        Object eps = o.opt("episodes");
        List<JSONArray> lists = new ArrayList<>();
        if (eps instanceof JSONObject) {
            JSONObject e = (JSONObject) eps;
            for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                JSONArray a = e.optJSONArray(it.next());
                if (a != null) lists.add(a);
            }
        } else if (eps instanceof JSONArray) {
            // some panels send a list of lists
            JSONArray a = (JSONArray) eps;
            for (int i = 0; i < a.length(); i++) if (a.optJSONArray(i) != null) lists.add(a.optJSONArray(i));
        }
        for (JSONArray a : lists) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                Model.Item ep = new Model.Item();
                ep.kind = Model.EPISODE;
                ep.id = e.optString("id");
                ep.season = e.optInt("season", 1);
                ep.episode = e.optInt("episode_num", i + 1);
                ep.name = e.optString("title", "Épisode " + ep.episode);
                ep.seriesId = series.id;
                ep.seriesName = series.name;
                JSONObject ei = e.optJSONObject("info");
                if (ei != null) {
                    ep.image = ei.optString("movie_image", null);
                    ep.plot = ei.optString("plot", null);
                }
                if (empty(ep.image)) ep.image = series.backdrop != null ? series.backdrop : series.image;
                ep.backdrop = series.backdrop;
                ep.url = s.episodeUrl(ep.id, e.optString("container_extension"));
                List<Model.Item> season = seasons.get(ep.season);
                if (season == null) seasons.put(ep.season, season = new ArrayList<>());
                season.add(ep);
            }
        }
        for (List<Model.Item> l : seasons.values()) l.sort((x, y) -> Integer.compare(x.episode, y.episode));
        return seasons;
    }

    /** a film's plot, backdrop and the rest, which the list leaves out */
    static void details(Source s, Model.Item movie) throws Exception {
        JSONObject o = new JSONObject(Net.text(s.api("get_vod_info") + "&vod_id=" + movie.id));
        JSONObject info = o.optJSONObject("info");
        if (info == null) return;
        if (empty(movie.plot)) movie.plot = info.optString("plot", info.optString("description", null));
        if (empty(movie.genre)) movie.genre = info.optString("genre", null);
        if (empty(movie.cast)) movie.cast = info.optString("cast", info.optString("actors", null));
        if (empty(movie.year)) {
            String y = info.optString("releasedate", info.optString("release_date", ""));
            if (y.length() >= 4) movie.year = y.substring(0, 4);
        }
        JSONArray bd = info.optJSONArray("backdrop_path");
        if (empty(movie.backdrop) && bd != null && bd.length() > 0) movie.backdrop = bd.optString(0);
        if (empty(movie.image)) movie.image = info.optString("movie_image", null);
    }

    /** now and next for a channel, from the provider's short guide */
    static List<Model.Prog> shortEpg(Source s, String streamId) throws Exception {
        JSONObject o = new JSONObject(Net.text(s.api("get_short_epg") + "&stream_id=" + streamId + "&limit=12"));
        JSONArray a = o.optJSONArray("epg_listings");
        List<Model.Prog> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            long start = p.optLong("start_timestamp", 0) * 1000, stop = p.optLong("stop_timestamp", 0) * 1000;
            if (start == 0 || stop <= start) continue;
            out.add(new Model.Prog(start, stop, b64(p.optString("title")), b64(p.optString("description"))));
        }
        return out;
    }

    private static String b64(String s) {
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return s;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("null");
    }

    private static JsonReader reader(File f) throws IOException {
        InputStream in = Net.file(f);
        JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        r.setLenient(true);
        return r;
    }

    private static String str(JsonReader r) throws IOException {
        switch (r.peek()) {
            case STRING:
            case NUMBER:
                return r.nextString();
            case BOOLEAN:
                return String.valueOf(r.nextBoolean());
            case NULL:
                r.nextNull();
                return null;
            default:
                r.skipValue();
                return null;
        }
    }

    private static String first(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) return str(r);
        String v = null;
        r.beginArray();
        while (r.hasNext()) {
            String s = str(r);
            if (v == null && s != null && !s.isEmpty()) v = s;
        }
        r.endArray();
        return v;
    }

    private static long num(String s) {
        if (s == null) return 0;
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Xtream() {
    }
}
