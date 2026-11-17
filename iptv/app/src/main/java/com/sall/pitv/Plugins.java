package com.sall.pitv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Nuvio JavaScript scrapers: repositories of small scripts, each asked for the streams of a
 * film or an episode. The protocol is Nuvio's own: a manifest.json listing files, and a script
 * exporting getStreams(tmdbId, mediaType, season, episode).
 *
 * Nothing is bundled with the app: the lists are the user's, like the Stremio add-ons. Only
 * direct links are kept, so a script offering a torrent is ignored.
 */
final class Plugins {
    private static final String TAG = "PiTV";
    private static final String PREFS = "plugins";
    private static final long COLLECT_MS = 32_000L;
    /** scripts run at once: they mostly wait on the network */
    private static final int POOL = 24;
    private static final long STREAM_TTL = 60 * 60_000L;
    private static final long ERROR_TTL = 5 * 60_000L;
    /** scraper|title -> when its script last failed: a failed script is not asked again
     *  right away, or every lookup would wait for it */
    private final Map<String, Long> errors = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > 400;
        }
    };

    static final class Repo {
        String input, url, name;
        JSONObject manifest;
        boolean enabled = true;
    }

    static final class Scraper {
        String repoUrl, repoName, id, name, filename, version, description, logo;
        List<String> types = new ArrayList<>();
        boolean enabled = true;
        File file, cache;

        String key() {
            return repoUrl + "#" + id;
        }

        boolean ready() {
            return file != null && file.isFile();
        }

        String url() {
            try {
                return new URL(new URL(repoUrl), filename).toString();
            } catch (Exception e) {
                return filename;
            }
        }
    }

    private static Plugins instance;
    private final Context app;
    private final File dir, cacheDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL);
    private final ExecutorService downloads = Executors.newFixedThreadPool(4);
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private final List<Repo> repos = new ArrayList<>();
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    /** a scraper's answer for one title, kept for an hour: asking again is instant, and the
     *  links of most providers stay valid for several hours */
    private final Map<String, CachedStreams> results = new LinkedHashMap<String, CachedStreams>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedStreams> e) {
            return size() > 400;
        }
    };

    private static final class CachedStreams {
        final long at;
        final List<Addons.Stream> streams;

        CachedStreams(long at, List<Addons.Stream> streams) {
            this.at = at;
            this.streams = streams;
        }
    }
    /** set by the Plugins page: refresh when a download ends */
    volatile Runnable onChange;

    static synchronized Plugins get(Context c) {
        if (instance == null) instance = new Plugins(c.getApplicationContext());
        return instance;
    }

    private Plugins(Context c) {
        app = c;
        dir = new File(c.getFilesDir(), "plugins");
        dir.mkdirs();
        // compiled scripts only fit the engine that made them: one folder per install
        long stamp = 0;
        try {
            stamp = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        cacheDir = new File(dir, "cache-" + stamp);
        File[] old = dir.listFiles();
        for (File f : old == null ? new File[0] : old) {
            if (f.isDirectory() && f.getName().startsWith("cache") && !f.equals(cacheDir)) {
                File[] in = f.listFiles();
                for (File x : in == null ? new File[0] : in) x.delete();
                f.delete();
            }
        }
        cacheDir.mkdirs();
        PluginRuntime.init(c.getAssets());
        load();
        loadStats();
        downloadMissing();
    }

    synchronized List<Scraper> scrapersOf(Repo repo) {
        return parse(repo);
    }

    boolean failed(Scraper s) {
        return failed.contains(s.key());
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- repositories

    synchronized List<Repo> repos() {
        return new ArrayList<>(repos);
    }

    synchronized List<Scraper> scrapers() {
        List<Scraper> out = new ArrayList<>();
        for (Repo r : repos) if (r.enabled) out.addAll(parse(r));
        return out;
    }

    /** adds or updates a repository; throws with a message the page can show */
    synchronized String add(String input) throws Exception {
        String u = normalize(input);
        if (u == null) throw new IllegalArgumentException("Lien invalide");
        Fetched f = fetch(u);
        JSONObject manifest;
        try {
            manifest = new JSONObject(f.body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Ce lien n'est pas un manifest Nuvio");
        }
        List<Scraper> found = parse(manifest, f.url, "");
        if (found.isEmpty()) throw new IllegalArgumentException("Aucun scraper dans ce manifest");
        String name = manifest.optString("name", host(f.url));

        Repo repo = null;
        for (Repo r : repos) if (r.url.equals(f.url)) {
            repo = r;
            break;
        }
        boolean added = repo == null;
        if (added) repos.add(repo = new Repo());
        repo.input = input;
        repo.url = f.url;
        repo.name = name;
        repo.manifest = manifest;
        save();
        invalidate(repo);
        download(repo);
        Log.i(TAG, "plugin repo " + name + " " + found.size() + " scrapers");
        return name;
    }

    synchronized void remove(Repo repo) {
        repos.remove(repo);
        for (Scraper s : parse(repo)) {
            if (s.file != null) s.file.delete();
            if (s.cache != null) s.cache.delete();
            forget(s);
        }
        save();
    }

    /** switches a whole repository's scrapers on or off at once */
    synchronized void setEnabled(Repo r, boolean on) {
        r.enabled = on;
        save();
    }

    /** "disabled" holds the scripts switched off, "forced" those a manifest ships off but switched on */
    synchronized void setEnabled(Scraper s, boolean on) {
        Set<String> off = keys("disabled"), forced = keys("forced");
        if (on) {
            off.remove(s.key());
            forced.add(s.key());
        } else {
            off.add(s.key());
            forced.remove(s.key());
        }
        prefs().edit().putString("disabled", new JSONArray(off).toString())
                .putString("forced", new JSONArray(forced).toString()).apply();
    }

    private Set<String> keys(String pref) {
        Set<String> set = new HashSet<>();
        try {
            JSONArray a = new JSONArray(prefs().getString(pref, "[]"));
            for (int i = 0; i < a.length(); i++) set.add(a.optString(i));
        } catch (Exception ignored) {
        }
        return set;
    }

    /**
     * re-reads a repository's manifest and its changed scripts, and gives all of its scripts a
     * fresh start: the paused ones run again, and recent failures and empty answers are forgotten
     */
    void refresh(Repo repo) {
        String prefix = repo.url + "#";
        synchronized (stats) {
            stats.keySet().removeIf(k -> k.startsWith(prefix));
            lastTitle.keySet().removeIf(k -> k.startsWith(prefix));
        }
        saveStats();
        synchronized (errors) {
            errors.keySet().removeIf(k -> k.startsWith(prefix));
        }
        synchronized (results) {
            results.entrySet().removeIf(e -> e.getKey().startsWith(prefix) && e.getValue().streams.isEmpty());
        }
        fire();
        downloads.submit(() -> {
            try {
                Fetched f = fetch(repo.url);
                JSONObject manifest = new JSONObject(f.body);
                if (parse(manifest, f.url, "").isEmpty()) return;
                synchronized (this) {
                    repo.manifest = manifest;
                    repo.name = manifest.optString("name", repo.name);
                    save();
                }
                invalidate(repo);
                downloadNow(repo);
                fire();
            } catch (Exception e) {
                Log.w(TAG, "plugin refresh " + repo.url, e);
            }
        });
    }

    private void download(Repo repo) {
        downloads.submit(() -> {
            downloadNow(repo);
            fire();
        });
    }

    private static String resultKey(Scraper s, String type, String tmdb, int season, int episode) {
        return s.key() + "|" + type + "/" + tmdb + "/" + season + "/" + episode;
    }

    /** drops a scraper's cached answers, after its script changed or it was removed */
    private void forget(Scraper s) {
        String prefix = s.key() + "|";
        synchronized (results) {
            results.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /** a new manifest version: the scripts are fetched again */
    private void invalidate(Repo repo) {
        for (Scraper s : parse(repo)) {
            if (s.file != null) s.file.delete();
            if (s.cache != null) s.cache.delete();
            forget(s);
        }
    }

    private void downloadNow(Repo repo) {
        for (Scraper s : parse(repo)) {
            if (!s.ready()) {
                try {
                    Net.toFile(s.url(), s.file);
                    s.cache.delete();
                    failed.remove(s.key());
                } catch (Exception e) {
                    Log.w(TAG, "plugin download " + s.id, e);
                    failed.add(s.key());
                }
            }
        }
    }

    /** files missing from a previous run: download them in the background */
    private void downloadMissing() {
        for (Repo r : repos()) {
            boolean missing = false;
            for (Scraper s : parse(r)) if (!s.ready()) missing = true;
            if (missing) download(r);
        }
    }

    private void fire() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    // ---------------------------------------------------------------- streams

    /** links as they come: each script's, the moment it answers (from a pool thread) */
    interface Partial {
        void found(List<Addons.Stream> streams);
    }

    /** a script's run for one title, and the searches waiting on it */
    private static final class Running {
        volatile Future<List<Addons.Stream>> future;
        final List<Partial> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    private final Map<String, Running> inflight = new HashMap<>();

    /** runs [streams] away from the caller, so the add-ons are asked at the same time */
    Future<List<Addons.Stream>> future(Model.Item item) {
        return future(item, null);
    }

    Future<List<Addons.Stream>> future(Model.Item item, Partial partial) {
        return callers.submit(() -> streams(item, partial));
    }

    /** every enabled script's direct streams for a film or an episode */
    List<Addons.Stream> streams(Model.Item item, Partial partial) {
        List<Scraper> enabled = new ArrayList<>();
        for (Scraper s : scrapers()) if (s.enabled && s.ready() && !paused(s)) enabled.add(s);
        if (enabled.isEmpty()) return new ArrayList<>();
        // the pool takes them in order: the scripts that found links before, fastest first,
        // so the first links show in a second or two; then the untried, then the rest
        synchronized (stats) {
            enabled.sort(Comparator.comparingLong(this::rank));
        }

        String tmdb = Tmdb.resolve(item);
        if (tmdb == null) {
            Log.i(TAG, "plugins: no TMDB id for " + item.name);
            return new ArrayList<>();
        }
        String type = item.kind == Model.MOVIE ? "movie" : "tv";
        int season = item.kind == Model.EPISODE ? item.season : 0;
        int episode = item.kind == Model.EPISODE ? item.episode : 0;
        String title = type + "/" + tmdb + "/" + season + "/" + episode;
        List<Future<List<Addons.Stream>>> jobs = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Addons.Stream> cached = new ArrayList<>();
        int skips = 0;
        long now = System.currentTimeMillis();
        for (Scraper s : enabled) {
            if (!supports(s, type)) continue;
            String rk = resultKey(s, type, tmdb, season, episode);
            synchronized (results) {
                CachedStreams c = results.get(rk);
                if (c != null && now - c.at < STREAM_TTL) {
                    cached.addAll(c.streams);
                    continue;
                }
            }
            synchronized (errors) {
                Long e = errors.get(rk);
                if (e != null && now - e < ERROR_TTL) {
                    skips++;
                    continue;
                }
            }
            // already running for an earlier search (the page's look-ahead): this one waits for
            // the same run instead of starting the script again
            synchronized (inflight) {
                Running r = inflight.get(rk);
                if (r != null) {
                    if (partial != null) r.listeners.add(partial);
                    jobs.add(r.future);
                    keys.add(rk);
                    continue;
                }
                Running run = new Running();
                if (partial != null) run.listeners.add(partial);
                inflight.put(rk, run);
                run.future = pool.submit(() -> {
                    // null on error: the script is not asked again for a few minutes,
                    // or every lookup would wait for it; an empty answer is real and cached
                    long t0 = System.currentTimeMillis();
                    List<Addons.Stream> l = null;
                    try {
                        l = runOne(s, tmdb, type, season, episode);
                    } finally {
                        record(s, title, l, System.currentTimeMillis() - t0);
                        Log.i(TAG, "plugin done " + s.repoName + "/" + s.id + ": "
                                + (l == null ? "error" : l.size() + " links") + " in " + (System.currentTimeMillis() - t0) + " ms");
                        if (l != null) synchronized (results) {
                            results.put(rk, new CachedStreams(System.currentTimeMillis(), new ArrayList<>(l)));
                        } else synchronized (errors) {
                            errors.put(rk, System.currentTimeMillis());
                        }
                        synchronized (inflight) {
                            inflight.remove(rk);
                        }
                    }
                    if (l == null) return new ArrayList<Addons.Stream>();
                    if (!l.isEmpty()) for (Partial x : run.listeners) x.found(new ArrayList<>(l));
                    return l;
                });
                jobs.add(run.future);
                keys.add(rk);
            }
        }
        Log.i(TAG, "plugins: " + cached.size() + " cached, " + jobs.size() + " running, " + skips + " failed recently");
        if (partial != null && !cached.isEmpty()) partial.found(new ArrayList<>(cached));
        long deadline = System.currentTimeMillis() + COLLECT_MS;
        List<Addons.Stream> out = new ArrayList<>(cached);
        for (int i = 0; i < jobs.size(); i++) {
            Future<List<Addons.Stream>> f = jobs.get(i);
            long left = deadline - System.currentTimeMillis();
            try {
                out.addAll(f.get(Math.max(left, 1), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                // too slow: it goes on in the background (its answer is cached when it
                // comes) but the next lookups skip it instead of waiting for it again
                synchronized (errors) {
                    errors.put(keys.get(i), System.currentTimeMillis());
                }
            } catch (Exception ignored) {
            }
        }
        out.sort(Comparator.comparingInt((Addons.Stream s) -> quality(s.name + " " + s.title)).reversed());
        Log.i(TAG, "plugins: " + out.size() + " streams for " + item.name + " (" + tmdb + ")");
        saveStats();
        return out;
    }

    // ---------------------------------------------------------------- order of the queue

    /** per script: [runs, runs with links, average ms, titles asked], kept across starts */
    private final Map<String, long[]> stats = new HashMap<>();
    /** per script, the title it was last asked for: asking again for the same one isn't a new title */
    private final Map<String, String> lastTitle = new HashMap<>();
    /** a script that found nothing for this many different titles is paused */
    static final int PAUSE_AFTER = 8;

    /** found no link for the last [PAUSE_AFTER] titles: skipped until switched on again */
    boolean paused(Scraper s) {
        synchronized (stats) {
            long[] st = stats.get(s.key());
            return st != null && st[1] == 0 && st[3] >= PAUSE_AFTER;
        }
    }

    /** switched on again by hand: a fresh start */
    void unpause(Scraper s) {
        synchronized (stats) {
            stats.remove(s.key());
        }
        saveStats();
        if (onChange != null) onChange.run();
    }

    private long rank(Scraper s) {
        long[] st = stats.get(s.key());
        if (st == null) return 1_000_000L;                       // untried
        if (st[1] > 0) return st[2];                             // has found links: by speed
        return 2_000_000L + st[2];                               // never found any
    }

    /** a run's outcome; a failure (null) counts as a run without links */
    private void record(Scraper s, String title, List<Addons.Stream> l, long ms) {
        synchronized (stats) {
            long[] st = stats.get(s.key());
            if (st == null) stats.put(s.key(), st = new long[]{0, 0, ms, 0});
            st[0]++;
            if (l != null && !l.isEmpty()) st[1]++;
            st[2] = (st[2] * 3 + ms) / 4;
            if (!title.equals(lastTitle.put(s.key(), title))) st[3]++;
        }
    }

    private void saveStats() {
        JSONObject o = new JSONObject();
        synchronized (stats) {
            try {
                for (Map.Entry<String, long[]> e : stats.entrySet()) {
                    long[] st = e.getValue();
                    o.put(e.getKey(), new JSONArray().put(st[0]).put(st[1]).put(st[2]).put(st[3]));
                }
            } catch (Exception ignored) {
            }
        }
        prefs().edit().putString("stats", o.toString()).apply();
    }

    private void loadStats() {
        try {
            JSONObject o = new JSONObject(prefs().getString("stats", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = o.getJSONArray(k);
                // the titles count came later: older entries start it at 0
                stats.put(k, new long[]{a.getLong(0), a.getLong(1), a.getLong(2), a.optLong(3, 0)});
            }
        } catch (Exception ignored) {
        }
    }

    /** one scraper's links for one title; null means the script failed and the answer is not cached */
    private List<Addons.Stream> runOne(Scraper s, String tmdb, String type, int season, int episode) {
        List<Addons.Stream> out = new ArrayList<>();
        try {
            if (s.cache.isFile() && s.cache.lastModified() < s.file.lastModified()) s.cache.delete();
            String json = PluginRuntime.run(s.file, s.cache, s.id, tmdb, type, season, episode, settings(s));
            JSONArray arr;
            try {
                arr = new JSONArray(json);
            } catch (Exception e) {
                JSONObject o = new JSONObject(json);
                Log.w(TAG, "plugin " + s.id + ": " + o.optString("error"));
                return null;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                // direct links (or a playlist the script put together itself), never a torrent
                if (!Net.playable(url)) continue;
                Addons.Stream st = new Addons.Stream();
                st.addon = s.name;
                st.name = first(o.optString("name", null), o.optString("quality", null));
                st.title = title(o);
                st.url = url;
                st.bingeGroup = "plugin:" + s.key();
                JSONObject h = o.optJSONObject("headers");
                if (h != null) {
                    st.headers = new HashMap<>();
                    for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                        String k = it.next();
                        Object v = h.opt(k);
                        if (v != null && v != JSONObject.NULL) st.headers.put(k, String.valueOf(v));
                    }
                }
                out.add(st);
            }
        } catch (Exception e) {
            Log.w(TAG, "plugin " + s.id, e);
            return null;
        }
        return out;
    }

    private String settings(Scraper s) {
        return prefs().getString("settings." + s.key(), "{}");
    }

    private static String title(JSONObject o) {
        StringBuilder b = new StringBuilder(o.optString("title", o.optString("description", "")));
        String q = o.optString("quality", "");
        if (!q.isEmpty() && b.indexOf(q) < 0) b.append(b.length() > 0 ? " · " : "").append(q);
        String lang = o.optString("language", o.optString("lang", ""));
        if (!lang.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(lang);
        String size = o.optString("size", "");
        if (!size.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(size);
        return b.toString();
    }

    private static String first(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    static int quality(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (t.contains("2160") || t.contains("4k")) return 5;
        if (t.contains("1080")) return 4;
        if (t.contains("720")) return 3;
        if (t.contains("480")) return 2;
        if (t.contains("360")) return 1;
        return 0;
    }

    private static final java.util.regex.Pattern VOST = java.util.regex.Pattern.compile("\\b(vostfr|vost|subfrench|sub ?fr|st ?fr|sous[- ]titr)"),
            MULTI = java.util.regex.Pattern.compile("\\b(multi|dual)\\b"),
            VF = java.util.regex.Pattern.compile("\\b(vff?|vfq|vfi|vf2|truefrench|french|fran[cç]ais|fr)\\b"),
            VO = java.util.regex.Pattern.compile("\\b(vo|eng|english|anglais)\\b"),
            ES = java.util.regex.Pattern.compile("\\b(esp|spanish|espa[nñ]ol|castellano|latino)\\b"),
            IT = java.util.regex.Pattern.compile("\\b(ita|italian|italiano)\\b"),
            DE = java.util.regex.Pattern.compile("\\b(ger|german|deutsch)\\b");

    /** the language a link's text shows, from its tags or flag emoji; "" when it doesn't say */
    static String language(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT).replace('_', ' ').replace('.', ' ');
        if (VOST.matcher(t).find()) return "VOSTFR";
        boolean fr = VF.matcher(t).find() || t.contains("\uD83C\uDDEB\uD83C\uDDF7");
        boolean en = VO.matcher(t).find() || t.contains("\uD83C\uDDEC\uD83C\uDDE7") || t.contains("\uD83C\uDDFA\uD83C\uDDF8");
        if (MULTI.matcher(t).find() || (fr && en)) return "MULTI";
        if (fr) return "VF";
        if (en) return "VO";
        if (ES.matcher(t).find() || t.contains("\uD83C\uDDEA\uD83C\uDDF8") || t.contains("\uD83C\uDDF2\uD83C\uDDFD")) return "Espagnol";
        if (IT.matcher(t).find() || t.contains("\uD83C\uDDEE\uD83C\uDDF9")) return "Italien";
        if (DE.matcher(t).find() || t.contains("\uD83C\uDDE9\uD83C\uDDEA")) return "Allemand";
        return "";
    }

    private static boolean supports(Scraper s, String type) {
        if (s.types.isEmpty()) return true;
        for (String t : s.types) {
            String x = t.toLowerCase(Locale.ROOT);
            if (x.equals(type)) return true;
            if (type.equals("tv") && (x.equals("series") || x.equals("anime") || x.equals("show"))) return true;
            if (type.equals("movie") && x.equals("film")) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- manifests

    private void load() {
        String saved = prefs().getString("repos", null);
        if (saved == null) return;
        try {
            JSONArray a = new JSONArray(saved);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Repo r = new Repo();
                r.input = o.optString("input", "");
                r.url = o.optString("url", "");
                r.name = o.optString("name", "");
                r.manifest = new JSONObject(o.optString("manifest", "{}"));
                r.enabled = o.optBoolean("enabled", true);
                if (!r.url.isEmpty() && r.manifest.length() > 0) repos.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "plugin repos", e);
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (Repo r : repos) {
            JSONObject o = new JSONObject();
            try {
                o.put("input", r.input);
                o.put("url", r.url);
                o.put("name", r.name);
                o.put("manifest", r.manifest == null ? "{}" : r.manifest.toString());
                o.put("enabled", r.enabled);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs().edit().putString("repos", a.toString()).apply();
    }

    private List<Scraper> parse(Repo repo) {
        if (repo.manifest == null) return new ArrayList<>();
        return parse(repo.manifest, repo.url, repo.name);
    }

    /** Nuvio's manifest, and the older single-script form */
    private List<Scraper> parse(JSONObject manifest, String manifestUrl, String repoName) {
        List<Scraper> out = new ArrayList<>();
        JSONArray arr = manifest.optJSONArray("scrapers");
        if (arr == null && !manifest.optString("filename").isEmpty()) {
            arr = new JSONArray();
            arr.put(manifest);
        }
        Set<String> disabled = keys("disabled"), forced = keys("forced");
        String name = repoName == null || repoName.isEmpty() ? manifest.optString("name", host(manifestUrl)) : repoName;

        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String filename = o.optString("filename", "");
            String id = o.optString("id", filename.replaceAll("^.*/|\\.js$", ""));
            if (filename.isEmpty() || id.isEmpty()) continue;
            Scraper s = new Scraper();
            s.repoUrl = manifestUrl;
            s.repoName = name;
            s.id = id;
            s.name = o.optString("name", id);
            s.filename = filename;
            s.version = o.optString("version", "");
            s.description = o.optString("description", "");
            s.logo = o.optString("logo", "");
            JSONArray types = o.optJSONArray("supportedTypes");
            for (int k = 0; types != null && k < types.length(); k++) s.types.add(types.optString(k));
            s.enabled = forced.contains(s.key()) || o.optBoolean("enabled", true) && !disabled.contains(s.key());
            String safe = fileBase(s);
            s.file = new File(dir, safe + ".js");
            s.cache = new File(cacheDir, safe + ".bc");
            out.add(s);
        }
        return out;
    }

    private static String fileBase(Scraper s) {
        String safe = s.id.replaceAll("[^A-Za-z0-9._-]", "_");
        return hash(s.repoUrl).substring(0, 8) + "-" + safe;
    }

    // ---------------------------------------------------------------- network and links

    private static final class Fetched {
        String url, body;
    }

    /** follows redirects so relative script names resolve against the final manifest URL */
    private static Fetched fetch(String url) throws Exception {
        Fetched f = new Fetched();
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", Source.DEFAULT_AGENT);
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new Exception("HTTP " + code);
            }
            f.url = url;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Net.copy(Net.gunzip(c.getInputStream()), out);
            f.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            c.disconnect();
            return f;
        }
        throw new Exception("trop de redirections");
    }

    static String normalize(String u) {
        if (u == null) return null;
        u = u.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("stremio://")) u = "https://" + u.substring(10);
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        u = u.replace(" ", "%20");
        if (u.matches("(?i).*(manifest\\.json|\\.json)(\\?.*)?$")) return u;
        String host = host(u);
        if (host.endsWith("cutt.ly") || host.endsWith("bit.ly") || host.endsWith("tinyurl.com")) return u;
        return u.replaceFirst("/+$", "") + "/manifest.json";
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte x : b) out.append(String.format("%02x", x));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
