package com.sall.pitv.player;

import com.sall.pitv.R;
import com.sall.pitv.ui.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A live channel recorded to a file from the moment it's paused, so it can resume where it was
 * and be rewound or forwarded up to the live point. The file is a ring: the oldest minutes are
 * overwritten once it's full. MPEG-TS only: a plain .ts stream, or an HLS stream whose segments
 * are MPEG-TS (they're appended one after the other).
 */
public final class Timeshift {
    private static final int TS_PACKET = 188;
    /** gives up after the source has failed for this long */
    private static final long GIVE_UP_MS = 25_000;

    private final String url;
    private final OkHttpClient http;
    private final File file;
    private final long cap;
    private final RandomAccessFile out;
    private final Object lock = new Object();
    /** bytes written since the start; the file holds the last [cap] of them */
    private long written;
    /** the media time at the end of what's written, from the start */
    private volatile long mediaEnd;
    /** where each stretch of media starts: {media ms, byte offset}, in order */
    private final List<long[]> index = new ArrayList<>();
    private volatile String error;
    private volatile boolean closed;
    private Thread thread;

    Timeshift(Context c, String url, java.net.InetSocketAddress proxy) throws IOException {
        this.url = url;
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS);
        if (proxy != null) b.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, proxy));
        http = b.build();
        file = new File(c.getCacheDir(), "timeshift.ts");
        // up to 2 GB (about an hour at 4 Mb/s), leaving the Pi room to breathe
        long free = c.getCacheDir().getUsableSpace() + (file.exists() ? file.length() : 0);
        cap = Math.min(2_000_000_000L, free - 500_000_000L) / TS_PACKET * TS_PACKET;
        if (cap < 100_000_000L) throw new IOException("Pas assez de place sur le Pi pour mettre en pause");
        out = new RandomAccessFile(file, "rw");
        out.setLength(0);
    }

    void start() {
        thread = new Thread(() -> {
            try {
                if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) hls();
                else progressive();
            } catch (Unsupported e) {
                fail(e.getMessage());
            } catch (Exception e) {
                if (!closed) fail("Enregistrement interrompu");
            }
        }, "timeshift");
        thread.setDaemon(true);
        thread.start();
    }

    void close() {
        closed = true;
        if (thread != null) thread.interrupt();
        synchronized (lock) {
            lock.notifyAll();
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** the channel can't be recorded (or stopped being): why, in French; null while it's fine */
    String error() {
        return error;
    }

    /** recording works: data has arrived */
    boolean started() {
        synchronized (lock) {
            return written > 0;
        }
    }

    /** the live point, in media time from the pause */
    long liveMs() {
        return mediaEnd;
    }

    /** the oldest moment still in the file */
    long oldestMs() {
        return entryAt(0)[0];
    }

    /** the start of the stretch holding [ms]: {media ms, byte offset}; the oldest one kept at worst */
    long[] entryAt(long ms) {
        synchronized (lock) {
            long floor = written - cap;
            long[] best = null;
            for (long[] e : index) {
                if (e[1] < floor) continue;
                if (best == null || e[0] <= ms) best = e;
                if (e[0] > ms) break;
            }
            return best != null ? best : new long[] {0, 0};
        }
    }

    // ---------------------------------------------------------------- recording

    private static final class Unsupported extends IOException {
        Unsupported(String why) {
            super(why);
        }
    }

    private void fail(String why) {
        error = why;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** a plain MPEG-TS stream: written as it comes, reconnected if it drops */
    private void progressive() throws IOException, InterruptedException {
        long t0 = System.currentTimeMillis(), failedSince = 0;
        byte[] buf = new byte[64 * 1024];
        boolean checked = false;
        while (!closed) {
            try (Response r = http.newCall(new Request.Builder().url(url).header("User-Agent", Net.agent).build()).execute()) {
                if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
                InputStream in = r.body().byteStream();
                long nextMark = 0;
                int n;
                while (!closed && (n = in.read(buf)) > 0) {
                    if (!checked) {
                        if ((buf[0] & 0xFF) != 0x47) throw new Unsupported("Pause indisponible sur cette chaîne (format)");
                        checked = true;
                    }
                    failedSince = 0;
                    long now = System.currentTimeMillis() - t0;
                    if (now >= nextMark) {
                        mark(now);
                        nextMark = now + 500;
                    }
                    write(buf, n);
                    mediaEnd = now;
                }
            } catch (Unsupported e) {
                throw e;
            } catch (IOException e) {
                if (closed) return;
                if (failedSince == 0) failedSince = System.currentTimeMillis();
                else if (System.currentTimeMillis() - failedSince > GIVE_UP_MS) throw e;
                Thread.sleep(1000);
            }
        }
    }

    /** HLS: the best variant's MPEG-TS segments, appended in order as the playlist grows */
    private void hls() throws IOException, InterruptedException {
        String media = url;
        String text = fetchText(media);
        if (text.contains("#EXT-X-STREAM-INF")) {
            // the variants: the best one, as the player would take
            if (text.contains("#EXT-X-MEDIA") && text.matches("(?s).*#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=.*")) {
                throw new Unsupported("Pause indisponible sur cette chaîne (son séparé)");
            }
            long best = -1;
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length - 1; i++) {
                String l = lines[i].trim();
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[:,]BANDWIDTH=(\\d+)").matcher(l);
                long bw = m.find() ? Long.parseLong(m.group(1)) : 0;
                String next = lines[i + 1].trim();
                if (bw > best && !next.isEmpty() && !next.startsWith("#")) {
                    best = bw;
                    media = resolve(lastUrl, next);
                }
            }
            text = fetchText(media);
        }
        long seq = -1, failedSince = 0;
        boolean first = true, checked = false;
        while (!closed) {
            if (!first) {
                try {
                    text = fetchText(media);
                    failedSince = 0;
                } catch (IOException e) {
                    if (failedSince == 0) failedSince = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - failedSince > GIVE_UP_MS) throw e;
                    Thread.sleep(1000);
                    continue;
                }
            }
            String base = lastUrl;
            if (text.contains("#EXT-X-MAP")) throw new Unsupported("Pause indisponible sur cette chaîne (format)");
            if (text.matches("(?s).*#EXT-X-KEY:METHOD=(?!NONE).*")) throw new Unsupported("Pause indisponible sur cette chaîne (chiffrée)");
            long firstSeq = 0;
            long target = 6;
            List<String> uris = new ArrayList<>();
            List<Long> durs = new ArrayList<>();
            long dur = 0;
            for (String l : text.split("\n")) {
                l = l.trim();
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) firstSeq = Long.parseLong(l.substring(22).trim());
                else if (l.startsWith("#EXT-X-TARGETDURATION:")) target = Long.parseLong(l.substring(22).trim());
                else if (l.startsWith("#EXTINF:")) {
                    String d = l.substring(8).split(",")[0].trim();
                    dur = Math.round(Double.parseDouble(d) * 1000);
                } else if (!l.isEmpty() && !l.startsWith("#")) {
                    uris.add(resolve(base, l));
                    durs.add(dur);
                }
            }
            if (first) {
                // start a little back from the live edge, about where the player was showing
                int from = uris.size();
                long back = 0;
                while (from > 0 && back < 10_000) back += durs.get(--from);
                seq = firstSeq + from;
                first = false;
            }
            boolean got = false;
            for (int i = 0; i < uris.size() && !closed; i++) {
                long s = firstSeq + i;
                if (s < seq) continue;
                byte[] seg;
                try {
                    seg = fetchBytes(uris.get(i));
                } catch (IOException e) {
                    // retried with the next reload of the playlist
                    break;
                }
                if (!checked) {
                    if (seg.length == 0 || (seg[0] & 0xFF) != 0x47) throw new Unsupported("Pause indisponible sur cette chaîne (format)");
                    checked = true;
                }
                mark(mediaEnd);
                write(seg, seg.length);
                mediaEnd += durs.get(i);
                seq = s + 1;
                got = true;
            }
            // the playlist was reset (a restarted encoder): follow it
            if (seq > firstSeq + uris.size() + 3) seq = firstSeq;
            if (text.contains("#EXT-X-ENDLIST")) return;
            if (!got) Thread.sleep(Math.max(1000, target * 500));
        }
    }

    private String lastUrl;

    private String fetchText(String u) throws IOException {
        return new String(fetchBytes(u), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] fetchBytes(String u) throws IOException {
        try (Response r = http.newCall(new Request.Builder().url(u).header("User-Agent", Net.agent).build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            lastUrl = r.request().url().toString();
            return r.body().bytes();
        }
    }

    private static String resolve(String base, String ref) {
        return java.net.URI.create(base).resolve(ref.replace(" ", "%20")).toString();
    }

    private void mark(long ms) {
        synchronized (lock) {
            index.add(new long[] {ms, written - written % TS_PACKET});
            // forget what the ring has overwritten
            long floor = written - cap;
            while (index.size() > 1 && index.get(1)[1] <= floor) index.remove(0);
        }
    }

    private void write(byte[] b, int n) throws IOException {
        synchronized (lock) {
            if (closed) return;
            int done = 0;
            while (done < n) {
                long at = (written + done) % cap;
                int len = (int) Math.min(n - done, cap - at);
                out.seek(at);
                out.write(b, done, len);
                done += len;
            }
            written += n;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- playing

    /** blocks until bytes at [pos] are written; fails if the recording stopped or overtook it */
    private int read(RandomAccessFile in, long pos, byte[] b, int off, int len) throws IOException {
        synchronized (lock) {
            while (!closed && error == null && written <= pos) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (closed) throw new IOException("closed");
            if (written <= pos) throw new IOException(error);
            if (pos < written - cap) throw new IOException("Le direct en pause a dépassé la durée gardée");
            long at = pos % cap;
            int n = (int) Math.min(len, Math.min(written - pos, cap - at));
            in.seek(at);
            return in.read(b, off, n);
        }
    }

    /** reads the recording from a byte offset, as an endless MPEG-TS stream */
    final class Source extends BaseDataSource {
        private final long start;
        private RandomAccessFile in;
        private long pos;

        Source(long start) {
            super(false);
            this.start = start;
        }

        @Override
        public long open(@NonNull DataSpec spec) throws IOException {
            transferInitializing(spec);
            in = new RandomAccessFile(file, "r");
            pos = start + spec.position;
            transferStarted(spec);
            return C.LENGTH_UNSET;
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int n = Timeshift.this.read(in, pos, b, off, len);
            if (n > 0) {
                pos += n;
                bytesTransferred(n);
            }
            return n;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("timeshift://" + start);
        }

        @Override
        public void close() throws IOException {
            if (in != null) {
                in.close();
                in = null;
                transferEnded();
            }
        }
    }
}
