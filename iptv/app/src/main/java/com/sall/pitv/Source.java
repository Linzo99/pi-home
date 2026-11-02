package com.sall.pitv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** The provider: an Xtream Codes login or an M3U playlist, with an optional XMLTV guide. */
final class Source {
    static final String XTREAM = "xtream", M3U = "m3u";
    /** most providers accept VLC; some block Android's default agent */
    static final String DEFAULT_AGENT = "VLC/3.0.21 LibVLC/3.0.21";

    String type = XTREAM;
    String server = "", user = "", pass = "";
    String m3u = "";
    String epg = "";
    String agent = DEFAULT_AGENT;
    /** "host:port" of an HTTP proxy in another country, for geo-blocked channels; empty for none */
    String proxy = "";

    /** the proxy as host and port, or null */
    java.net.InetSocketAddress proxyAddress() {
        String p = proxy.trim().replaceFirst("^https?://", "");
        int i = p.lastIndexOf(':');
        if (i <= 0) return null;
        try {
            return java.net.InetSocketAddress.createUnresolved(p.substring(0, i), Integer.parseInt(p.substring(i + 1).replace("/", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean isSet() {
        return XTREAM.equals(type) ? !server.isEmpty() && !user.isEmpty() : !m3u.isEmpty();
    }

    /** a short id, so the caches of two different sources never mix */
    String id() {
        return Integer.toHexString((type + server + user + pass + m3u).hashCode());
    }

    /** "http://host:port", whatever form the user typed */
    String base() {
        String s = server.trim();
        if (!s.contains("://")) s = "http://" + s;
        int i = s.indexOf("/player_api.php");
        if (i > 0) s = s.substring(0, i);
        i = s.indexOf("/get.php");
        if (i > 0) s = s.substring(0, i);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    String api(String action) {
        return base() + "/player_api.php?username=" + Uri.encode(user) + "&password=" + Uri.encode(pass)
                + (action == null ? "" : "&action=" + action);
    }

    String liveUrl(String id, String ext) {
        return base() + "/live/" + user + "/" + pass + "/" + id + "." + ext;
    }

    String movieUrl(String id, String ext) {
        return base() + "/movie/" + user + "/" + pass + "/" + id + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }

    String episodeUrl(String id, String ext) {
        return base() + "/series/" + user + "/" + pass + "/" + id + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }

    static Source load(Context c) {
        SharedPreferences p = c.getSharedPreferences("source", Context.MODE_PRIVATE);
        Source s = new Source();
        s.type = p.getString("type", XTREAM);
        s.server = p.getString("server", "");
        s.user = p.getString("user", "");
        s.pass = p.getString("pass", "");
        s.m3u = p.getString("m3u", "");
        s.epg = p.getString("epg", "");
        s.agent = p.getString("agent", DEFAULT_AGENT);
        if (s.agent.isEmpty()) s.agent = DEFAULT_AGENT;
        s.proxy = p.getString("proxy", "");
        return s;
    }

    void save(Context c) {
        c.getSharedPreferences("source", Context.MODE_PRIVATE).edit()
                .putString("type", type).putString("server", server.trim()).putString("user", user.trim())
                .putString("pass", pass.trim()).putString("m3u", m3u.trim()).putString("epg", epg.trim())
                .putString("agent", agent.trim()).putString("proxy", proxy.trim()).apply();
    }
}
