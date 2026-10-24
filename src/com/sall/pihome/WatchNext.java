package com.sall.pihome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** "Continue watching" entries that apps publish to the Android TV provider. */
final class WatchNext {
    static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");
    private static final int MAX = 12, MAX_ROW = 15;

    static final class Item {
        String pkg, title, subtitle, poster, intentUri;
        long position, duration;
        int color;
    }

    /**
     * everyone's "continue watching" except the apps in skip (the hidden ones); needs
     * ACCESS_ALL_EPG_DATA to see other apps' rows, returns an empty list without it
     */
    static List<Item> load(Context c, Set<String> skip) {
        List<Item> out = new ArrayList<>();
        String[] cols = {"package_name", "title", "episode_title", "season_display_number",
                "episode_display_number", "poster_art_uri", "thumbnail_uri", "intent_uri",
                "last_playback_position_millis", "duration_millis", "browsable"};
        try (Cursor cur = c.getContentResolver().query(URI, cols, null, null,
                "last_engagement_time_utc_millis DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < MAX) {
                if (!cur.isNull(10) && cur.getInt(10) == 0) continue;
                Item it = new Item();
                it.pkg = cur.getString(0);
                if (skip.contains(it.pkg)) continue;
                it.title = cur.getString(1);
                it.intentUri = cur.getString(7);
                if (it.title == null || it.intentUri == null) continue;
                it.subtitle = subtitle(cur.getString(2), cur.getString(3), cur.getString(4));
                it.poster = poster(cur.getString(6), cur.getString(5));
                it.position = cur.getLong(8);
                it.duration = cur.getLong(9);
                out.add(it);
            }
        } catch (Exception ignored) {
            // provider missing or permission not granted
        }
        return out;
    }

    static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");

    static final class Row {
        String label;
        List<Item> items = new ArrayList<>();
    }

    /** everything one app publishes: its "continue watching" first, then its visible channels */
    static Row forApp(Context c, String pkg, String appLabel) {
        Row row = new Row();
        Set<String> seen = new HashSet<>();
        List<String> sections = new ArrayList<>();
        for (Item it : load(c, new HashSet<>())) {
            if (!pkg.equals(it.pkg)) continue;
            row.items.add(it);
            seen.add(it.title);
        }
        if (!row.items.isEmpty()) sections.add("Continuer à regarder");

        String[] chCols = {"_id", "package_name", "display_name", "browsable"};
        String[] cols = {"title", "episode_title", "season_display_number", "episode_display_number",
                "poster_art_uri", "thumbnail_uri", "intent_uri", "last_playback_position_millis",
                "duration_millis", "browsable"};
        try (Cursor ch = c.getContentResolver().query(CHANNELS, chCols, null, null, null)) {
            while (ch != null && ch.moveToNext() && row.items.size() < MAX_ROW) {
                if (!pkg.equals(ch.getString(1)) || ch.getInt(3) == 0) continue;
                int before = row.items.size();
                try (Cursor cur = c.getContentResolver().query(PREVIEW, cols, "channel_id=?",
                        new String[] {String.valueOf(ch.getLong(0))}, "weight DESC")) {
                    while (cur != null && cur.moveToNext() && row.items.size() < MAX_ROW) {
                        if (!cur.isNull(9) && cur.getInt(9) == 0) continue;
                        Item it = new Item();
                        it.pkg = pkg;
                        it.title = cur.getString(0);
                        it.intentUri = cur.getString(6);
                        if (it.title == null || it.intentUri == null || !seen.add(it.title)) continue;
                        it.subtitle = subtitle(cur.getString(1), cur.getString(2), cur.getString(3));
                        it.poster = poster(cur.getString(5), cur.getString(4));
                        it.position = cur.getLong(7);
                        it.duration = cur.getLong(8);
                        row.items.add(it);
                    }
                }
                String name = ch.getString(2);
                if (row.items.size() > before && !TextUtils.isEmpty(name)) sections.add(name);
            }
        } catch (Exception ignored) {
        }
        row.label = appLabel + (sections.isEmpty() ? "" : " · " + TextUtils.join(" · ", sections));
        return row;
    }

    private static String subtitle(String ep, String season, String num) {
        if (season != null && num != null) return "S" + season + " · É" + num + (ep != null ? " · " + ep : "");
        return ep;
    }

    private static String poster(String thumb, String poster) {
        return !TextUtils.isEmpty(thumb) ? thumb : TextUtils.isEmpty(poster) ? null : poster;
    }

    static Intent intentOf(Item it) throws Exception {
        return Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** a poster scaled to about the card size, cached on disk; call off the main thread */
    static Bitmap poster(Context c, String uri, int widthPx) {
        if (uri == null) return null;
        File dir = new File(c.getCacheDir(), "posters");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(uri.hashCode()));
        try {
            if (!f.exists()) {
                byte[] data;
                if (uri.startsWith("http")) {
                    HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(10000);
                    try (InputStream in = con.getInputStream()) {
                        data = readAll(in);
                    } finally {
                        con.disconnect();
                    }
                } else {
                    try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
                        data = readAll(in);
                    }
                }
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= widthPx) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            Log.w("PiHome", "poster " + uri + ": " + e);
            f.delete();
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private WatchNext() {
    }
}
