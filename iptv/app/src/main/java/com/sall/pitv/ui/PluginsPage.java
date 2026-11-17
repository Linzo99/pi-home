package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The Plugins page: Nuvio JavaScript repositories, and which of their scrapers run. The
 * repositories and the sources they find are the user's choice; the app ships none.
 */
public final class PluginsPage {
    final View view;
    private final MainActivity a;
    private final LinearLayout list;
    private final EditText url;
    private final TextView addButton;
    private final TextView busy;
    private final Runnable onChange;
    /** the repositories shown open, by link; closed by default so a long list stays short */
    private static final java.util.Set<String> open = new java.util.HashSet<>();

    PluginsPage(MainActivity a) {
        this.a = a;
        this.onChange = () -> a.ui.post(this::refresh);
        Plugins p = Plugins.get(a);
        p.onChange = onChange;

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.VERTICAL);
        cols.setPadding(a.dp(40), a.dp(28), a.dp(40), a.dp(40));
        sv.addView(cols);
        view = sv;

        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutParams(new LinearLayout.LayoutParams(a.dp(760), -2));
        cols.addView(form);

        TextView h = Ui.text(a, 24, Color.WHITE, true);
        h.setText("Plugins");
        form.addView(h);

        TextView intro = Ui.text(a, 13, Ui.DIM, false);
        intro.setText("Des dépôts de scrapers Nuvio (fichiers JavaScript) ajoutent des sources françaises pour les films, séries et animes. Les liens directs sont lus ici ; les torrents sont ignorés.");
        intro.setLineSpacing(a.dp(3), 1f);
        form.addView(intro, Ui.margins(-1, -2, 0, a.dp(6), 0, a.dp(16)));

        url = new EditText(a);
        url.setSingleLine(true);
        url.setHint("https://…/manifest.json");
        url.setHintTextColor(0x55FFFFFF);
        url.setTextColor(Color.WHITE);
        url.setTextSize(14);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        url.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        url.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        url.setBackground(Ui.rounded(0xFF262626, a.dp(6)));
        url.setOnFocusChangeListener((v, has) -> url.setBackground(has ? Ui.outline(Color.WHITE, a.dp(2), a.dp(6)) : Ui.rounded(0xFF262626, a.dp(6))));
        form.addView(url, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addButton = Ui.button(a, 0, "Ajouter le dépôt", v -> add());
        actions.addView(addButton);
        // down from a long link would follow the cursor, at the right, to an Actualiser button
        addButton.setId(View.generateViewId());
        url.setNextFocusDownId(addButton.getId());
        url.setOnEditorActionListener((v, action, e) -> {
            add();
            return true;
        });
        busy = Ui.line(a, 12, Ui.FAINT, false);
        actions.addView(busy, Ui.margins(0, -2, a.dp(14), 0, 0, 0));
        form.addView(actions, Ui.margins(-2, -2, 0, a.dp(10), 0, 0));

        TextView example = Ui.text(a, 11, Ui.FAINT, false);
        example.setText("Exemple : https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json");
        example.setLineSpacing(a.dp(2), 1f);
        form.addView(example, Ui.margins(-1, -2, 0, a.dp(6), 0, a.dp(20)));

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        form.addView(list);

        refresh();
    }

    /** on the list, or the button: the link field takes the highlight only when moved to */
    void focus() {
        java.util.ArrayList<View> l = list.getFocusables(View.FOCUS_DOWN);
        (l.isEmpty() ? addButton : l.get(0)).requestFocus();
    }

    void pause() {
        if (Plugins.get(a).onChange == onChange) Plugins.get(a).onChange = null;
    }

    void resume() {
        Plugins.get(a).onChange = onChange;
        refresh();
    }

    private void add() {
        String link = url.getText().toString().trim();
        if (link.isEmpty()) {
            Toast.makeText(a, "Collez le lien du manifest.json", Toast.LENGTH_SHORT).show();
            return;
        }
        busy.setText("Ajout…");
        a.cat.async(() -> {
            try {
                return Plugins.get(a).add(link);
            } catch (Exception e) {
                android.util.Log.w("PluginsPage", "add " + link, e);
                return "!" + (e.getMessage() == null ? "Dépôt illisible" : e.getMessage());
            }
        }, name -> {
            busy.setText("");
            if (name == null) {
                Toast.makeText(a, "Dépôt illisible", Toast.LENGTH_LONG).show();
            } else if (name.startsWith("!")) {
                Toast.makeText(a, name.substring(1), Toast.LENGTH_LONG).show();
            } else {
                url.setText("");
                Toast.makeText(a, name + " ajouté — téléchargement des scrapers…", Toast.LENGTH_SHORT).show();
            }
            refresh();
        });
    }

    private void refresh() {
        // the highlight stays on the same repository's header when the list is rebuilt
        View f = list.findFocus();
        Object keep = f == null ? null : f.getTag();
        if (f != null) {
            // held by the list meanwhile, or Android would hand it to the tabs above
            list.setFocusable(true);
            list.requestFocus();
        }
        list.removeAllViews();
        Plugins p = Plugins.get(a);
        List<Plugins.Repo> repos = p.repos();
        if (repos.isEmpty()) {
            TextView none = Ui.text(a, 13, Ui.DIM, false);
            none.setText("Aucun dépôt. Ajoutez-en un ci-dessus ; ses scrapers apparaîtront ici, activés par défaut.");
            none.setLineSpacing(a.dp(2), 1f);
            list.addView(none);
            if (list.isFocused()) url.requestFocus();
            list.setFocusable(false);
            return;
        }
        for (Plugins.Repo repo : repos) {
            boolean expanded = open.contains(repo.url);
            List<Plugins.Scraper> ss = p.scrapersOf(repo);
            int ready = 0, on = 0, resting = 0;
            for (Plugins.Scraper s : ss) {
                if (s.ready()) ready++;
                if (s.enabled && p.paused(s)) resting++;
                else if (s.enabled) on++;
            }

            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = Ui.button(a, 0, (expanded ? "▾  " : "▸  ") + repo.name, v -> {
                if (!open.remove(repo.url)) open.add(repo.url);
                refresh();
            });
            n.setTag("repo:" + repo.url);
            head.addView(n);
            TextView sum = Ui.line(a, 12, Ui.DIM, false);
            sum.setText(repo.enabled ? on + " activés sur " + ss.size() + (resting > 0 ? "  ·  " + resting + " en pause" : "") : "Désactivé");
            head.addView(sum, Ui.margins(0, -2, a.dp(14), 0, 0, 0));
            ((LinearLayout.LayoutParams) sum.getLayoutParams()).weight = 1;

            TextView state = Ui.line(a, 11, Ui.FAINT, false);
            state.setText(ready == ss.size() ? "" : ready + "/" + ss.size() + " prêts");
            head.addView(state, Ui.margins(0, -2, a.dp(12), 0, a.dp(12), 0));
            TextView again = Ui.button(a, R.drawable.ic_replay, "Actualiser", v -> {
                state.setText("Mise à jour…");
                p.refresh(repo);
            });
            again.setTag("refresh:" + repo.url);
            head.addView(again, Ui.margins(-2, -2, 0, 0, a.dp(8), 0));
            // switching the repository off and removing it, side by side
            TextView onOff = Ui.toggle(a, repo.enabled, repo.name, v -> {
                p.setEnabled(repo, !repo.enabled);
                refresh();
            });
            onOff.setTag("enable:" + repo.url);
            head.addView(onOff, Ui.margins(-2, -2, 0, 0, a.dp(8), 0));
            TextView remove = Ui.button(a, 0, "Retirer", v -> {
                p.remove(repo);
                refresh();
            });
            remove.setTag("remove:" + repo.url);
            head.addView(remove);
            list.addView(head, Ui.margins(-1, -2, 0, a.dp(14), 0, a.dp(4)));
            if (!expanded || !repo.enabled) continue;
            TextView u = Ui.line(a, 11, Ui.FAINT, false);
            u.setText(repo.url);
            list.addView(u, Ui.margins(-1, -2, a.dp(4), 0, 0, a.dp(4)));

            for (Plugins.Scraper s : ss) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));

                boolean paused = s.enabled && p.paused(s);
                TextView toggle = Ui.toggle(a, s.enabled && !paused, s.name, v -> {
                    // a paused script is switched on again with a fresh start
                    if (paused) p.unpause(s);
                    else p.setEnabled(s, !s.enabled);
                    refresh();
                });
                toggle.setTag("scraper:" + repo.url + "/" + s.name);
                row.addView(toggle, Ui.margins(-2, -2, 0, 0, a.dp(10), 0));

                LinearLayout texts = new LinearLayout(a);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView name = Ui.line(a, 13, Color.WHITE, false);
                name.setText(s.name + (s.version.isEmpty() ? "" : "  " + s.version));
                texts.addView(name);
                String detail = !s.ready() ? (p.failed(s) ? "Téléchargement impossible" : "Téléchargement…")
                        : paused ? "En pause : aucun lien pour les " + Plugins.PAUSE_AFTER + " derniers titres — activez-le pour réessayer"
                        : s.description.replace('\n', ' ');
                TextView d = Ui.line(a, 11, s.ready() && !paused ? Ui.DIM : 0xFFFFB74D, false);
                d.setText(detail);
                texts.addView(d);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView kind = Ui.line(a, 11, Ui.FAINT, false);
                kind.setText(s.types.isEmpty() ? "tous" : String.join(", ", s.types));
                row.addView(kind);
                list.addView(row, Ui.margins(-1, -2, 0, 0, 0, a.dp(2)));
            }
        }
        View back = keep == null ? null : list.findViewWithTag(keep);
        if (back != null) back.requestFocus();
        // a view without a tag, or gone: the list's first, never the tabs above
        else if (list.isFocused()) focus();
        list.setFocusable(false);
    }
}
