package com.sall.pitv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Nuvio's collections: themed shelves (Streaming, Genres, Studios…) of folders, each a tile that
 * opens a few add-on catalogues. Read from a Nuvio export saved as files/collections.json, else
 * the one shipped in the app's assets; the catalogues themselves come from the add-ons, by their
 * manifest id.
 */
final class Folders {
    static final class Collection {
        String title;
        final List<Folder> folders = new ArrayList<>();
    }

    static final class Folder {
        String title, shelf, cover, backdrop, logo;
        boolean landscape;
        /** each {addon id, type, catalogue id, genre} */
        final List<String[]> sources = new ArrayList<>();
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "collections.json");
    }

    private static List<Collection> cached;
    private static long cachedStamp = Long.MIN_VALUE;

    /** the shelves, read again only when the file changes (an import from the phone) */
    static synchronized List<Collection> load(Context c) {
        File f = file(c);
        long stamp = f.exists() ? f.lastModified() : -1;
        if (cached != null && stamp == cachedStamp) return cached;
        try {
            String json;
            if (f.exists()) json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            else try (java.io.InputStream in = c.getAssets().open("collections.json")) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                Net.copy(in, b);
                json = b.toString("UTF-8");
            }
            cached = parse(json);
        } catch (Exception e) {
            Log.w("PiTV", "collections", e);
            cached = new ArrayList<>();
        }
        cachedStamp = stamp;
        return cached;
    }

    /** a Nuvio collections export; throws when it isn't one */
    static List<Collection> parse(String json) throws Exception {
        List<Collection> out = new ArrayList<>();
        JSONArray a = new JSONArray(json);
        {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Collection col = new Collection();
                col.title = o.optString("title");
                JSONArray fs = o.optJSONArray("folders");
                for (int k = 0; fs != null && k < fs.length(); k++) {
                    JSONObject x = fs.optJSONObject(k);
                    if (x == null) continue;
                    Folder d = new Folder();
                    d.title = x.optString("title");
                    d.shelf = col.title;
                    d.cover = x.optString("coverImageUrl", null);
                    d.backdrop = x.optString("heroBackdropUrl", null);
                    d.logo = x.optString("titleLogoUrl", null);
                    d.landscape = "LANDSCAPE".equals(x.optString("tileShape"));
                    // an app export lists them as "sources", the pack's template as "catalogSources"
                    JSONArray src = x.optJSONArray("sources");
                    if (src == null) src = x.optJSONArray("catalogSources");
                    for (int s = 0; src != null && s < src.length(); s++) {
                        JSONObject y = src.optJSONObject(s);
                        if (y == null || !"addon".equals(y.optString("provider", "addon"))) continue;
                        String genre = y.optString("genre");
                        if ("None".equals(genre)) genre = "";
                        d.sources.add(new String[] {y.optString("addonId"), y.optString("type"), y.optString("catalogId"), genre});
                    }
                    if (!d.sources.isEmpty()) col.folders.add(d);
                }
                if (!col.folders.isEmpty()) out.add(col);
            }
        }
        return out;
    }

    private Folders() {
    }
}
