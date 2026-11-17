package com.sall.pitv;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Adult add-ons stay out of sight until a 4-digit code is typed: their rows leave every page,
 * search and the links skip them, and what they play is never written into the history. The
 * code opens them until Pi TV leaves the screen. It is typed on an on-screen keypad, since the
 * TV doesn't pass its number keys on.
 */
final class Adult {
    /** opened with the code; shut again once no Pi TV screen is showing */
    static volatile boolean unlocked;

    private static final String PREFS = "player", HASH = "adult.pin", SALT = "adult.salt";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int showing;
    private static final Runnable relock = () -> unlocked = false;

    // ---------------------------------------------------------------- shutting again

    /** a Pi TV screen came up */
    static void started() {
        showing++;
        main.removeCallbacks(relock);
    }

    /** a Pi TV screen went away: with none left (Home, another app, sleep), it shuts */
    static void stopped() {
        showing = Math.max(0, showing - 1);
        // a moment's grace: going from the list to the player stops one screen before the next starts
        if (showing == 0) main.postDelayed(relock, 3_000);
    }

    // ---------------------------------------------------------------- the code

    static boolean hasCode(Context c) {
        return prefs(c).contains(HASH);
    }

    private static boolean matches(Context c, String code) {
        SharedPreferences p = prefs(c);
        return p.contains(HASH) && hash(p.getString(SALT, ""), code).equals(p.getString(HASH, ""));
    }

    private static void save(Context c, String code) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder salt = new StringBuilder();
        for (byte x : b) salt.append(String.format("%02x", x));
        prefs(c).edit().putString(SALT, salt.toString()).putString(HASH, hash(salt.toString(), code)).apply();
    }

    private static String hash(String salt, String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + code).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- asking

    /** opens with the code, choosing one the first time; [then] runs once open */
    static void open(MainActivity a, Runnable then) {
        if (unlocked) {
            then.run();
            return;
        }
        if (!hasCode(a)) {
            keypad(a, "Choisissez un code à 4 chiffres pour le contenu adulte", first -> {
                a.ui.post(() -> keypad(a, "Confirmez le code", again -> {
                    if (!again.equals(first)) return "Les deux codes diffèrent";
                    save(a, first);
                    unlocked = true;
                    then.run();
                    return null;
                }));
                return null;
            });
            return;
        }
        keypad(a, "Code du contenu adulte", code -> {
            if (!matches(a, code)) return "Code incorrect";
            unlocked = true;
            then.run();
            return null;
        });
    }

    /** a new code, after the current one */
    static void change(MainActivity a) {
        Runnable choose = () -> keypad(a, "Nouveau code à 4 chiffres", first -> {
            a.ui.post(() -> keypad(a, "Confirmez le nouveau code", again -> {
                if (!again.equals(first)) return "Les deux codes diffèrent";
                save(a, first);
                android.widget.Toast.makeText(a, "Code adulte enregistré", android.widget.Toast.LENGTH_SHORT).show();
                return null;
            }));
            return null;
        });
        if (!hasCode(a)) {
            choose.run();
            return;
        }
        keypad(a, "Code actuel", code -> {
            if (!matches(a, code)) return "Code incorrect";
            a.ui.post(choose);
            return null;
        });
    }

    /** told the 4 digits; answers null when they do, or what's wrong (shown, and typed again) */
    interface Typed {
        String done(String code);
    }

    private static void keypad(MainActivity a, String title, Typed typed) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(a.dp(32), a.dp(26), a.dp(32), a.dp(26));
        box.setBackground(Ui.rounded(0xF01C1C1E, a.dp(18)));
        TextView t = Ui.text(a, 16, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setText(title);
        box.addView(t, new LinearLayout.LayoutParams(a.dp(340), -2));
        TextView dots = Ui.line(a, 28, Color.WHITE, true);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.3f);
        box.addView(dots, Ui.margins(-2, -2, 0, a.dp(14), 0, a.dp(4)));
        TextView err = Ui.line(a, 12, Ui.RED, false);
        err.setGravity(Gravity.CENTER);
        box.addView(err, Ui.margins(-2, -2, 0, 0, 0, a.dp(12)));

        StringBuilder code = new StringBuilder();
        Runnable draw = () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 4; i++) s.append(i < code.length() ? "●" : "○");
            dots.setText(s);
        };
        draw.run();
        java.util.function.Consumer<String> key = k -> {
            if (k.equals("⌫")) {
                if (code.length() > 0) code.setLength(code.length() - 1);
            } else if (code.length() < 4) code.append(k);
            err.setText("");
            draw.run();
            if (code.length() < 4) return;
            String wrong = typed.done(code.toString());
            if (wrong == null) {
                d.dismiss();
                return;
            }
            err.setText(wrong);
            code.setLength(0);
            draw.run();
        };
        String[][] grid = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "⌫"}};
        TextView five = null;
        for (String[] line : grid) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER);
            for (String k : line) {
                View b;
                if (k.isEmpty()) {
                    b = new View(a);
                } else {
                    TextView btn = Ui.button(a, 0, k, v -> key.accept(k));
                    btn.setGravity(Gravity.CENTER);
                    btn.setTextSize(20);
                    if (k.equals("5")) five = btn;
                    b = btn;
                }
                row.addView(b, Ui.margins(a.dp(76), a.dp(56), a.dp(5), a.dp(5), a.dp(5), a.dp(5)));
            }
            box.addView(row);
        }
        // a remote with number keys types them straight in
        d.setOnKeyListener((di, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                key.accept(String.valueOf(k - KeyEvent.KEYCODE_0));
                return true;
            }
            if (k == KeyEvent.KEYCODE_DEL) {
                key.accept("⌫");
                return true;
            }
            return false;
        });
        d.setContentView(box);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        d.show();
        if (five != null) five.requestFocus();
    }

    private Adult() {
    }
}
