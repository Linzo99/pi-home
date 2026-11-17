package com.sall.pitv.data;

import com.sall.pitv.R;
import com.sall.pitv.ui.*;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.net.*;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the launcher shows for Pi TV, through the Android TV provider: the films and episodes
 * of "Continuer à regarder" as watch-next entries, and the recent channels as a preview
 * channel. Each entry opens the app on it (MainActivity.EXTRA_OPEN).
 */
public final class TvHome {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final String CHANNEL = "Chaînes récentes";

    /** publishes the library's current state, off the main thread; nothing is written when unchanged */
    public static void sync(Context c) {
        Context app = c.getApplicationContext();
        worker.execute(() -> {
            try {
                Library lib = Library.get(app);
                List<Library.Progress> cw = lib.continueWatching();
                List<Model.Item> live = lib.recentLive();
                StringBuilder sig = new StringBuilder();
                for (Library.Progress p : cw) sig.append(p.item.key()).append('@').append(p.pos / 60_000).append(';');
                sig.append('|');
                for (Model.Item i : live) sig.append(i.key()).append(';');
                SharedPreferences prefs = app.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
                if (sig.toString().equals(prefs.getString("sig", null))) return;
                watchNext(app, cw);
                channels(app, live);
                prefs.edit().putString("sig", sig.toString()).apply();
            } catch (Exception e) {
                // no TV provider, or it refused: the launcher just shows nothing for Pi TV
                Log.w("TvHome", "sync", e);
            }
        });
    }

    private static void watchNext(Context c, List<Library.Progress> cw) {
        ContentResolver r = c.getContentResolver();
        // the provider only hands an app its own rows
        r.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null);
        for (Library.Progress p : cw) {
            Model.Item i = p.item;
            ContentValues v = new ContentValues();
            boolean ep = i.kind == Model.EPISODE;
            v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, ep ? TvContract.WatchNextPrograms.TYPE_TV_EPISODE : TvContract.WatchNextPrograms.TYPE_MOVIE);
            v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
            v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, ep && i.seriesName != null ? i.seriesName : i.name);
            if (ep) {
                v.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, i.name);
                if (i.season > 0) v.put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, String.valueOf(i.season));
                if (i.episode > 0) v.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, String.valueOf(i.episode));
            }
            if (i.plot != null) v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, i.plot);
            // a wide picture, as on the app's own row: an episode's still, else the backdrop
            String img = ep && notEmpty(i.image) ? i.image : notEmpty(i.backdrop) ? i.backdrop : i.image;
            if (notEmpty(img)) {
                v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, img);
                v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
            }
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) p.pos);
            v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) p.dur);
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, p.time);
            v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, i.key());
            v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, link(c, i.key()));
            r.insert(TvContract.WatchNextPrograms.CONTENT_URI, v);
        }
    }

    private static void channels(Context c, List<Model.Item> live) {
        ContentResolver r = c.getContentResolver();
        long id = -1;
        try (Cursor cur = r.query(TvContract.Channels.CONTENT_URI, new String[] {TvContract.Channels._ID, TvContract.Channels.COLUMN_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(1))) id = cur.getLong(0);
            }
        }
        if (id < 0) {
            if (live.isEmpty()) return;
            ContentValues v = new ContentValues();
            v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
            v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, CHANNEL);
            v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, new Intent(c, MainActivity.class).toUri(Intent.URI_INTENT_SCHEME));
            Uri u = r.insert(TvContract.Channels.CONTENT_URI, v);
            if (u == null) return;
            id = android.content.ContentUris.parseId(u);
            try {
                // the stock launcher asks the user; Pi Home's row needs it set (setup.sh does it with root)
                TvContract.requestChannelBrowsable(c, id);
            } catch (Exception ignored) {
            }
        }
        // the channel's programs are replaced as a whole
        try (Cursor cur = r.query(TvContract.PreviewPrograms.CONTENT_URI, new String[] {TvContract.PreviewPrograms._ID,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (cur.getLong(1) == id) r.delete(TvContract.buildPreviewProgramUri(cur.getLong(0)), null, null);
            }
        }
        int weight = live.size();
        for (Model.Item ch : live) {
            ContentValues v = new ContentValues();
            v.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, id);
            v.put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL);
            v.put(TvContract.PreviewPrograms.COLUMN_TITLE, ch.name);
            if (notEmpty(ch.image)) {
                v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, ch.image);
                v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
            }
            v.put(TvContract.PreviewPrograms.COLUMN_LIVE, 1);
            v.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight--);
            v.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, ch.key());
            v.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, link(c, ch.key()));
            r.insert(TvContract.PreviewPrograms.CONTENT_URI, v);
        }
    }

    /** opens Pi TV on the film, episode or channel with this key */
    private static String link(Context c, String key) {
        return new Intent(c, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .toUri(Intent.URI_INTENT_SCHEME);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private TvHome() {
    }
}
