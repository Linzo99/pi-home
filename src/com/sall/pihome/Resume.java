package com.sall.pihome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Reprendre" for every app, including those that publish nothing to the TV provider: what each
 * app plays is followed through its media session (Pi Home already watches them for the
 * now-playing button), and the title, picture and position kept in files/resume.json.
 */
final class Resume {
    private static final int MAX = 24;
    private static final long MAX_AGE = 30L * 24 * 3600_000L;
    /** shorter than this is a clip or a song, not something to come back to */
    private static final long MIN_DURATION = 5 * 60_000L;
    /** the player's own screen for an entry, while Pi Home has been running since it played */
    private static final Map<String, PendingIntent> screens = new HashMap<>();

    /** called whenever a session changes: saves where its programme is, or drops it once finished */
    static synchronized void record(Context c, MediaController mc) {
        try {
            MediaMetadata md = mc.getMetadata();
            PlaybackState st = mc.getPlaybackState();
            if (md == null || st == null) return;
            String title = first(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), md.getString(MediaMetadata.METADATA_KEY_TITLE));
            long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (title == null || title.trim().isEmpty() || duration < MIN_DURATION) return;
            long pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
            pos = Math.max(0, Math.min(pos, duration));
            String pkg = mc.getPackageName();
            String key = pkg + "|" + title.trim();
            if (mc.getSessionActivity() != null) screens.put(key, mc.getSessionActivity());

            JSONArray list = read(c), out = new JSONArray();
            JSONObject entry = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if (key.equals(o.optString("key"))) entry = o;
                else out.put(o);
            }
            boolean finished = pos > duration * 95 / 100 || st.getState() == PlaybackState.STATE_STOPPED && pos > duration * 90 / 100;
            if (finished) {
                if (entry != null) new File(entry.optString("art", "/nonexistent")).delete();
                write(c, out);
                return;
            }
            // too early to be worth resuming
            if (pos < 30_000 && entry == null) return;
            if (entry == null) entry = new JSONObject().put("key", key).put("pkg", pkg).put("title", title.trim());
            String sub = first(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            entry.put("sub", sub == null ? "" : sub).put("pos", pos).put("dur", duration).put("at", System.currentTimeMillis());
            if (!entry.has("art")) {
                String art = art(c, md, key);
                if (art != null) entry.put("art", art);
            }
            JSONArray sorted = new JSONArray().put(entry);
            for (int i = 0; i < out.length() && sorted.length() < MAX; i++) sorted.put(out.get(i));
            write(c, sorted);
        } catch (Exception ignored) {
        }
    }

    /** the entries, latest first; one app's only when pkg is given */
    static synchronized List<WatchNext.Item> load(Context c, String pkg) {
        List<WatchNext.Item> items = new ArrayList<>();
        try {
            JSONArray list = read(c);
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if (now - o.optLong("at") > MAX_AGE) continue;
                if (pkg != null && !pkg.equals(o.optString("pkg"))) continue;
                Intent open = c.getPackageManager().getLeanbackLaunchIntentForPackage(o.optString("pkg"));
                if (open == null) open = c.getPackageManager().getLaunchIntentForPackage(o.optString("pkg"));
                if (open == null) continue; // uninstalled
                WatchNext.Item it = new WatchNext.Item();
                it.pkg = o.optString("pkg");
                it.title = o.optString("title");
                String sub = o.optString("sub", "");
                it.subtitle = sub.isEmpty() ? null : sub;
                it.position = o.optLong("pos");
                it.duration = o.optLong("dur");
                String art = o.optString("art", "");
                it.poster = art.isEmpty() ? null : art.startsWith("/") ? "file://" + art : art;
                it.intentUri = open.toUri(Intent.URI_INTENT_SCHEME);
                it.resumeKey = o.optString("key");
                items.add(it);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    /** opens the player's own screen when it is still known, which lands back on the programme */
    static boolean open(WatchNext.Item it) {
        PendingIntent pi = it.resumeKey == null ? null : screens.get(it.resumeKey);
        if (pi == null) return false;
        try {
            pi.send();
            return true;
        } catch (Exception e) {
            screens.remove(it.resumeKey);
            return false;
        }
    }

    /** the programme's picture: a link the app gave, else its bitmap saved small */
    private static String art(Context c, MediaMetadata md, String key) {
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI}) {
            String u = md.getString(k);
            if (u != null && (u.startsWith("http") || u.startsWith("content"))) return u;
        }
        Bitmap b = null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ALBUM_ART}) {
            b = md.getBitmap(k);
            if (b != null) break;
        }
        if (b == null) return null;
        File dir = new File(c.getFilesDir(), "resume");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
        try (FileOutputStream o = new FileOutputStream(f)) {
            int w = Math.min(480, b.getWidth());
            Bitmap small = w == b.getWidth() ? b : Bitmap.createScaledBitmap(b, w, Math.max(1, b.getHeight() * w / b.getWidth()), true);
            small.compress(Bitmap.CompressFormat.JPEG, 85, o);
            return f.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }

    private static JSONArray read(Context c) {
        File f = new File(c.getFilesDir(), "resume.json");
        try {
            return new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray a) throws Exception {
        File tmp = new File(c.getFilesDir(), "resume.json.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
        tmp.renameTo(new File(c.getFilesDir(), "resume.json"));
    }

    private Resume() {
    }
}
