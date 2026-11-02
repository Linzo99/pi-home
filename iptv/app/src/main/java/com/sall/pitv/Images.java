package com.sall.pitv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters, logos and backdrops: downloaded once to the cache folder, decoded at the size they
 * are shown (the Pi has 2 GB for everything), and kept in a small memory cache.
 */
final class Images {
    private static Images instance;
    private final File dir;
    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    static synchronized Images get(Context c) {
        if (instance == null) instance = new Images(c.getApplicationContext());
        return instance;
    }

    private Images(Context c) {
        dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
        int kb = (int) Math.min(48 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 6);
        mem = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getAllocationByteCount() / 1024;
            }
        };
    }

    /** shows url in v at about w×h pixels; alpha for logos, which are often transparent */
    void load(ImageView v, String url, int w, int h, boolean alpha) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) {
            v.setTag(R.id.image_url, null);
            v.setImageDrawable(null);
            return;
        }
        String key = url + "@" + w;
        v.setTag(R.id.image_url, key);
        Bitmap b = mem.get(key);
        if (b != null) {
            v.setImageBitmap(b);
            return;
        }
        v.setImageDrawable(null);
        pool.execute(() -> {
            if (!key.equals(v.getTag(R.id.image_url))) return;
            Bitmap bm = fetch(url, w, h, alpha);
            if (bm == null) return;
            mem.put(key, bm);
            ui.post(() -> {
                if (key.equals(v.getTag(R.id.image_url))) {
                    v.setImageBitmap(bm);
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    private Bitmap fetch(String url, int w, int h, boolean alpha) {
        try {
            File f = new File(dir, md5(url));
            if (!f.exists()) {
                byte[] data = Net.bytes(url);
                File tmp = new File(f.getPath() + ".part");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(data);
                }
                tmp.renameTo(f);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) {
                f.delete();
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (Throwable e) {
            return null;
        }
    }

    /** keeps the disk cache under about 300 MB, dropping the oldest files */
    void trim() {
        pool.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            long total = 0;
            for (File f : files) total += f.length();
            for (File f : files) {
                if (total < 300L * 1024 * 1024) break;
                total -= f.length();
                f.delete();
            }
        });
    }

    private static String md5(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes());
        StringBuilder b = new StringBuilder();
        for (byte x : h) b.append(String.format("%02x", x));
        return b.toString();
    }
}
