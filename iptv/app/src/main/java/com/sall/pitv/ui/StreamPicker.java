package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sources of a film or an episode, as they answer: the sources on the left, each with how
 * its search is going, and the chosen one's links on the right. Nothing plays until a link is
 * picked.
 *
 * Callers announce each search with pending(name) and report it with done(name, streams), on the
 * main thread. Streams are listed under their own source (Stream.addon), so one search may bring
 * several sources.
 */
public final class StreamPicker {
    interface Pick {
        void onPick(Addons.Stream s);
    }

    private static final int SEARCHING = 0, DONE = 1, FAILED = 2;
    /** the first row: every link, as they come */
    private static final String ALL = "";

    private static final class Source {
        final String name;
        int state = SEARCHING;
        /** its links came early, under their own sources' rows */
        boolean gave;
        final List<Addons.Stream> streams = new ArrayList<>();

        Source(String name) {
            this.name = name;
        }
    }

    private final MainActivity a;
    private final Pick pick;
    private final Dialog dialog;
    private final LinearLayout sourceList, linkList;
    private final TextView status;
    private final ProgressBar spinner;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final List<Addons.Stream> all = new ArrayList<>();
    private final Map<String, TextView> sourceRows = new LinkedHashMap<>();
    private String selected = ALL;
    private boolean closed;
    /** the link last played, highlighted again on coming back from the player */
    private Addons.Stream played;
    /** the source that played this title last time: taken the moment it answers, unless a key comes first */
    private String resume;
    private final android.widget.ImageView backdrop;
    /** how the links are ordered: by quality, by language, or by source; kept for next time */
    private static final String[] SORTS = {"Qualité", "Langue", "Source"};
    private int sort;
    private final TextView sortButton;

    StreamPicker(MainActivity a, String title, Pick pick) {
        this.a = a;
        this.pick = pick;
        dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.setOnDismissListener(d -> closed = true);
        dialog.setOnKeyListener((d, code, e) -> {
            // choosing by hand: no automatic resume
            if (e.getAction() == KeyEvent.ACTION_DOWN && resume != null) {
                resume = null;
                update();
            }
            return false;
        });

        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF0B0B0B);
        // the title's picture, blurred and dimmed: the window is a sheet of glass over it
        backdrop = new android.widget.ImageView(a);
        backdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        Ui.blur(backdrop, a.dp(30));
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(a);
        dim.setBackgroundColor(0xB3000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(48), a.dp(32), a.dp(48), a.dp(24));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = Ui.line(a, 24, Color.WHITE, true);
        h.setText(title);
        top.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        sort = Math.max(0, Math.min(SORTS.length - 1, a.getSharedPreferences("player", android.content.Context.MODE_PRIVATE).getInt("pickSort", 0)));
        sortButton = Ui.button(a, 0, "Trier : " + SORTS[sort], v -> {
            sort = (sort + 1) % SORTS.length;
            a.getSharedPreferences("player", android.content.Context.MODE_PRIVATE).edit().putInt("pickSort", sort).apply();
            ((TextView) v).setText("Trier : " + SORTS[sort]);
            showLinks();
        });
        top.addView(sortButton);
        col.addView(top);
        LinearLayout sl = new LinearLayout(a);
        sl.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(a);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.RED));
        sl.addView(spinner, new LinearLayout.LayoutParams(a.dp(16), a.dp(16)));
        status = Ui.line(a, 13, Ui.DIM, false);
        sl.addView(status, Ui.margins(-2, -2, a.dp(8), 0, 0, 0));
        col.addView(sl, Ui.margins(-1, -2, 0, a.dp(8), 0, a.dp(18)));

        LinearLayout cols = new LinearLayout(a);
        col.addView(cols, new LinearLayout.LayoutParams(-1, 0, 1f));
        sourceList = new LinearLayout(a);
        sourceList.setOrientation(LinearLayout.VERTICAL);
        cols.addView(scroll(sourceList), new LinearLayout.LayoutParams(a.dp(260), -1));
        linkList = new LinearLayout(a);
        linkList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = a.dp(20);
        cols.addView(scroll(linkList), lp);

        TextView hint = Ui.line(a, 11, Ui.FAINT, false);
        hint.setText("Haut : trier   ·   Haut / bas : choisir une source   ·   Droite : ses liens   ·   OK : lire (Retour y revient)   ·   Retour : fermer");
        col.addView(hint, Ui.margins(-1, -2, 0, a.dp(12), 0, 0));

        dialog.setContentView(root);
        root.getViewTreeObserver().addOnWindowFocusChangeListener(has -> {
            if (has && root.findFocus() == null) refocus();
        });
        sources.put(ALL, new Source("Toutes les sources"));
        update();
    }

    private ScrollView scroll(View content) {
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(content);
        return sv;
    }

    void show() {
        dialog.show();
        sourceRows.get(ALL).requestFocus();
    }

    /** back from the player: on the link played if it's still listed, else on its source */
    private void refocus() {
        for (int i = 0; played != null && i < linkList.getChildCount(); i++) {
            View v = linkList.getChildAt(i);
            if (v.getTag() == played) {
                v.requestFocus();
                return;
            }
        }
        TextView src = sourceRows.get(selected);
        if (src != null) src.requestFocus();
    }

    boolean closed() {
        return closed;
    }

    /** the links found so far, best first; the list goes on growing while the search runs */
    List<Addons.Stream> streams() {
        return all;
    }

    /** the title's picture behind the window */
    void backdrop(String url) {
        Images.get(a).load(backdrop, url, a.dp(320), a.dp(180), false);
    }

    void resumeWith(String source) {
        resume = source;
        update();
    }

    private void tryResume() {
        if (resume == null || played != null) return;
        for (Addons.Stream s : all) {
            if (resume.equals(s.source())) {
                resume = null;
                played = s;
                showLinks();
                pick.onPick(s);
                return;
            }
        }
    }

    /** a search has started; its row says so until done */
    void pending(String name) {
        if (closed) return;
        if (!sources.containsKey(name)) sources.put(name, new Source(name));
        update();
    }

    /** some of a search's links, before it ends: they can be played at once */
    void partial(String name, List<Addons.Stream> found) {
        if (closed || found.isEmpty()) return;
        boolean ownLinks = false;
        for (Addons.Stream s : found) {
            String src = s.addon == null || s.addon.isEmpty() ? name : s.addon;
            if (src.equals(name)) {
                ownLinks = true;
                continue;
            }
            Source x = sources.get(src);
            if (x == null) sources.put(src, x = new Source(src));
            x.state = DONE;
            x.streams.add(s);
            all.add(s);
        }
        Source own = sources.get(name);
        if (own != null && !ownLinks) own.gave = true;
        // the best quality first, as the full list was; equal ones keep their arrival order
        Comparator<Addons.Stream> best = Comparator.comparingInt((Addons.Stream s) -> Plugins.quality(s.name + " " + s.title)).reversed();
        all.sort(best);
        for (Source x : sources.values()) x.streams.sort(best);
        update();
    }

    /** a search has answered: its links (null when it failed) */
    void done(String name, List<Addons.Stream> found) {
        if (closed) return;
        Source own = sources.get(name);
        if (own != null && own.gave && found != null) {
            // its links came through partial: the row gives way to theirs
            sources.remove(name);
            update();
            return;
        }
        if (found == null) {
            if (own == null) sources.put(name, own = new Source(name));
            own.state = FAILED;
        } else {
            boolean ownLinks = false;
            for (Addons.Stream s : found) {
                String src = s.addon == null || s.addon.isEmpty() ? name : s.addon;
                Source x = sources.get(src);
                if (x == null) sources.put(src, x = new Source(src));
                x.state = DONE;
                x.streams.add(s);
                all.add(s);
                if (src.equals(name)) ownLinks = true;
            }
            // a search that brought other sources' links (the plugins) gives way to them
            if (own != null && !ownLinks && !found.isEmpty()) sources.remove(name);
            else if (own != null) own.state = DONE;
            else if (found.isEmpty()) {
                own = new Source(name);
                own.state = DONE;
                sources.put(name, own);
            }
        }
        update();
    }

    // ---------------------------------------------------------------- display

    private boolean searching() {
        for (Map.Entry<String, Source> e : sources.entrySet()) if (!e.getKey().equals(ALL) && e.getValue().state == SEARCHING) return true;
        return false;
    }

    private void update() {
        boolean busy = searching();
        int n = 0, answered = 0;
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            if (e.getKey().equals(ALL)) continue;
            n++;
            if (e.getValue().state != SEARCHING) answered++;
        }
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText((resume != null ? "Reprise avec " + resume.split("\\|")[0] + " dès qu'il répond (une touche pour choisir)  ·  " : "")
                + (busy ? "Recherche dans " + n + " sources…  " + answered + " ont répondu  ·  " + links(all.size())
                : all.isEmpty() ? "Aucun lien trouvé." : links(all.size()) + " dans " + countWithLinks() + " sources"));
        Source allRow = sources.get(ALL);
        allRow.state = busy ? SEARCHING : DONE;

        // the rows, in order; the focused one is kept
        View focus = dialog.getCurrentFocus();
        int i = 0;
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            String key = e.getKey();
            TextView row = sourceRows.get(key);
            if (row == null) {
                row = sourceRow(key);
                sourceRows.put(key, row);
                sourceList.addView(row, i, Ui.margins(-1, -2, 0, 0, 0, a.dp(4)));
            }
            styleSource(row, key);
            i++;
        }
        for (String key : new ArrayList<>(sourceRows.keySet())) {
            if (sources.containsKey(key)) continue;
            TextView gone = sourceRows.remove(key);
            boolean had = gone.hasFocus();
            sourceList.removeView(gone);
            if (had) sourceRows.get(ALL).requestFocus();
            if (key.equals(selected)) select(ALL);
        }
        // the links shown: refilled, unless the highlight is in them (then only added to)
        if (!linkList.hasFocus()) showLinks();
        else appendLinks();
        if (focus != null && focus.getParent() == null) sourceRows.get(ALL).requestFocus();
        tryResume();
    }

    private int countWithLinks() {
        int n = 0;
        for (Map.Entry<String, Source> e : sources.entrySet()) if (!e.getKey().equals(ALL) && !e.getValue().streams.isEmpty()) n++;
        return n;
    }

    private static String links(int n) {
        return n == 0 ? "aucun lien" : n == 1 ? "1 lien" : n + " liens";
    }

    private TextView sourceRow(String key) {
        TextView t = Ui.text(a, 14, Color.WHITE, true);
        t.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, has) -> {
            if (has && !key.equals(selected)) select(key);
            for (Map.Entry<String, TextView> e : sourceRows.entrySet()) styleSource(e.getValue(), e.getKey());
        });
        t.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (linkList.getChildCount() > 0 && linkList.getChildAt(0).isFocusable()) linkList.getChildAt(0).requestFocus();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_LEFT;
        });
        return t;
    }

    private void styleSource(TextView t, String key) {
        Source s = sources.get(key);
        if (s == null) return;
        int n = key.equals(ALL) ? all.size() : s.streams.size();
        String state = s.state == SEARCHING ? (n > 0 ? n + "  ·  recherche…" : "recherche…")
                : s.state == FAILED ? "pas de réponse" : n == 0 ? "aucun lien" : links(n);
        t.setText(s.name + "\n" + state);
        boolean f = t.hasFocus(), on = key.equals(selected);
        t.setBackground(f ? Ui.rounded(Color.WHITE, a.dp(14)) : on ? Ui.glass(a, a.dp(14), 1) : null);
        t.setTextColor(f ? Color.BLACK : s.state == DONE && n == 0 || s.state == FAILED ? Ui.FAINT : Color.WHITE);
    }

    private void select(String key) {
        selected = key;
        showLinks();
    }

    private List<Addons.Stream> shownStreams() {
        Source s = sources.get(selected);
        List<Addons.Stream> l = new ArrayList<>(selected.equals(ALL) || s == null ? all : s.streams);
        // the lists are already best quality first: the other orders keep it within their groups
        if (sort == 1) l.sort(Comparator.comparingInt(this::languageRank));
        else if (sort == 2) l.sort(Comparator.comparing((Addons.Stream x) -> x.addon == null ? "" : x.addon.toLowerCase(java.util.Locale.ROOT)));
        return l;
    }

    /** the language chosen last in the player first, then French, then the rest; unknown last */
    private int languageRank(Addons.Stream x) {
        String l = x.language();
        if (l.isEmpty()) return 99;
        String pref = a.getSharedPreferences("player", android.content.Context.MODE_PRIVATE).getString("srcLang", null);
        if (l.equals(pref)) return 0;
        int i = java.util.Arrays.asList("VF", "MULTI", "VOSTFR", "VO", "Espagnol", "Italien", "Allemand").indexOf(l);
        return i < 0 ? 50 : i + 1;
    }

    private void showLinks() {
        linkList.removeAllViews();
        appendLinks();
    }

    /** the links not yet shown for the selected source, at the end of the list */
    private void appendLinks() {
        List<Addons.Stream> l = shownStreams();
        // the placeholder line goes once links come
        if (linkList.getChildCount() == 1 && !linkList.getChildAt(0).isFocusable()) linkList.removeAllViews();
        // those that came in the middle of the order are added at the end, until the list is redrawn
        java.util.Set<Object> shown = new java.util.HashSet<>();
        for (int i = 0; i < linkList.getChildCount(); i++) shown.add(linkList.getChildAt(i).getTag());
        for (Addons.Stream x : l) if (!shown.contains(x)) linkList.addView(linkRow(x), Ui.margins(-1, -2, 0, 0, 0, a.dp(4)));
        if (linkList.getChildCount() == 0) {
            Source s = sources.get(selected);
            TextView none = Ui.text(a, 13, Ui.DIM, false);
            none.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            none.setText(s != null && s.state == SEARCHING ? "Recherche en cours…" : s != null && s.state == FAILED ? "Cette source n'a pas répondu." : "Aucun lien ici.");
            linkList.addView(none);
        }
    }

    private View linkRow(Addons.Stream s) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        row.setFocusable(true);
        row.setTag(s);
        row.setBackground(Ui.glass(a, a.dp(14), 0));
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.line(a, 14, Color.WHITE, true);
        String n = s.name == null ? "" : s.name.replace('\n', ' ').trim();
        name.setText((selected.equals(ALL) ? s.addon + (n.isEmpty() || n.equals(s.addon) ? "" : "  ·  " + n) : n.isEmpty() ? s.addon : n));
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        // its language and quality at a glance, as the sorting sees them
        String q = new String[] {"", "360p", "480p", "720p", "1080p", "4K"}[s.quality()];
        String tags = s.language() + (s.language().isEmpty() || q.isEmpty() ? "" : "  ·  ") + q;
        TextView badge = Ui.line(a, 12, Color.WHITE, true);
        badge.setText(tags);
        badge.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        line.addView(badge, Ui.margins(-2, -2, a.dp(12), 0, 0, 0));
        row.addView(line);
        String t = s.title == null ? "" : s.title.replace("\n", "  ·  ").trim();
        TextView detail = Ui.text(a, 12, Ui.DIM, false);
        detail.setMaxLines(2);
        detail.setText(t);
        detail.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        row.addView(detail, Ui.margins(-1, -2, 0, a.dp(3), 0, 0));
        row.setOnFocusChangeListener((v, has) -> {
            row.setBackground(has ? Ui.rounded(Color.WHITE, a.dp(14)) : Ui.glass(a, a.dp(14), 0));
            name.setTextColor(has ? Color.BLACK : Color.WHITE);
            badge.setTextColor(has ? Color.BLACK : Color.WHITE);
            detail.setTextColor(has ? 0xB3000000 : Ui.DIM);
        });
        // the window stays under the player: Back from it comes back to these links
        row.setOnClickListener(v -> {
            played = s;
            pick.onPick(s);
        });
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                TextView back = sourceRows.get(selected);
                if (back != null) back.requestFocus();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        return row;
    }
}
