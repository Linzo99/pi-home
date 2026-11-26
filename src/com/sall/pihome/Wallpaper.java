package com.sall.pihome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * The home screen's photo, kept on disk so it shows at once (and offline), and changed every few
 * hours. Two sources:
 * - "nature": Wikimedia Commons' featured pictures of lakes and mountains, freely licensed
 *   (mostly CC BY-SA), credited with their author and licence as Commons asks;
 * - "bing": Bing's picture of the day.
 */
final class Wallpaper {
    static final class Photo {
        Bitmap bitmap;
        /** what, by whom, under which licence */
        String credit;
    }

    static final String NATURE = "nature", BING = "bing";
    private static final long TTL = 6 * 3600_000L, LIST_TTL = 7 * 24 * 3600_000L;
    private static final String[] CATEGORIES = {"Featured pictures of lakes", "Featured pictures of mountains"};
    private static final String AGENT = "PiHome/2.0 (Android TV home screen)";

    static String source(Context c) {
        return prefs(c).getString("wall.src", NATURE);
    }

    /** another source: the next refresh fetches from it at once */
    static void setSource(Context c, String src) {
        prefs(c).edit().putString("wall.src", src).putLong("wall.at", 0).apply();
    }

    /** the next refresh takes a new photo whatever the time */
    static void skip(Context c) {
        prefs(c).edit().putLong("wall.at", 0).apply();
    }

    /** the saved photo, or null before the first download; call off the main thread */
    static Photo saved(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        // half the memory and quicker to decode; a photo needs no transparency
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        Photo p = new Photo();
        p.bitmap = b;
        p.credit = prefs(c).getString("wall.credit", "");
        return p;
    }

    /** a new photo when the saved one is old; null otherwise */
    static synchronized Photo refresh(Context c) {
        SharedPreferences p = prefs(c);
        // a photo from the other source (or from before there was a choice) is replaced at once
        String of = p.getString("wall.of", p.contains("wall.base") || p.contains("wall.url") ? BING : source(c));
        if (file(c).exists() && of.equals(source(c)) && System.currentTimeMillis() - p.getLong("wall.at", 0) < TTL) return null;
        try {
            String[] pick = BING.equals(source(c)) ? bing(p) : nature(c, p);
            p.edit().putLong("wall.at", System.currentTimeMillis()).apply();
            if (pick == null) return null;
            File tmp = new File(c.getFilesDir(), "wallpaper.tmp");
            HttpURLConnection con = (HttpURLConnection) new URL(pick[0]).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", AGENT);
            long expected = -1, got = 0;
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                expected = con.getContentLengthLong();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; got += n) out.write(buf, 0, n);
            } finally {
                con.disconnect();
            }
            // a cut download still decodes, with a smeared band at the bottom: only whole ones
            if (expected > 0 && got != expected) return null;
            if (BitmapFactory.decodeFile(tmp.getPath()) == null || !tmp.renameTo(file(c))) return null;
            p.edit().putString("wall.url", pick[0]).putString("wall.credit", pick[1]).putString("wall.of", source(c)).apply();
            return saved(c);
        } catch (Exception e) {
            return null;
        }
    }

    /** {image link, credit}; null when Bing still shows the same picture */
    private static String[] bing(SharedPreferences p) throws Exception {
        JSONObject img = new JSONObject(Weather.get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=fr-FR"))
                .getJSONArray("images").getJSONObject(0);
        String url = "https://www.bing.com" + img.getString("urlbase") + "_1920x1080.jpg";
        if (url.equals(p.getString("wall.url", null))) return null;
        return new String[] {url, img.optString("copyright", "")};
    }

    /** the next photo of the Commons list, which is fetched again once a week */
    private static String[] nature(Context c, SharedPreferences p) throws Exception {
        JSONArray list;
        try {
            list = new JSONArray(p.getString("wall.list", "[]"));
        } catch (Exception e) {
            list = new JSONArray();
        }
        if (list.length() == 0 || System.currentTimeMillis() - p.getLong("wall.listAt", 0) > LIST_TTL) {
            JSONArray fresh = new JSONArray();
            for (String cat : CATEGORIES) commons(cat, fresh);
            if (fresh.length() > 0) {
                list = fresh;
                p.edit().putString("wall.list", list.toString()).putLong("wall.listAt", System.currentTimeMillis()).apply();
            }
        }
        if (list.length() == 0) return null;
        // a shuffled walk through the list: a fixed stride over the list's length
        int n = list.length(), i = (p.getInt("wall.i", (int) (Math.random() * n)) + 37) % n;
        p.edit().putInt("wall.i", i).apply();
        JSONObject o = list.getJSONObject(i);
        return new String[] {o.getString("u"), o.getString("c")};
    }

    /** a category's landscape-shaped pictures: a 1920-wide copy and its credit */
    private static void commons(String category, JSONArray into) throws Exception {
        String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers"
                + "&gcmtype=file&gcmlimit=50&gcmtitle=" + URLEncoder.encode("Category:" + category, "UTF-8")
                + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920"
                + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName";
        HttpURLConnection con = (HttpURLConnection) new URL(api).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", AGENT);
        String body;
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
            body = b.toString("UTF-8");
        } finally {
            con.disconnect();
        }
        JSONObject pages = new JSONObject(body).getJSONObject("query").getJSONObject("pages");
        for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) {
            JSONObject page = pages.getJSONObject(it.next());
            // Commons details only so many pictures per answer: the others are left out
            JSONArray infos = page.optJSONArray("imageinfo");
            if (infos == null || infos.length() == 0) continue;
            JSONObject info = infos.getJSONObject(0);
            int w = info.optInt("width"), h = info.optInt("height");
            // a screen's shape, near enough: no panoramas, no portraits
            if (h <= 0 || w < 1600 || w / (float) h < 1.3f || w / (float) h > 2.1f) continue;
            String url = info.optString("thumburl", "");
            if (url.isEmpty()) continue;
            JSONObject meta = info.optJSONObject("extmetadata");
            String title = text(meta, "ObjectName");
            if (title.isEmpty()) title = page.optString("title", "").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "");
            String artist = text(meta, "Artist"), licence = text(meta, "LicenseShortName");
            // the author and licence first: they must show even when the line is cut
            String credit = (artist.isEmpty() ? "" : "Photo : " + artist + " · ") + (licence.isEmpty() ? "" : licence + " · ")
                    + "Wikimedia Commons" + (title.isEmpty() ? "" : "\n" + title);
            into.put(new JSONObject().put("u", url).put("c", credit));
        }
    }

    /** a metadata field as plain text (Commons gives some as HTML) */
    private static String text(JSONObject meta, String key) {
        if (meta == null || meta.optJSONObject(key) == null) return "";
        String v = meta.optJSONObject(key).optString("value", "");
        return Html.fromHtml(v, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    private Wallpaper() {
    }
}
