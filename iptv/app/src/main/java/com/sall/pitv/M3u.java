package com.sall.pitv;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An M3U playlist. Films and series are told apart from channels by their address (Xtream-style
 * /movie/ and /series/ paths) or a video file extension; series episodes are grouped by names
 * like "Show S01E02".
 */
final class M3u {
    static final class Result {
        final Map<String, Model.Category> live = new LinkedHashMap<>(), movies = new LinkedHashMap<>(), series = new LinkedHashMap<>();
        /** the guide named in the header (url-tvg / x-tvg-url) */
        String epg;
    }

    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern EPISODE = Pattern.compile("^(.*?)[\\s._-]*[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})\\b.*$");
    private static final Pattern VOD_GROUP = Pattern.compile("film|movie|vod|cin[ée]ma|s[ée]rie|saison|season|show", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_FILE = Pattern.compile("\\.(mp4|mkv|avi|mov|wmv|m4v|webm)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

    static Result parse(File f) throws IOException {
        Result res = new Result();
        Map<String, Model.Item> shows = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Net.file(f), StandardCharsets.UTF_8), 64 * 1024)) {
            String line, info = null, group = null;
            int n = 0;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTM3U")) {
                    Map<String, String> a = attrs(line);
                    String epg = a.containsKey("url-tvg") ? a.get("url-tvg") : a.get("x-tvg-url");
                    if (epg != null && !epg.isEmpty()) res.epg = epg.split(",")[0].trim();
                } else if (line.startsWith("#EXTINF")) {
                    info = line;
                } else if (line.startsWith("#EXTGRP:")) {
                    group = line.substring(8).trim();
                } else if (!line.startsWith("#") && info != null) {
                    add(res, shows, info, group, line, ++n);
                    info = null;
                    group = null;
                }
            }
        }
        for (Model.Item s : shows.values()) {
            s.episodes.sort((a, b) -> a.season != b.season ? Integer.compare(a.season, b.season) : Integer.compare(a.episode, b.episode));
        }
        return res;
    }

    private static void add(Result res, Map<String, Model.Item> shows, String info, String extgrp, String url, int n) {
        Map<String, String> a = attrs(info);
        int comma = firstUnquotedComma(info);
        String name = comma >= 0 ? info.substring(comma + 1).trim() : "";
        if (name.isEmpty()) name = a.getOrDefault("tvg-name", "Sans nom");
        String group = a.get("group-title");
        if (group == null || group.isEmpty()) group = extgrp != null ? extgrp : "Autres";
        String logo = a.get("tvg-logo");

        String lower = url.toLowerCase(Locale.ROOT);
        int kind;
        if (lower.contains("/series/")) kind = Model.EPISODE;
        else if (lower.contains("/movie/")) kind = Model.MOVIE;
        // a video file is a film or an episode only in a group named so: live playlists have
        // channels that loop an .mp4 too
        else if (VIDEO_FILE.matcher(lower).find() && VOD_GROUP.matcher(group).find()) kind = EPISODE.matcher(name).matches() ? Model.EPISODE : Model.MOVIE;
        else kind = Model.LIVE;

        Model.Item i = new Model.Item();
        i.kind = kind;
        i.id = Integer.toHexString(url.hashCode()) + Integer.toHexString(name.hashCode());
        i.name = name;
        i.image = logo;
        i.url = url;
        i.num = n;
        i.epgId = a.get("tvg-id");
        if (i.epgId == null || i.epgId.isEmpty()) i.epgId = a.get("tvg-name");

        if (kind == Model.EPISODE) {
            Matcher m = EPISODE.matcher(name);
            String show = name;
            if (m.matches()) {
                show = m.group(1).trim();
                i.season = Integer.parseInt(m.group(2));
                i.episode = Integer.parseInt(m.group(3));
                i.name = "Épisode " + i.episode;
            } else {
                i.season = 1;
                i.episode = n;
            }
            String key = group + "|" + show.toLowerCase(Locale.ROOT);
            Model.Item s = shows.get(key);
            if (s == null) {
                s = new Model.Item();
                s.kind = Model.SERIES;
                s.id = Integer.toHexString(key.hashCode());
                s.name = show;
                s.image = logo;
                s.category = group;
                s.episodes = new ArrayList<>();
                shows.put(key, s);
                cat(res.series, Model.SERIES, group).items.add(s);
            }
            i.seriesId = s.id;
            i.seriesName = s.name;
            i.category = group;
            s.episodes.add(i);
            return;
        }
        // "Movies;News": the item belongs to both
        String[] groups = group.split(";");
        i.category = groups[0].trim();
        for (String g : groups) {
            g = g.trim();
            if (!g.isEmpty()) cat(kind == Model.LIVE ? res.live : res.movies, kind, g).items.add(i);
        }
    }

    private static Model.Category cat(Map<String, Model.Category> m, int kind, String name) {
        Model.Category c = m.get(name);
        if (c == null) m.put(name, c = new Model.Category(kind, name, name));
        return c;
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> a = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(line);
        while (m.find()) a.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim());
        return a;
    }

    /** the comma before the name, skipping commas inside quoted attributes */
    private static int firstUnquotedComma(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    private M3u() {
    }
}
