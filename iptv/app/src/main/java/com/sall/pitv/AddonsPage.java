package com.sall.pitv;

import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Paramètres' Add-ons tab: the Stremio add-ons, what each one brings, and adding one by its link. */
final class AddonsPage {
    final View view;
    private final MainActivity a;
    private final LinearLayout list;
    private final EditText url;
    private final TextView addButton;

    AddonsPage(MainActivity a) {
        this.a = a;
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(40), a.dp(28), a.dp(40), a.dp(40));
        sv.addView(col);
        view = sv;

        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        col.addView(form, new LinearLayout.LayoutParams(a.dp(760), -2));

        TextView h = Ui.text(a, 24, Color.WHITE, true);
        h.setText("Add-ons");
        form.addView(h);
        TextView intro = Ui.text(a, 13, Ui.DIM, false);
        intro.setText("Des add-ons Stremio ajoutent des films, des séries et des animes (catalogues, fiches), des liens pour les lire et des sous-titres.");
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
        addButton = Ui.button(a, 0, "Ajouter l'add-on", v -> add());
        form.addView(addButton, Ui.margins(-2, -2, 0, a.dp(10), 0, 0));
        // down from a long link would follow the cursor, at the right, to a Retirer button
        addButton.setId(View.generateViewId());
        url.setNextFocusDownId(addButton.getId());
        url.setOnEditorActionListener((v, action, e) -> {
            add();
            return true;
        });
        TextView tip = Ui.text(a, 11, Ui.FAINT, false);
        tip.setText("Aussi depuis le téléphone, dans l'onglet IPTV : un lien par ligne.");
        form.addView(tip, Ui.margins(-1, -2, 0, a.dp(6), 0, a.dp(20)));

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

    private void add() {
        String u = Addons.normalize(url.getText().toString());
        if (u == null) {
            Toast.makeText(a, "Collez le lien du manifest.json", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> l = Addons.get(a).urls();
        l.add(u);
        set(l);
        url.setText("");
    }

    private void set(List<String> urls) {
        Addons.get(a).setUrls(urls);
        // the list is shown again once their manifests are read (Catalog's listeners)
        a.cat.loadAddons(true);
        refresh();
        Toast.makeText(a, "Add-ons mis à jour — chargement…", Toast.LENGTH_SHORT).show();
    }

    /** the add-ons, in order; the highlight stays where it was */
    void refresh() {
        View f = list.findFocus();
        Object keep = f == null ? null : f.getTag();
        if (f != null) {
            // held by the list meanwhile, or Android would hand it to the tabs above
            list.setFocusable(true);
            list.requestFocus();
        }
        list.removeAllViews();
        Addons ad = Addons.get(a);
        List<String> urls = ad.urls();
        if (urls.isEmpty()) {
            TextView none = Ui.text(a, 13, Ui.DIM, false);
            none.setText("Aucun add-on. Ajoutez-en un ci-dessus.");
            list.addView(none);
        }
        for (String u : urls) {
            Addons.Addon x = null;
            for (Addons.Addon y : ad.list) if (y.url.equals(u)) x = y;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(14), a.dp(10), a.dp(10), a.dp(10));
            row.setBackground(Ui.rounded(0xFF1A1A1A, a.dp(10)));

            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.line(a, 15, Color.WHITE, true);
            // the host only: a link may carry a personal configuration
            String host = u.replaceFirst("^https?://", "").replaceFirst("/.*", "");
            name.setText(x != null && !x.name.isEmpty() ? x.name : host);
            texts.addView(name);
            String desc = x == null || x.manifest == null ? "Chargement…" : x.manifest.optString("description", "").replace('\n', ' ').trim();
            TextView d = Ui.text(a, 12, Ui.DIM, false);
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            d.setText(desc);
            d.setVisibility(desc.isEmpty() ? View.GONE : View.VISIBLE);
            texts.addView(d, Ui.margins(-1, -2, 0, a.dp(3), 0, 0));
            TextView brings = Ui.line(a, 11, Ui.FAINT, false);
            brings.setText(host + (x == null ? "" : "  ·  " + brings(x)));
            texts.addView(brings, Ui.margins(-1, -2, 0, a.dp(4), 0, 0));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean on = ad.enabled(u);
            // switched off: greyed out, and its rows leave the pages
            texts.setAlpha(on ? 1f : 0.45f);

            TextView onOff = Ui.toggle(a, on, name.getText().toString(), v -> {
                ad.setEnabled(u, !on);
                a.cat.loadAddons(false);
                refresh();
            });
            onOff.setTag("enable:" + u);
            row.addView(onOff, Ui.margins(-2, -2, a.dp(12), 0, 0, 0));

            TextView remove = Ui.button(a, 0, "Retirer", v -> {
                List<String> l = ad.urls();
                l.remove(u);
                set(l);
            });
            remove.setTag("remove:" + u);
            row.addView(remove, Ui.margins(-2, -2, a.dp(8), 0, 0, 0));
            list.addView(row, Ui.margins(-1, -2, 0, 0, 0, a.dp(8)));
        }
        View back = keep == null ? null : list.findViewWithTag(keep);
        if (back != null) back.requestFocus();
        else if (list.isFocused()) url.requestFocus();
        list.setFocusable(false);
    }

    /** what an add-on brings, from its manifest's resources */
    private static String brings(Addons.Addon x) {
        List<String> out = new ArrayList<>();
        JSONArray res = x.manifest == null ? null : x.manifest.optJSONArray("resources");
        for (int i = 0; res != null && i < res.length(); i++) {
            Object r = res.opt(i);
            String n = r instanceof JSONObject ? ((JSONObject) r).optString("name") : String.valueOf(r);
            String fr = n.equals("catalog") ? "catalogues" : n.equals("meta") ? "fiches" : n.equals("stream") ? "liens"
                    : n.equals("subtitles") ? "sous-titres" : null;
            if (fr != null && !out.contains(fr)) out.add(fr);
        }
        return out.isEmpty() ? "—" : String.join(", ", out);
    }
}
