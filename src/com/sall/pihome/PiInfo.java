package com.sall.pihome;

import android.app.ActivityManager;
import android.content.Context;
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

    static String system() {
        return "Android " + Build.VERSION.RELEASE + " · " + Build.MODEL;
    }

    private static String gb(long bytes) {
        return String.format(Locale.FRANCE, "%.1f Go", bytes / 1073741824.0);
    }

    private PiInfo() {
    }
}
