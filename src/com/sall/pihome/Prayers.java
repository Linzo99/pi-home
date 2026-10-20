package com.sall.pihome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Today's prayer times from Aladhan (Muslim World League method), at the weather's location. */
final class Prayers {
    static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};

    static final class Next {
        String name;
        Calendar at;
    }

    /** today's times as "HH:mm", cached for the day; call off the main thread */
    static String[] today(Context c) {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        String day = new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date());
        String cached = p.getString("prayers." + day, null);
        if (cached != null) return cached.split(",");
        try {
            float[] loc = Weather.location(c);
            JSONObject t = new JSONObject(Weather.get(String.format(Locale.US,
                    "https://api.aladhan.com/v1/timings/%s?latitude=%.4f&longitude=%.4f&method=3", day, loc[0], loc[1])))
                    .getJSONObject("data").getJSONObject("timings");
            String[] out = new String[NAMES.length];
            for (int i = 0; i < NAMES.length; i++) out[i] = t.getString(NAMES[i]).substring(0, 5);
            p.edit().putString("prayers." + day, String.join(",", out)).apply();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** the next prayer still to come today, or null after Isha */
    static Next next(String[] times) {
        if (times == null) return null;
        Calendar now = Calendar.getInstance();
        for (int i = 0; i < NAMES.length; i++) {
            String[] hm = times[i].split(":");
            Calendar at = (Calendar) now.clone();
            at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
            at.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (at.after(now)) {
                Next n = new Next();
                n.name = NAMES[i];
                n.at = at;
                return n;
            }
        }
        return null;
    }

    private Prayers() {
    }
}
