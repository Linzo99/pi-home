package com.sall.pihome;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** New versions of the apps installed from GitHub, which the Play Store never updates. */
final class Updates {
    /** package, GitHub repository, and a piece of the right APK's file name for the Pi 4 */
    private static final String[][] TRACKED = {
            {"org.smarttube.stable", "yuliskov/SmartTube", "stable", "arm64-v8a"},
            {"com.nuvio.tv", "NuvioMedia/NuvioTV", "app-full", "arm64-v8a"},
    };

    static final class Update {
        String pkg, label, installed, latest, url;
        long size;
    }

    /** call off the main thread */
    static List<Update> check(Context c) {
        List<Update> out = new ArrayList<>();
        for (String[] t : TRACKED) {
            try {
                PackageInfo pi = c.getPackageManager().getPackageInfo(t[0], 0);
                JSONObject rel = new JSONObject(Weather.get("https://api.github.com/repos/" + t[1] + "/releases/latest"));
                String latest = rel.getString("tag_name");
                if (newer(latest, pi.versionName) <= 0) continue;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.getString("name");
                    if (!name.endsWith(".apk") || !name.contains(t[2]) || !name.contains(t[3])) continue;
                    Update u = new Update();
                    u.pkg = t[0];
                    u.label = c.getPackageManager().getApplicationLabel(pi.applicationInfo).toString();
                    u.installed = pi.versionName;
                    u.latest = latest.replaceAll("[^0-9.]", "");
                    u.url = a.getString("browser_download_url");
                    u.size = a.getLong("size");
                    out.add(u);
                    break;
                }
            } catch (Exception ignored) {
                // not installed, offline, or GitHub's rate limit: try again next time
            }
        }
        return out;
    }

    /** > 0 when tag a ("32.60s", "v1.1.0") is a newer version than b */
    static int newer(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p - q;
        }
        return 0;
    }

    /** downloads the APK into the app's files, where the root bridge can install it from */
    static File download(Context c, Update u) throws Exception {
        File dir = new File(c.getFilesDir(), "updates");
        dir.mkdirs();
        File f = new File(dir, u.pkg + ".apk");
        HttpURLConnection con = (HttpURLConnection) new URL(u.url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        try (InputStream in = con.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        } finally {
            con.disconnect();
        }
        if (u.size > 0 && f.length() != u.size) {
            f.delete();
            throw new Exception("incomplete download");
        }
        f.setReadable(true, false);
        dir.setExecutable(true, false);
        return f;
    }

    private Updates() {
    }
}
