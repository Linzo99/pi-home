package com.sall.pitv;

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
final class Net {
    static String agent = Source.DEFAULT_AGENT;

    static InputStream open(String url) throws IOException {
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", agent);
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
            InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
            return gunzip(in);
        }
        throw new IOException("too many redirects");
    }

    /** gzip by its magic bytes too: XMLTV guides are often served as .gz without the header */
    static InputStream gunzip(InputStream in) throws IOException {
        in.mark(2);
        int a = in.read(), b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b ? new BufferedInputStream(new GZIPInputStream(in, 64 * 1024), 64 * 1024) : in;
    }

    static byte[] bytes(String url) throws IOException {
        try (InputStream in = open(url)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        }
    }

    static String text(String url) throws IOException {
        return new String(bytes(url), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** downloads to a temporary file first, so a broken download never replaces a good cache */
    static void toFile(String url, File f) throws IOException {
        File tmp = new File(f.getPath() + ".part");
        try (InputStream in = open(url); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + f);
    }

    static InputStream file(File f) throws IOException {
        return gunzip(new BufferedInputStream(new FileInputStream(f), 64 * 1024));
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private Net() {
    }
}
