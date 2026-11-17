package com.sall.pitv.addons;

import com.sall.pitv.R;
import com.sall.pitv.ui.*;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tastyblacks as a built-in add-on. It hosts no video: each of its cards is a /go?vx=… link to
 * a video on another tube site. So the catalogues are its category pages, keeping only the cards
 * from sites whose video file can be found in their page (most use the KVS player, which writes
 * the file's address in plain text); the links come from following the card to that page.
 * Its search sits behind a Cloudflare check, so there is none here.
 */
public final class Tasty {
    public static final String URL = Builtin.SCHEME + "tasty/manifest.json";
    private static final String SITE = "https://tastyblacks.com";
    private static final String PREFIX = "tasty:";

    /** the catalogues: id, row name, category path */
    private static final String[][] CATALOGS = {
            {"tasty-new", "Tastyblacks · Récentes", "/en/0/all/"},
            {"tasty-french", "Tastyblacks · Français", "/en/89/french/"},
            {"tasty-french-amateur", "Tastyblacks · Amateur français", "/en/136612/french_amateur/"},
            {"tasty-ebony", "Tastyblacks · Ebony", "/en/13/ebony/"},
            {"tasty-big-ass", "Tastyblacks · Big Ass", "/en/209/big_ass/"},
            {"tasty-amateur", "Tastyblacks · Amateur", "/en/21/amateur/"},
    };

    /** the sites (as Tastyblacks names them) whose page gives the video file; tried in Nov 2026 */
    private static final Set<String> PLAYABLE = new HashSet<>(Arrays.asList(
            "PornDRCom", "PHT", "xTits", "BigTitsLust", "UiPornCom", "XXXVogueNet", "XFeedCom", "BP", "HDSex",
            "Lesbian8", "SortPorn", "MatureTubeHere", "SexOceanNet", "FreePorn8", "Ebony8", "XoZilla", "PWT"));

    private static final Pattern ROUTE = Pattern.compile("^/(catalog|meta|stream)/([^/]+)/([^/]+?)(?:/([^/]+))?\\.json$");
    private static final Pattern CARD = Pattern.compile("<li>\\s*<a href=\"/go\\?vx=([^\"]+)\"[^>]*>\\s*(<img[^>]*>)[\\s\\S]*?<h3 class=\"title\">([^<]*)</h3>[\\s\\S]*?<span class=\"dur\">([^<]*)</span>");

    static String answer(String path) throws IOException {
        try {
            if (path.equals("/manifest.json") || path.equals("/")) return manifest().toString();
            Matcher m = ROUTE.matcher(path);
            if (!m.matches()) throw new IOException("not found: " + path);
            String resource = m.group(1), id = URLDecoder.decode(m.group(3), "UTF-8");
            int skip = 0;
            if (m.group(4) != null) {
                Matcher s = Pattern.compile("(?:^|&)skip=(\\d+)").matcher(m.group(4));
                if (s.find()) skip = Integer.parseInt(s.group(1));
            }
            if (resource.equals("catalog")) return new JSONObject().put("metas", catalog(id, skip)).toString();
            String vx = id.startsWith(PREFIX) ? id.substring(PREFIX.length()) : "";
            if (!vx.matches("[A-Za-z0-9_-]+")) return resource.equals("meta") ? "{\"meta\":null}" : "{\"streams\":[]}";
            if (resource.equals("meta")) {
                JSONObject meta = meta(vx);
                return new JSONObject().put("meta", meta == null ? JSONObject.NULL : meta).toString();
            }
            return new JSONObject().put("streams", streams(vx)).toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static JSONObject manifest() throws JSONException {
        JSONArray catalogs = new JSONArray();
        for (String[] c : CATALOGS) {
            catalogs.put(new JSONObject().put("type", "movie").put("id", c[0]).put("name", c[1])
                    .put("extra", new JSONArray().put(new JSONObject().put("name", "skip"))));
        }
        return new JSONObject()
                .put("id", "com.sall.tastyblacks")
                .put("version", "1.0.0")
                .put("name", "Tastyblacks")
                .put("description", "Intégré à Pi TV : catégories Tastyblacks, vidéos des sites qui se lisent ici.")
                .put("resources", new JSONArray().put("catalog").put("meta").put("stream"))
                .put("types", new JSONArray().put("movie"))
                .put("idPrefixes", new JSONArray().put(PREFIX))
                .put("catalogs", catalogs)
                .put("behaviorHints", new JSONObject().put("adult", true));
    }

    // ---------------------------------------------------------------- listings

    /** what each card said, for its details page */
    private static final Map<String, JSONObject> known = new LinkedHashMap<String, JSONObject>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> e) {
            return size() > 3000;
        }
    };
    /**
     * per catalogue, how many titles its pages gave in all after page 1, 2, …: the player asks
     * for "the titles after the first N", but each page keeps a different number of cards
     */
    private static final Map<String, List<Integer>> totals = new HashMap<>();
    private static final Map<String, Object[]> pages = new HashMap<>();

    private static JSONArray catalog(String id, int skip) throws IOException, JSONException {
        String path = null;
        for (String[] c : CATALOGS) if (c[0].equals(id)) path = c[2];
        if (path == null) return new JSONArray();
        int page;
        synchronized (totals) {
            List<Integer> t = totals.computeIfAbsent(id, k -> new ArrayList<>());
            if (skip == 0) page = 1;
            else {
                int i = t.indexOf(skip);
                page = i >= 0 ? i + 2 : skip / 60 + 1;
            }
        }
        String url = SITE + path + (page > 1 ? page + "/" : "");
        String html;
        synchronized (pages) {
            Object[] hit = pages.get(url);
            html = hit != null && (long) hit[0] > System.currentTimeMillis() ? (String) hit[1] : null;
        }
        if (html == null) {
            html = Net.page(url, SITE + "/en/")[1];
            synchronized (pages) {
                if (pages.size() > 60) pages.clear();
                pages.put(url, new Object[]{System.currentTimeMillis() + 10 * 60_000L, html});
            }
        }
        JSONArray out = cards(html);
        synchronized (totals) {
            List<Integer> t = totals.get(id);
            while (t.size() < page - 1) t.add(t.isEmpty() ? 0 : t.get(t.size() - 1));
            int total = (page == 1 ? 0 : t.get(page - 2)) + out.length();
            if (t.size() >= page) t.set(page - 1, total);
            else t.add(total);
        }
        return out;
    }

    private static JSONArray cards(String html) throws JSONException {
        JSONArray out = new JSONArray();
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            String vx = m.group(1), img = m.group(2), title = decode(m.group(3)), dur = m.group(4).trim();
            String site = first("data-cl-name=\"([^\"]+)\"", img);
            if (site == null || !PLAYABLE.contains(site) || Erome.BLOCKED.matcher(title).find()) continue;
            // lazy images keep the real one in data-src
            String thumb = first("data-src=\"([^\"]+)\"", img);
            if (thumb == null) thumb = first(" src=\"([^\"]+)\"", img);
            if (thumb != null && thumb.startsWith("//")) thumb = "https:" + thumb;
            JSONObject meta = new JSONObject()
                    .put("id", PREFIX + vx)
                    .put("type", "movie")
                    .put("name", title.isEmpty() ? "Sans titre" : title)
                    .put("poster", thumb)
                    .put("posterShape", "landscape")
                    .put("background", thumb)
                    .put("runtime", dur)
                    .put("description", dur + " · " + site.replaceAll("(Com|Net)$", ""));
            synchronized (known) {
                known.put(vx, meta);
            }
            out.put(meta);
        }
        return out;
    }

    // ---------------------------------------------------------------- one video

    private static JSONObject meta(String vx) throws IOException, JSONException {
        synchronized (known) {
            JSONObject k = known.get(vx);
            if (k != null) return k;
        }
        // not seen in a listing since Pi TV started (from Continuer à regarder, say): the
        // video's own page says what it is
        String[] p = Net.page(SITE + "/go?vx=" + vx, SITE + "/en/");
        String title = decode(first("og:title\" content=\"([^\"]*)\"", p[1]));
        if (title.isEmpty()) title = decode(first("<title>([^<]*)</title>", p[1]));
        if (Erome.BLOCKED.matcher(title).find()) return null;
        String image = first("og:image\" content=\"([^\"]*)\"", p[1]);
        return new JSONObject().put("id", PREFIX + vx).put("type", "movie").put("name", title.isEmpty() ? "Vidéo" : title)
                .put("poster", image).put("background", image).put("posterShape", "landscape");
    }

    private static JSONArray streams(String vx) throws IOException, JSONException {
        String[] p = Net.page(SITE + "/go?vx=" + vx, SITE + "/en/");
        String at = p[0], html = p[1], host = host(at);
        JSONObject headers = new JSONObject().put("Referer", at).put("User-Agent", Net.BROWSER);
        // [address, label], the best first
        List<String[]> found = new ArrayList<>();
        // the KVS player: video_url, and the higher qualities in video_alt_url…
        for (String k : new String[]{"video_alt_url3", "video_alt_url2", "video_alt_url", "video_url"}) {
            String u = first(k + "\\s*:\\s*'([^']+)'", html);
            if (u == null || u.startsWith("function/")) continue;
            String label = first(k + "_text\\s*:\\s*'([^']+)'", html);
            found.add(new String[]{u, label != null ? label : quality(u)});
        }
        // a plain <source>
        Matcher s = Pattern.compile("<source[^>]+src=\"([^\"]+)\"[^>]*>").matcher(html);
        while (s.find()) {
            String tag = s.group(), label = first("(?:label|title|res)=\"([^\"]+)\"", tag);
            found.add(new String[]{s.group(1), label != null && !label.equalsIgnoreCase("auto") ? label : quality(s.group(1))});
        }
        // the schema.org description, then any HLS playlist named in the page
        String ld = first("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"", html);
        if (ld != null) found.add(new String[]{ld.replace("\\/", "/"), quality(ld)});
        String hls = first("(https?:[^\"'\\s]+\\.m3u8[^\"'\\s]*)", html);
        if (hls != null) found.add(new String[]{hls.replace("\\/", "/"), "HLS"});

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        String title = decode(first("og:title\" content=\"([^\"]*)\"", html));
        for (String[] f : found) {
            String u = f[0].startsWith("//") ? "https:" + f[0] : f[0];
            if (!u.startsWith("http") || u.toLowerCase().contains("preview") || !seen.add(u)) continue;
            out.put(new JSONObject()
                    .put("name", host + "\n" + f[1])
                    .put("title", title.isEmpty() ? host : title)
                    .put("url", u)
                    .put("headers", headers));
        }
        return out;
    }

    /** a quality read from the address: …_720p.mp4 */
    private static String quality(String u) {
        String q = first("(\\d{3,4})p", u);
        return q != null ? q + "p" : u.contains(".m3u8") ? "HLS" : "MP4";
    }

    private static String host(String u) {
        String h = u.replaceFirst("^https?://", "").replaceFirst("[/?].*", "").replaceFirst("^www\\.", "");
        return h.isEmpty() ? "Vidéo" : h;
    }

    // ---------------------------------------------------------------- text

    private static String first(String regex, String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String decode(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    private Tasty() {
    }
}
