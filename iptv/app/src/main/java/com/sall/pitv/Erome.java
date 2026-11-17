package com.sall.pitv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erome as a built-in add-on: Pi TV asks builtin://erome/… as it would any Stremio add-on, and
 * this answers from Erome's pages, read here on the Pi. Its "hot" and "new" albums and its
 * search are the catalogues; an album's clips (720p H.264 MP4) are its links. Erome's video
 * server wants an erome.com Referer, which each link carries.
 */
final class Erome {
    static final String URL = Builtin.SCHEME + "erome/manifest.json";
    private static final String SITE = "https://www.erome.com";
    private static final String PREFIX = "erome:";
    /** albums per Erome page */
    private static final int PAGE = 36;

    /**
     * titles that plainly say minors or no consent are never listed nor played; only the
     * unambiguous words, so role-play and ordinary amateur titles aren't caught
     */
    static final Pattern BLOCKED = Pattern.compile("\\b(underage|under\\s*age|preteen|pre-teen|child|children"
            + "|lolita|loli|jailbait|middle\\s*school|1[0-7]\\s*(?:yo|y\\.?o\\.?|yr|yrs|years?\\s*old)"
            + "|rape|raped|drugged|unconscious|passed\\s*out)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ROUTE = Pattern.compile("^/(catalog|meta|stream)/([^/]+)/([^/]+?)(?:/([^/]+))?\\.json$");
    private static final Pattern ALBUM_LINK = Pattern.compile("href=\"https://www\\.erome\\.com/a/([A-Za-z0-9]+)\"");
    private static final Pattern THUMB = Pattern.compile("https://s\\d+\\.erome\\.com/[^\"'\\s]+/thumbs/[^\"'\\s]+\\.jpg");
    private static final Pattern SOURCE = Pattern.compile("<source src=\"([^\"]+\\.mp4[^\"]*)\"[^>]*>");

    /** /path → its JSON answer */
    static String answer(String path) throws IOException {
        try {
            if (path.equals("/manifest.json") || path.equals("/")) return manifest().toString();
            Matcher m = ROUTE.matcher(path);
            if (!m.matches()) throw new IOException("not found: " + path);
            String resource = m.group(1), id = URLDecoder.decode(m.group(3), "UTF-8");
            Map<String, String> extra = new HashMap<>();
            if (m.group(4) != null) {
                for (String kv : m.group(4).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) extra.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            }
            if (resource.equals("catalog")) return new JSONObject().put("metas", catalog(id, extra)).toString();
            String album = id.startsWith(PREFIX) ? id.substring(PREFIX.length()) : "";
            if (!album.matches("[A-Za-z0-9]+")) {
                return resource.equals("meta") ? "{\"meta\":null}" : "{\"streams\":[]}";
            }
            if (resource.equals("meta")) {
                JSONObject meta = meta(album);
                return new JSONObject().put("meta", meta == null ? JSONObject.NULL : meta).toString();
            }
            return new JSONObject().put("streams", streams(album)).toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static JSONObject manifest() throws JSONException {
        JSONArray catalogs = new JSONArray()
                .put(new JSONObject().put("type", "movie").put("id", "erome-hot").put("name", "Erome · Populaires")
                        .put("extra", new JSONArray().put(new JSONObject().put("name", "search")).put(new JSONObject().put("name", "skip"))))
                .put(new JSONObject().put("type", "movie").put("id", "erome-new").put("name", "Erome · Nouveaux")
                        .put("extra", new JSONArray().put(new JSONObject().put("name", "skip"))));
        return new JSONObject()
                .put("id", "com.sall.erome")
                .put("version", "1.1.0")
                .put("name", "Erome")
                .put("description", "Intégré à Pi TV : albums Erome populaires, nouveaux et recherche. Clips 720p H.264.")
                .put("resources", new JSONArray().put("catalog").put("meta").put("stream"))
                .put("types", new JSONArray().put("movie"))
                .put("idPrefixes", new JSONArray().put(PREFIX))
                .put("catalogs", catalogs)
                .put("behaviorHints", new JSONObject().put("adult", true));
    }

    // ---------------------------------------------------------------- pages, cached a little

    private static final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > 120;
        }
    };

    private static String page(String url, long ttlMs) throws IOException {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null && (long) hit[0] > System.currentTimeMillis()) return (String) hit[1];
        }
        String body = Net.text(url);
        synchronized (cache) {
            cache.put(url, new Object[]{System.currentTimeMillis() + ttlMs, body});
        }
        return body;
    }

    // ---------------------------------------------------------------- listings

    private static JSONArray catalog(String id, Map<String, String> extra) throws IOException, JSONException {
        int skip = 0;
        try {
            skip = Integer.parseInt(extra.getOrDefault("skip", "0"));
        } catch (NumberFormatException ignored) {
        }
        int p = skip / PAGE + 1;
        String q = extra.get("search"), url;
        if (q != null && !q.trim().isEmpty()) url = SITE + "/search?q=" + URLEncoder.encode(q.trim(), "UTF-8") + "&page=" + p;
        else if (id.equals("erome-new")) url = SITE + "/explore/new?page=" + p;
        else url = SITE + "/explore?page=" + p;
        return albums(page(url, (q != null ? 10 : 5) * 60_000L));
    }

    /** a listing page's albums that hold at least one video */
    private static JSONArray albums(String html) throws JSONException {
        JSONArray out = new JSONArray();
        String[] blocks = html.split("class=\"album-thumbnail-container\"");
        for (int i = 1; i < blocks.length; i++) {
            String b = blocks[i];
            String id = first(ALBUM_LINK, b);
            int videos = count(b, "album-videos");
            if (id == null || videos == 0) continue;
            String title = decode(first(Pattern.compile("class=\"album-title\"[^>]*>([^<]*)"), b));
            String user = decode(first(Pattern.compile("class=\"album-user\"[^>]*>([^<]*)"), b));
            if (BLOCKED.matcher(title).find()) continue;
            Matcher t = THUMB.matcher(b);
            String thumb = t.find() ? t.group() : null;
            int images = count(b, "album-images");
            List<String> about = new ArrayList<>();
            if (!user.isEmpty()) about.add("Par " + user);
            about.add(videos + " vidéo" + (videos > 1 ? "s" : ""));
            if (images > 0) about.add(images + " photo" + (images > 1 ? "s" : ""));
            out.put(new JSONObject()
                    .put("id", PREFIX + id)
                    .put("type", "movie")
                    .put("name", title.isEmpty() ? "Sans titre" : title)
                    .put("poster", thumb)
                    .put("posterShape", "square")
                    .put("background", thumb)
                    .put("description", String.join(" · ", about)));
        }
        return out;
    }

    /** the number after a card's camera or video icon */
    private static int count(String block, String cls) {
        Matcher m = Pattern.compile("class=\"" + cls + "\"[^>]*>(?:<svg[\\s\\S]*?</svg>)?\\s*(\\d+)").matcher(block);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ---------------------------------------------------------------- one album

    private static final class Album {
        String title = "", user = "", poster;
        final List<String[]> clips = new ArrayList<>();
        boolean blocked;
    }

    private static Album album(String id) throws IOException {
        String html = page(SITE + "/a/" + id, 30 * 60_000L);
        Album a = new Album();
        a.title = decode(first(Pattern.compile("class=\"album-title-page\"[^>]*>([^<]*)"), html));
        if (a.title.isEmpty()) a.title = decode(first(Pattern.compile("og:title\" content=\"([^\"]*)\""), html));
        a.user = decode(first(Pattern.compile("id=\"user_name\"[^>]*>([^<]*)"), html));
        if (a.user.isEmpty()) a.user = decode(first(Pattern.compile("class=\"username\"[^>]*>([^<]*)"), html));
        a.poster = first(Pattern.compile("og:image\" content=\"([^\"]*)\""), html);
        // the album's own clips: its <source> tags, each once and in order
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = SOURCE.matcher(html);
        while (m.find()) {
            if (!seen.add(m.group(1))) continue;
            String res = first(Pattern.compile("res=['\"]?(\\d+)"), m.group());
            if (res == null) res = first(Pattern.compile("_(\\d{3,4})p\\.mp4"), m.group(1));
            a.clips.add(new String[]{m.group(1), res});
        }
        a.blocked = BLOCKED.matcher(a.title).find();
        return a;
    }

    private static JSONObject meta(String id) throws IOException, JSONException {
        Album a = album(id);
        if (a.blocked) return null;
        int n = a.clips.size();
        return new JSONObject()
                .put("id", PREFIX + id)
                .put("type", "movie")
                .put("name", a.title.isEmpty() ? "Sans titre" : a.title)
                .put("poster", a.poster)
                .put("posterShape", "square")
                .put("background", a.poster)
                .put("description", (a.user.isEmpty() ? "" : "Par " + a.user + " · ") + n + " vidéo" + (n > 1 ? "s" : ""));
    }

    private static JSONArray streams(String id) throws IOException, JSONException {
        Album a = album(id);
        JSONArray out = new JSONArray();
        if (a.blocked) return out;
        JSONObject headers = new JSONObject().put("Referer", SITE + "/");
        int n = a.clips.size();
        for (int i = 0; i < n; i++) {
            String[] c = a.clips.get(i);
            out.put(new JSONObject()
                    .put("name", "Erome\n" + (c[1] != null ? c[1] + "p" : "MP4"))
                    .put("title", (a.title.isEmpty() ? "Clip" : a.title) + (n > 1 ? " · clip " + (i + 1) + "/" + n : ""))
                    .put("url", c[0])
                    .put("headers", headers)
                    .put("behaviorHints", new JSONObject().put("bingeGroup", "erome-" + id)));
        }
        return out;
    }

    // ---------------------------------------------------------------- text

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? (m.groupCount() > 0 ? m.group(1) : m.group()) : null;
    }

    private static String decode(String s) {
        if (s == null) return "";
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(m.group(1))))));
        m.appendTail(b);
        return b.toString().trim();
    }

    private Erome() {
    }
}
