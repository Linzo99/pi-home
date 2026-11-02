package com.sall.pitv;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The programme guide. From an XMLTV file when there is one (kept to the channels in the
 * playlist and to the hours around now, so a large guide fits in memory), else from the Xtream
 * short guide, fetched per channel when it's on screen.
 */
final class Epg {
    /** XMLTV programmes by lower-cased channel id */
    private final Map<String, List<Model.Prog>> guide = new HashMap<>();
    /** channel display names to ids, for playlists without tvg-id */
    private final Map<String, String> byName = new HashMap<>();
    private final Map<String, List<Model.Prog>> shortCache = new ConcurrentHashMap<>();
    private final Map<String, Long> shortTime = new ConcurrentHashMap<>();
    boolean hasGuide;

    /**
     * parses an XMLTV file, keeping programmes from 2 hours ago to 36 hours ahead for the
     * playlist's channels: by guide id, or by name for channels the playlist gives no id
     */
    void loadXmltv(File f, Set<String> ids, Set<String> names) {
        Set<String> wanted = new java.util.HashSet<>(ids);
        long now = System.currentTimeMillis(), from = now - 2 * 3600_000L, to = now + 36 * 3600_000L;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        SimpleDateFormat bare = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        bare.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        Map<String, List<Model.Prog>> g = new HashMap<>();
        Map<String, String> byNameNew = new HashMap<>();
        try (InputStream in = Net.file(f)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            String channel = null, title = null, desc = null, tag = null, chanId = null;
            long start = 0, stop = 0;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("programme".equals(tag)) {
                        channel = lower(p.getAttributeValue(null, "channel"));
                        start = time(fmt, bare, p.getAttributeValue(null, "start"));
                        stop = time(fmt, bare, p.getAttributeValue(null, "stop"));
                        title = null;
                        desc = null;
                    } else if ("channel".equals(tag)) {
                        chanId = lower(p.getAttributeValue(null, "id"));
                    }
                } else if (ev == XmlPullParser.TEXT && tag != null) {
                    if (channel != null && "title".equals(tag) && title == null) title = p.getText().trim();
                    else if (channel != null && "desc".equals(tag) && desc == null) desc = p.getText().trim();
                    else if (chanId != null && "display-name".equals(tag)) {
                        String n = norm(p.getText());
                        byNameNew.put(n, chanId);
                        if (names.contains(n)) wanted.add(chanId);
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = p.getName();
                    if ("programme".equals(name)) {
                        if (channel != null && stop > from && start < to && stop > start
                                && wanted.contains(channel)) {
                            List<Model.Prog> l = g.get(channel);
                            if (l == null) g.put(channel, l = new ArrayList<>());
                            l.add(new Model.Prog(start, stop, title == null ? "" : title, desc));
                        }
                        channel = null;
                    } else if ("channel".equals(name)) {
                        chanId = null;
                    }
                    tag = null;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PiTV", "guide", e);
        }
        for (List<Model.Prog> l : g.values()) l.sort((a, b) -> Long.compare(a.start, b.start));
        synchronized (this) {
            guide.clear();
            guide.putAll(g);
            byName.clear();
            byName.putAll(byNameNew);
            hasGuide = !g.isEmpty();
        }
    }

    /** the channel's programmes from now on (the current one first), or empty */
    List<Model.Prog> upcoming(Model.Item ch) {
        List<Model.Prog> all = cached(ch);
        if (all == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        List<Model.Prog> out = new ArrayList<>();
        for (Model.Prog p : all) if (p.stop > now) out.add(p);
        return out;
    }

    Model.Prog now(Model.Item ch) {
        List<Model.Prog> u = upcoming(ch);
        return u.isEmpty() || u.get(0).start > System.currentTimeMillis() ? null : u.get(0);
    }

    /** what's known without the network: the XMLTV guide, or a recent short guide */
    List<Model.Prog> cached(Model.Item ch) {
        synchronized (this) {
            if (hasGuide) {
                List<Model.Prog> l = ch.epgId == null ? null : guide.get(lower(ch.epgId));
                if (l == null) {
                    String id = byName.get(norm(ch.name));
                    if (id != null) l = guide.get(id);
                }
                return l;
            }
        }
        return shortCache.get(ch.id);
    }

    /** Xtream without a guide file: fetches the channel's short guide; call off the main thread */
    boolean fetchShort(Source s, Model.Item ch) {
        if (hasGuide || !Source.XTREAM.equals(s.type)) return false;
        Long t = shortTime.get(ch.id);
        if (t != null && System.currentTimeMillis() - t < 20 * 60_000L) return false;
        shortTime.put(ch.id, System.currentTimeMillis());
        try {
            shortCache.put(ch.id, Xtream.shortEpg(s, ch.id));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long time(SimpleDateFormat fmt, SimpleDateFormat bare, String s) {
        if (s == null) return 0;
        try {
            s = s.trim();
            if (s.length() > 14 && s.indexOf(' ') < 0) s = s.substring(0, 14) + " " + s.substring(14);
            return s.length() > 14 ? fmt.parse(s).getTime() : bare.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    /** "TF1 HD", "tf1.fr" and "TF1" all become "tf1" */
    static String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT).replaceAll("\\.(fr|sn|com|tv|uk|us|be|ch)\\b", "");
        s = s.replaceAll("\\b(hd|fhd|uhd|4k|sd|hevc|h265)\\b", "").replaceAll("[^\\p{L}\\p{N}]", "");
        return s;
    }
}
