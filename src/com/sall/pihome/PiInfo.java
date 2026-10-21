package com.sall.pihome;

import android.app.ActivityManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** The figures shown in the "Infos Pi" panel. */
final class PiInfo {
    /** °C, or NaN when the sensor can't be read */
    static float temperature() {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone0/temp"))) {
            return Integer.parseInt(r.readLine().trim()) / 1000f;
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    static String memory(Context c) {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(m);
        return gb(m.availMem) + " libres sur " + gb(m.totalMem);
    }

    static String storage() {
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        return gb(s.getAvailableBytes()) + " libres sur " + gb(s.getTotalBytes());
    }

    static String address(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return "hors ligne";
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return "inconnue";
    }

    static String uptime() {
        long min = SystemClock.elapsedRealtime() / 60000;
        long d = min / 1440, h = min / 60 % 24, m = min % 60;
        if (d > 0) return d + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }

    /**
     * Wi-Fi and Ethernet data today and this month, plus the apps that used the most this month.
     * Android saves these figures about every 30 minutes. Needs PACKAGE_USAGE_STATS (privileged,
     * whitelisted); call off the main thread.
     */
    static String dataUsage(Context c) {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        Calendar month = (Calendar) day.clone();
        month.set(Calendar.DAY_OF_MONTH, 1);
        long now = System.currentTimeMillis();
        NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
        PackageManager pm = c.getPackageManager();
        Map<String, Long> byApp = new HashMap<>();
        long today = 0, total = 0;
        try {
            for (int type : new int[] {android.net.ConnectivityManager.TYPE_WIFI, android.net.ConnectivityManager.TYPE_ETHERNET}) {
                today += deviceBytes(nsm, type, day.getTimeInMillis(), now);
                total += deviceBytes(nsm, type, month.getTimeInMillis(), now);
                NetworkStats s = nsm.querySummary(type, null, month.getTimeInMillis(), now);
                if (s == null) continue; // no such network, e.g. no Ethernet cable ever plugged in
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (s.getNextBucket(b)) byApp.merge(appName(pm, b.getUid()), b.getRxBytes() + b.getTxBytes(), Long::sum);
                s.close();
            }
        } catch (Exception e) {
            android.util.Log.w("PiHome", "data usage", e);
            return "Données : indisponible";
        }
        List<Map.Entry<String, Long>> top = new ArrayList<>(byApp.entrySet());
        top.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        StringBuilder sb = new StringBuilder("Données : " + size(today) + " aujourd'hui · " + size(total) + " ce mois");
        for (int i = 0; i < Math.min(3, top.size()) && top.get(i).getValue() >= 1048576; i++) {
            sb.append(i == 0 ? "\nCe mois : " : " · ").append(top.get(i).getKey()).append(' ').append(size(top.get(i).getValue()));
        }
        return sb.toString();
    }

    private static long deviceBytes(NetworkStatsManager nsm, int type, long start, long end) throws Exception {
        NetworkStats.Bucket b = nsm.querySummaryForDevice(type, null, start, end);
        return b == null ? 0 : b.getRxBytes() + b.getTxBytes();
    }

    private static String appName(PackageManager pm, int uid) {
        if (uid == NetworkStats.Bucket.UID_REMOVED) return "Apps supprimées";
        if (uid == NetworkStats.Bucket.UID_TETHERING) return "Partage de connexion";
        if (uid < 10000) return "Android";
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return "Apps supprimées";
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkgs[0];
        }
    }

    private static String size(long bytes) {
        return bytes >= 1073741824 ? gb(bytes) : String.format(Locale.FRANCE, "%.0f Mo", bytes / 1048576.0);
    }

    static String system() {
        return "Android " + Build.VERSION.RELEASE + " · " + Build.MODEL;
    }

    private static String gb(long bytes) {
        return String.format(Locale.FRANCE, "%.1f Go", bytes / 1073741824.0);
    }

    private PiInfo() {
    }
}
