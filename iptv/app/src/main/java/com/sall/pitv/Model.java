package com.sall.pitv;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Channels, films, series and episodes, whichever source they come from. */
final class Model {
    static final int LIVE = 0, MOVIE = 1, SERIES = 2, EPISODE = 3;

    static final class Category {
        final int kind;
        final String id, name;
        final List<Item> items = new ArrayList<>();

        Category(int kind, String id, String name) {
            this.kind = kind;
            this.id = id;
            this.name = name;
        }
    }

    static final class Item {
        int kind;
        String id, name;
        /** poster for films and series, logo for channels, still for episodes */
        String image;
        String backdrop;
        String category;
        /** stream address; empty for series, whose episodes have their own */
        String url;
        /** channel id in the guide (tvg-id / epg_channel_id) */
        String epgId;
        int num;
        /** when the provider added it, in seconds */
        long added;
        String rating, year, plot, genre, cast;
        /** episodes: their series' id and name, season and number */
        String seriesId, seriesName;
        int season, episode;
        /** M3U series: the episodes, grouped from names like "Show S01E02" */
        List<Item> episodes;
        /** the name lower-cased and without accents, for search */
        String folded;
        /** from a Stremio add-on: its type ("movie", "series") and id ("tt0111161", "kitsu:1:2") */
        String stype, sid;
        /** from an adult add-on: hidden until the adult code is typed, never kept in the history */
        boolean adult;
        /** add-on titles: the kind of stream chosen, to take the same for the next episode */
        String bingeGroup;
        /** add-on subtitles for the stream: {url, language} */
        List<String[]> subs;
        /** the HTTP headers a plugin's stream needs (Referer, User-Agent, …) */
        java.util.Map<String, String> headers;
        /** the stream playing: which source it came from (Addons.Stream.source) */
        String source;
        /** the other links found for this title, best first: tried in turn when one fails */
        List<Addons.Stream> alternatives;

        boolean fromAddon() {
            return sid != null;
        }

        String key() {
            return kind + ":" + id;
        }

        /** the line under a title: year, genre, rating */
        String meta() {
            StringBuilder b = new StringBuilder();
            if (year != null && !year.isEmpty()) b.append(year);
            if (genre != null && !genre.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(genre);
            if (rating != null && !rating.isEmpty() && !rating.equals("0")) {
                try {
                    double r = Double.parseDouble(rating);
                    if (r > 0) b.append(b.length() > 0 ? " · " : "").append(String.format(java.util.Locale.FRANCE, "★ %.1f", r > 10 ? r / 10 : r));
                } catch (NumberFormatException ignored) {
                }
            }
            return b.toString();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("k", kind).put("id", id).put("n", name).putOpt("img", image).putOpt("bd", backdrop)
                        .putOpt("c", category).putOpt("u", url).putOpt("epg", epgId).put("num", num)
                        .putOpt("y", year).putOpt("sid", seriesId).putOpt("sn", seriesName)
                        .put("s", season).put("e", episode).putOpt("st", stype).putOpt("aid", sid);
                if (adult) o.put("ad", true);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.kind = o.optInt("k");
            i.id = o.optString("id");
            i.name = o.optString("n");
            i.image = o.optString("img", null);
            i.backdrop = o.optString("bd", null);
            i.category = o.optString("c", null);
            i.url = o.optString("u", null);
            i.epgId = o.optString("epg", null);
            i.num = o.optInt("num");
            i.year = o.optString("y", null);
            i.seriesId = o.optString("sid", null);
            i.seriesName = o.optString("sn", null);
            i.season = o.optInt("s");
            i.episode = o.optInt("e");
            i.stype = o.optString("st", null);
            i.sid = o.optString("aid", null);
            // saved before the flag existed: Stremio ids are never web addresses, but the tube
            // sites' add-ons use the video page's
            i.adult = o.optBoolean("ad") || i.sid != null && i.sid.startsWith("http");
            return i;
        }
    }

    /** a programme in the guide */
    static final class Prog {
        final long start, stop;
        final String title, desc;

        Prog(long start, long stop, String title, String desc) {
            this.start = start;
            this.stop = stop;
            this.title = title;
            this.desc = desc;
        }
    }

    private Model() {
    }
}
