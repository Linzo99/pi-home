package com.sall.pihome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current weather from Open-Meteo (no account needed). The location comes from the public IP via
 * ipapi.co, looked up at most once a day.
 */
final class Weather {
    static final class Now {
        String text;
        int icon;
    }

    private static final long LOCATION_TTL = 24 * 3600_000L;

    /** call off the main thread; null when offline or a service fails */
    static Now fetch(Context c) {
        try {
            float[] loc = location(c);
            float lat = loc[0], lon = loc[1];
            String city = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("geo.city", "");
            JSONObject cur = new JSONObject(get(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon)))
                    .getJSONObject("current");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            Now n = new Now();
            n.text = Math.round(cur.getDouble("temperature_2m")) + "° · " + describe(code, day)
                    + (city.isEmpty() ? "" : " · " + city);
            n.icon = code <= 1 ? (day ? R.drawable.ic_sun : R.drawable.ic_sleep) : R.drawable.ic_cloud;
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    /** {latitude, longitude} from the public IP, refreshed once a day; shared with the prayer times */
    static float[] location(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        float lat = p.getFloat("geo.lat", Float.NaN), lon = p.getFloat("geo.lon", Float.NaN);
        if (Float.isNaN(lat) || System.currentTimeMillis() - p.getLong("geo.at", 0) > LOCATION_TTL) {
            JSONObject geo = new JSONObject(get("https://ipapi.co/json/"));
            lat = (float) geo.getDouble("latitude");
            lon = (float) geo.getDouble("longitude");
            p.edit().putString("geo.city", geo.optString("city", "")).putFloat("geo.lat", lat)
                    .putFloat("geo.lon", lon).putLong("geo.at", System.currentTimeMillis()).apply();
        }
        return new float[] {lat, lon};
    }

    /** WMO weather codes, as Open-Meteo reports them */
    private static String describe(int code, boolean day) {
        if (code == 0) return day ? "Ensoleillé" : "Dégagé";
        if (code <= 2) return "Éclaircies";
        if (code == 3) return "Nuageux";
        if (code == 45 || code == 48) return "Brouillard";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "Pluie";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neige";
        if (code >= 95) return "Orage";
        return "Variable";
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", "PiHome/1.9");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private Weather() {
    }
}
