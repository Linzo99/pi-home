package com.sall.pitv.net;

import com.sall.pitv.R;
import com.sall.pitv.ui.*;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** HTTP downloads, with the provider's user agent, redirects across http/https and gzip. */
public final class Net {
    public static String agent = Source.DEFAULT_AGENT;

    static InputStream open(String url) throws IOException {
        return open(url, null, null, null);
    }

    /**
     * [agentOr] instead of the IPTV user agent and [referer] when given; [landed], when given,
     * gets the address after the redirects
     */
    static InputStream open(String url, String agentOr, String referer, String[] landed) throws IOException {
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", agentOr != null ? agentOr : agent);
            if (referer != null) c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                // HttpURLConnection won't follow http -> https by itself
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            if (landed != null) landed[0] = url;
            InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
            return gunzip(in);
        }
        throw new IOException("too many redirects");
    }

    /** gzip by its magic bytes too: XMLTV guides are often served as .gz without the header */
    public static InputStream gunzip(InputStream in) throws IOException {
        if (!in.markSupported()) in = new BufferedInputStream(in, 64 * 1024);
        in.mark(2);
        int a = in.read(), b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b ? new BufferedInputStream(new GZIPInputStream(in, 64 * 1024), 64 * 1024) : in;
    }

    public static byte[] bytes(String url) throws IOException {
        try (InputStream in = open(url)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        }
    }

    /** a web page as a browser would ask for it: {address after redirects, its text} */
    public static String[] page(String url, String referer) throws IOException {
        String[] landed = {url};
        try (InputStream in = open(url, BROWSER, referer, landed)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return new String[]{landed[0], new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)};
        }
    }

    public static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    public static String text(String url) throws IOException {
        // an add-on built into Pi TV answers here, without the network
        if (Builtin.is(url)) return Builtin.answer(url);
        return new String(bytes(url), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** downloads to a temporary file first, so a broken download never replaces a good cache */
    public static void toFile(String url, File f) throws IOException {
        File tmp = new File(f.getPath() + ".part");
        try (InputStream in = open(url); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + f);
    }

    public static InputStream file(File f) throws IOException {
        return gunzip(new BufferedInputStream(new FileInputStream(f), 64 * 1024));
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    // ---------------------------------------------------------------- stream links

    /**
     * a link the player can open: a web address, or a data: link holding the media itself (a
     * source's own playlist, such as one site's video with another's audio), never a torrent or
     * a local page
     */
    public static boolean playable(String url) {
        if (url == null) return false;
        String low = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("data:")) return low.indexOf(',') > 0;
        return low.startsWith("http") && !low.startsWith("http://127.") && !low.startsWith("http://localhost")
                && !low.contains(".torrent");
    }

    public static final String HLS = "application/x-mpegURL", DASH = "application/dash+xml";

    /** HLS, DASH, or null to let the player guess from the bytes */
    public static String format(String url) {
        String low = url.toLowerCase(java.util.Locale.ROOT);
        if (!low.startsWith("data:")) {
            if (low.contains(".m3u8")) return HLS;
            if (low.contains(".mpd")) return DASH;
            return null;
        }
        // data:[//]<type>[;params][;base64],<payload>: the declared type first, then the payload
        int comma = low.indexOf(',');
        String head = low.substring(5, comma);
        if (head.contains("mpegurl") || head.contains("m3u8") || head.contains("/hls")) return HLS;
        if (head.contains("dash") || head.contains("mpd")) return DASH;
        String start;
        try {
            String body = url.substring(comma + 1);
            int hash = body.indexOf('#');
            if (hash >= 0) body = body.substring(0, hash);
            if (body.length() > 4096) body = body.substring(0, 4096);
            start = head.contains(";base64")
                    ? new String(android.util.Base64.decode(body.substring(0, body.length() / 4 * 4), android.util.Base64.DEFAULT), "UTF-8")
                    : java.net.URLDecoder.decode(body, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (start.trim().startsWith("#EXTM3U")) return HLS;
        if (start.contains("<MPD")) return DASH;
        return null;
    }

    private Net() {
    }
}
