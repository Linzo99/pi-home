package com.sall.pitv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Stremio add-ons: films, series and anime from their catalogues, and the streams and subtitles
 * add-ons offer for a title. The add-ons are the user's choice; the defaults only describe titles
 * (Cinemeta, Kitsu) and bring subtitles (OpenSubtitles), they play nothing.
 *
 * The protocol is plain JSON over HTTP: {base}/manifest.json, then
 * {base}/{catalog|meta|stream|subtitles}/{type}/{id}[/{extra}].json.
 */
final class Addons {
    static final String[] DEFAULTS = {
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://anime-kitsu.strem.fun/manifest.json",
            "https://opensubtitles-v3.strem.io/manifest.json"};
    private static final long MAX_AGE = 6 * 3600_000L;
    /** catalogues shown per add-on and kind, so a big add-on doesn't fill every page */
    private static final int MAX_CATALOGS = 6;

    static final class Addon {
        final String url, base;
        String name = "";
        JSONObject manifest;

        Addon(String url) {
            this.url = url;
            base = url.replaceFirst("/manifest\\.json.*$", "");
        }

        /** whether it answers [resource] for this type and id */
        boolean serves(String resource, String type, String id) {
            if (manifest == null) return false;
            JSONArray res = manifest.optJSONArray("resources");
            for (int i = 0; res != null && i < res.length(); i++) {
                Object r = res.opt(i);
                JSONArray types = manifest.optJSONArray("types"), prefixes = manifest.optJSONArray("idPrefixes");
                if (r instanceof JSONObject) {
                    JSONObject o = (JSONObject) r;
                    if (!resource.equals(o.optString("name"))) continue;
                    if (o.has("types")) types = o.optJSONArray("types");
                    if (o.has("idPrefixes")) prefixes = o.optJSONArray("idPrefixes");
                } else if (!resource.equals(r)) continue;
                if (type != null && !has(types, type)) continue;
                if (id != null && prefixes != null && prefixes.length() > 0 && !startsWithAny(id, prefixes)) continue;
                return true;
            }
            return false;
        }
    }

    /** a row of titles from one of an add-on's catalogues */
    static final class Row {
        final String name;
        final int kind;
        final List<Model.Item> items;

        Row(String name, int kind, List<Model.Item> items) {
            this.name = name;
            this.kind = kind;
            this.items = items;
        }
    }

    static final class Stream {
        String addon, name, title, url, bingeGroup;
        /** a plugin's stream often needs a Referer or a User-Agent */
        java.util.Map<String, String> headers;

        /** 0 unknown, 1 = 360p … 5 = 4K */
        int quality() {
            return Plugins.quality(name + " " + title);
        }

        /** the source as remembered for a title: the add-on or script, and its kind of stream */
        String source() {
            return addon + "|" + (bingeGroup != null && !bingeGroup.isEmpty() ? bingeGroup : "q" + quality());
        }

        String label() {
            String t = title == null ? "" : title.replace('\n', ' ').trim();
            String n = name == null ? "" : name.replace('\n', ' ').trim();
            return addon + (n.isEmpty() || n.equals(addon) ? "" : " · " + n) + (t.isEmpty() ? "" : "\n" + t);
        }
    }

    private static Addons instance;
    private final Context app;
    private final File dir;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    final List<Addon> list = new ArrayList<>();
    /** what the catalogues showed, for films, series and anime */
    volatile List<Row> movies = new ArrayList<>(), series = new ArrayList<>(), anime = new ArrayList<>();
    /** every title seen, by key: a series' poster for its episodes in "continue watching" */
    final Map<String, Model.Item> byKey = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, JSONObject> metas = new LinkedHashMap<String, JSONObject>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> e) {
            return size() > 40;
        }
    };

    static synchronized Addons get(Context c) {
        if (instance == null) instance = new Addons(c.getApplicationContext());
        return instance;
    }

    private Addons(Context c) {
        app = c;
        dir = new File(c.getFilesDir(), "addons");
        dir.mkdirs();
        for (String u : urls()) list.add(new Addon(u));
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("addons", Context.MODE_PRIVATE);
    }

    List<String> urls() {
        List<String> out = new ArrayList<>();
        String saved = prefs().getString("urls", null);
        if (saved == null) return new ArrayList<>(List.of(DEFAULTS));
        try {
            JSONArray a = new JSONArray(saved);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** replaces the add-ons; links may be stremio:// and need not end in manifest.json */
    void setUrls(List<String> urls) {
        JSONArray a = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (String u : urls) {
            u = normalize(u);
            if (u != null && !seen.contains(u)) {
                seen.add(u);
                a.put(u);
            }
        }
        prefs().edit().putString("urls", a.toString()).apply();
        synchronized (list) {
            list.clear();
            for (String u : seen) list.add(new Addon(u));
        }
    }

    /** an add-on switched off stays listed, but brings nothing until switched back on */
    boolean enabled(String url) {
        return !off().contains(url);
    }

    void setEnabled(String url, boolean on) {
        java.util.Set<String> o = off();
        if (on) o.remove(url);
        else o.add(url);
        prefs().edit().putString("off", new JSONArray(o).toString()).apply();
    }

    private java.util.Set<String> off() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("off", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static String normalize(String u) {
        u = u.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("stremio://")) u = "https://" + u.substring(10);
        if (!u.startsWith("http")) u = "https://" + u;
        if (!u.contains("/manifest.json")) u = u.replaceFirst("/+$", "") + "/manifest.json";
        return u;
    }

    /** the add-ons switched on: what search, pages, links and subtitles ask */
    private List<Addon> addons() {
        java.util.Set<String> o = off();
        List<Addon> out = new ArrayList<>();
        synchronized (list) {
            for (Addon a : list) if (!o.contains(a.url)) out.add(a);
        }
        return out;
    }

    // ---------------------------------------------------------------- catalogues

    /** reads the manifests and catalogues: from disk when fresh, else from the add-ons */
    void load(boolean force) {
        List<Row> mv = new ArrayList<>(), sr = new ArrayList<>(), an = new ArrayList<>();
        List<Future<?>> jobs = new ArrayList<>();
        java.util.Set<String> o = off();
        List<Addon> all;
        synchronized (list) {
            all = new ArrayList<>(list);
        }
        for (Addon a : all) {
            try {
                a.manifest = new JSONObject(cached(a.url, force));
                // "AIOMetadata | ElfHosted": the add-on, not its host
                a.name = a.manifest.optString("name", a.base).split("\\s+\\|\\s+")[0].trim();
            } catch (Exception e) {
                Log.w("PiTV", "addon " + a.url, e);
                continue;
            }
            if (o.contains(a.url)) continue;
            JSONArray cats = a.manifest.optJSONArray("catalogs");
            Map<String, Integer> perKind = new HashMap<>();
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null || needsInput(c)) continue;
                String type = c.optString("type");
                int kind = kind(type);
                String bucket = "anime".equals(type) ? "anime" : type;
                if (kind < 0 || perKind.merge(bucket, 1, Integer::sum) > MAX_CATALOGS) continue;
                String url = a.base + "/catalog/" + type + "/" + enc(c.optString("id")) + ".json";
                String name = rowName(a, c.optString("name", c.optString("id")));
                List<Row> into = "anime".equals(type) ? an : kind == Model.MOVIE ? mv : sr;
                Row row = new Row(name, kind, new ArrayList<>());
                into.add(row);
                jobs.add(pool.submit(() -> {
                    try {
                        JSONArray metas = new JSONObject(cached(url, force)).optJSONArray("metas");
                        for (int k = 0; metas != null && k < metas.length(); k++) {
                            Model.Item it = item(metas.optJSONObject(k), type);
                            if (it != null) row.items.add(it);
                        }
                    } catch (Exception e) {
                        Log.w("PiTV", "catalog " + url, e);
                    }
                }));
            }
        }
        for (Future<?> f : jobs) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        mv.removeIf(r -> r.items.isEmpty());
        sr.removeIf(r -> r.items.isEmpty());
        an.removeIf(r -> r.items.isEmpty());
        for (List<Row> l : List.of(mv, sr, an)) for (Row r : l) for (Model.Item i : r.items) byKey.put(i.key(), i);
        movies = mv;
        series = sr;
        anime = an;
    }

    /**
     * a catalogue that can't be listed without typing something (a search, ids). A required
     * filter with choices, like a genre, is fine: add-ons such as AIOMetadata mark it required
     * only to keep the catalogue off Stremio's home screen, and answer with their default.
     */
    private static boolean needsInput(JSONObject c) {
        JSONArray extra = c.optJSONArray("extra");
        java.util.Set<String> withChoices = new java.util.HashSet<>();
        for (int i = 0; extra != null && i < extra.length(); i++) {
            JSONObject e = extra.optJSONObject(i);
            if (e == null) continue;
            JSONArray opts = e.optJSONArray("options");
            if (opts != null && opts.length() > 0) withChoices.add(e.optString("name"));
            else if (e.optBoolean("isRequired")) return true;
        }
        JSONArray req = c.optJSONArray("extraRequired");
        for (int i = 0; req != null && i < req.length(); i++) if (!withChoices.contains(req.optString(i))) return true;
        return false;
    }

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("popular", "Populaires"), Map.entry("new", "Nouveautés"), Map.entry("featured", "À la une"),
            Map.entry("trending", "Tendances"), Map.entry("top airing", "En cours de diffusion"),
            Map.entry("airing now", "En cours de diffusion"), Map.entry("upcoming season", "La saison prochaine"),
            Map.entry("most popular", "Les plus populaires"), Map.entry("most favorites", "Les préférés"),
            Map.entry("highest rated", "Les mieux notés"), Map.entry("top rated", "Les mieux notés"),
            Map.entry("top anime", "Les meilleurs animes"), Map.entry("top series", "Les meilleures séries"),
            Map.entry("top movies", "Les meilleurs films"), Map.entry("recommendations", "Recommandations"));

    private static String rowName(Addon a, String name) {
        return catalogName(name) + "  ·  " + a.name;
    }

    private static String catalogName(String name) {
        // no emoji or "Kitsu" before the name
        String n = name.replaceFirst("^[^\\p{L}\\p{N}]+", "").replaceFirst("(?i)^kitsu\\s+", "").trim();
        String fr = NAMES.get(n.toLowerCase(Locale.ROOT));
        return fr != null ? fr : n;
    }

    private static int kind(String type) {
        switch (type) {
            case "movie":
                return Model.MOVIE;
            case "series":
            case "anime":
            case "tv":
                return Model.SERIES;
            default:
                return -1;
        }
    }

    /** a title from a catalogue or a search */
    private static Model.Item item(JSONObject m, String catalogType) {
        if (m == null || m.optString("id").isEmpty()) return null;
        String type = m.optString("type", catalogType);
        int kind = kind(type);
        if (kind < 0) kind = kind(catalogType);
        if (kind < 0) return null;
        Model.Item i = new Model.Item();
        i.kind = kind;
        i.sid = m.optString("id");
        i.id = i.sid;
        i.stype = type;
        i.name = m.optString("name");
        i.image = m.optString("poster", null);
        i.backdrop = m.optString("background", null);
        i.plot = m.optString("description", null);
        i.year = m.optString("releaseInfo", m.optString("year", null));
        i.rating = m.optString("imdbRating", null);
        i.genre = join(m.optJSONArray("genres"), 3);
        return i;
    }

    /** titles whose name matches, from the catalogues that take a search */
    List<Model.Item> search(String q, int kind) {
        List<Model.Item> out = new ArrayList<>();
        List<Future<List<Model.Item>>> jobs = new ArrayList<>();
        for (Addon a : addons()) {
            JSONArray cats = a.manifest == null ? null : a.manifest.optJSONArray("catalogs");
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                String type = c == null ? "" : c.optString("type");
                if (kind(type) != kind || !takesSearch(c)) continue;
                String url = a.base + "/catalog/" + type + "/" + enc(c.optString("id")) + "/search=" + enc(q) + ".json";
                jobs.add(pool.submit(() -> {
                    List<Model.Item> l = new ArrayList<>();
                    JSONArray metas = new JSONObject(Net.text(url)).optJSONArray("metas");
                    for (int k = 0; metas != null && k < metas.length(); k++) {
                        Model.Item it = item(metas.optJSONObject(k), type);
                        if (it != null) l.add(it);
                    }
                    return l;
                }));
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Future<List<Model.Item>> f : jobs) {
            try {
                for (Model.Item i : f.get(20, TimeUnit.SECONDS)) {
                    if (seen.add(i.key())) {
                        out.add(i);
                        byKey.putIfAbsent(i.key(), i);
                    }
                }
            } catch (Exception e) {
                Log.w("PiTV", "addon search", e);
            }
        }
        return out;
    }

    private static boolean takesSearch(JSONObject c) {
        JSONArray extra = c.optJSONArray("extra");
        for (int i = 0; extra != null && i < extra.length(); i++) {
            JSONObject e = extra.optJSONObject(i);
            if (e != null && "search".equals(e.optString("name"))) return true;
        }
        JSONArray sup = c.optJSONArray("extraSupported");
        return has(sup, "search");
    }

    /**
     * one catalogue of the add-on with this manifest id, for a collection's folder; the add-on
     * may be switched off, the collection still names it
     */
    Row catalog(String addonId, String type, String catalogId, String genre) {
        List<Addon> all;
        synchronized (list) {
            all = new ArrayList<>(list);
        }
        for (Addon a : all) {
            if (a.manifest == null || !addonId.equals(a.manifest.optString("id"))) continue;
            String name = catalogId;
            JSONArray cats = a.manifest.optJSONArray("catalogs");
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c != null && type.equals(c.optString("type")) && catalogId.equals(c.optString("id"))) name = c.optString("name", catalogId);
            }
            String url = a.base + "/catalog/" + type + "/" + enc(catalogId)
                    + (genre == null || genre.isEmpty() ? "" : "/genre=" + enc(genre)) + ".json";
            Row row = new Row(catalogName(name), kind(type), new ArrayList<>());
            try {
                JSONArray metas = new JSONObject(cached(url, false)).optJSONArray("metas");
                for (int k = 0; metas != null && k < metas.length(); k++) {
                    Model.Item it = item(metas.optJSONObject(k), type);
                    if (it == null) continue;
                    row.items.add(it);
                    byKey.putIfAbsent(it.key(), it);
                }
            } catch (Exception e) {
                Log.w("PiTV", "catalog " + url, e);
            }
            return row;
        }
        return null;
    }

    // ---------------------------------------------------------------- details

    /**
     * fills a film's or series' page from the first add-on that describes it; a series' episodes
     * come back by season
     */
    Map<Integer, List<Model.Item>> details(Model.Item item) throws Exception {
        String key = item.stype + "/" + item.sid;
        JSONObject meta;
        synchronized (metas) {
            meta = metas.get(key);
        }
        if (meta == null) {
            for (Addon a : addons()) {
                if (!a.serves("meta", item.stype, item.sid)) continue;
                try {
                    meta = new JSONObject(Net.text(a.base + "/meta/" + item.stype + "/" + enc(item.sid) + ".json")).optJSONObject("meta");
                } catch (Exception e) {
                    Log.w("PiTV", "meta " + a.name, e);
                }
                if (meta != null) break;
            }
            if (meta == null) return new LinkedHashMap<>();
            synchronized (metas) {
                metas.put(key, meta);
            }
        }
        if (empty(item.plot)) item.plot = meta.optString("description", null);
        if (!empty(meta.optString("background"))) item.backdrop = meta.optString("background");
        if (empty(item.image)) item.image = meta.optString("poster", null);
        if (empty(item.year)) item.year = meta.optString("releaseInfo", null);
        if (empty(item.rating)) item.rating = meta.optString("imdbRating", null);
        if (empty(item.genre)) item.genre = join(meta.optJSONArray("genres"), 3);
        item.cast = join(meta.optJSONArray("cast"), 4);
        // a type the stream add-ons know ("anime" catalogues describe "series" and "movie")
        item.stype = meta.optString("type", item.stype);

        Map<Integer, List<Model.Item>> seasons = new java.util.TreeMap<>((x, y) -> x == 0 ? (y == 0 ? 0 : 1) : y == 0 ? -1 : Integer.compare(x, y));
        JSONArray videos = meta.optJSONArray("videos");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if (v == null || v.optString("id").isEmpty()) continue;
            Model.Item e = new Model.Item();
            e.kind = Model.EPISODE;
            e.sid = v.optString("id");
            e.id = e.sid;
            e.stype = item.stype;
            e.season = v.optInt("season", 1);
            e.episode = v.optInt("episode", v.optInt("number", i + 1));
            e.name = v.optString("title", v.optString("name", "Épisode " + e.episode));
            e.image = v.optString("thumbnail", null);
            e.plot = v.optString("overview", v.optString("description", null));
            e.seriesId = item.sid;
            e.seriesName = item.name;
            List<Model.Item> l = seasons.get(e.season);
            if (l == null) seasons.put(e.season, l = new ArrayList<>());
            l.add(e);
        }
        for (List<Model.Item> l : seasons.values()) l.sort((x, y) -> Integer.compare(x.episode, y.episode));
        return seasons;
    }

    // ---------------------------------------------------------------- streams and subtitles

    /** every add-on's playable streams for a film or an episode, asked all at once */
    List<Stream> streams(Model.Item item) {
        List<Stream> out = new ArrayList<>();
        List<Future<List<Stream>>> jobs = new ArrayList<>();
        for (Addon a : addons()) {
            if (!a.serves("stream", item.stype, item.sid)) continue;
            jobs.add(pool.submit(() -> fetchStreams(a, item)));
        }
        for (Future<List<Stream>> f : jobs) {
            try {
                out.addAll(f.get(25, TimeUnit.SECONDS));
            } catch (Exception e) {
                Log.w("PiTV", "streams", e);
            }
        }
        return out;
    }

    /**
     * asks every add-on at once and returns their names; each one's streams come to [done] on its
     * own as soon as it answers (null when it failed), so a slow add-on doesn't hold the others
     */
    List<String> streams(Model.Item item, java.util.function.BiConsumer<String, List<Stream>> done) {
        List<String> asked = new ArrayList<>();
        for (Addon a : addons()) {
            if (!a.serves("stream", item.stype, item.sid)) continue;
            asked.add(a.name);
            pool.execute(() -> {
                List<Stream> r;
                try {
                    r = fetchStreams(a, item);
                } catch (Exception e) {
                    Log.w("PiTV", "streams " + a.name, e);
                    r = null;
                }
                done.accept(a.name, r);
            });
        }
        return asked;
    }

    /** one add-on's direct streams for a film or an episode */
    private List<Stream> fetchStreams(Addon a, Model.Item item) throws Exception {
        String url = a.base + "/stream/" + item.stype + "/" + enc(item.sid) + ".json";
        List<Stream> l = new ArrayList<>();
        JSONArray s = new JSONObject(Net.text(url)).optJSONArray("streams");
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject o = s.optJSONObject(i);
            // direct links only: no torrents, no YouTube, no "open in a browser"
            if (o == null || !o.optString("url").startsWith("http")) continue;
            Stream st = new Stream();
            st.addon = a.name;
            st.name = o.optString("name", null);
            st.title = o.optString("title", o.optString("description", null));
            st.url = o.optString("url");
            JSONObject hints = o.optJSONObject("behaviorHints");
            st.bingeGroup = hints == null ? null : hints.optString("bingeGroup", null);
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                st.headers = new java.util.HashMap<>();
                for (java.util.Iterator<String> it = h.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = h.optString(k, null);
                    if (v != null) st.headers.put(k, v);
                }
            }
            l.add(st);
        }
        return l;
    }

    /** subtitles from the add-ons, one per language likely wanted here: {url, language} */
    List<String[]> subtitles(Model.Item item, String wanted) {
        List<String> langs = new ArrayList<>(List.of("fre", "fra", "fr", "eng", "en"));
        if (wanted != null && !wanted.isEmpty()) langs.add(0, wanted);
        List<String[]> out = new ArrayList<>();
        List<Future<?>> jobs = new ArrayList<>();
        for (Addon a : addons()) {
            if (!a.serves("subtitles", item.stype, item.sid)) continue;
            String url = a.base + "/subtitles/" + item.stype + "/" + enc(item.sid) + ".json";
            jobs.add(pool.submit(() -> {
                JSONArray s = new JSONObject(Net.text(url)).optJSONArray("subtitles");
                Map<String, Integer> perLang = new HashMap<>();
                for (int i = 0; s != null && i < s.length(); i++) {
                    JSONObject o = s.optJSONObject(i);
                    String lang = o == null ? "" : o.optString("lang").toLowerCase(Locale.ROOT);
                    if (o == null || !langs.contains(lang) || perLang.merge(lang, 1, Integer::sum) > 1) continue;
                    synchronized (out) {
                        out.add(new String[] {o.optString("url"), lang});
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** the response saved on disk, fetched again when older than a few hours (or forced) */
    private String cached(String url, boolean force) throws Exception {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".json");
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE;
        if (fresh && !force) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        try {
            String s = Net.text(url);
            new JSONObject(s);
            Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
            return s;
        } catch (Exception e) {
            // offline: the old copy is better than nothing
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            throw e;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static boolean has(JSONArray a, String s) {
        for (int i = 0; a != null && i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    private static boolean startsWithAny(String id, JSONArray prefixes) {
        for (int i = 0; i < prefixes.length(); i++) if (id.startsWith(prefixes.optString(i))) return true;
        return false;
    }

    private static String join(JSONArray a, int max) {
        if (a == null || a.length() == 0) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(max, a.length()); i++) b.append(i > 0 ? ", " : "").append(a.optString(i));
        return b.toString();
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("null");
    }
}
