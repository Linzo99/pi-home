package com.sall.pitv;

import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;

/**
 * The provider's details, typed here with the remote or on a phone through SetupServer, and
 * the catalogue's state.
 */
final class SettingsPage {
    final View view;
    private final MainActivity a;
    private final TextView typeX, typeM, phone, state;
    private final EditText server, user, pass, m3u, epg, agent;
    private final LinearLayout xtFields, m3uFields;
    private String type;
    private SetupServer setup;

    SettingsPage(MainActivity a) {
        this.a = a;
        Source s = a.cat.source;
        type = s.type;

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout cols = new LinearLayout(a);
        cols.setPadding(a.dp(40), a.dp(28), a.dp(40), a.dp(40));
        sv.addView(cols);
        view = sv;

        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        cols.addView(form, new LinearLayout.LayoutParams(a.dp(440), -2));
        TextView h = Ui.text(a, 24, Color.WHITE, true);
        h.setText(s.isSet() ? "Paramètres" : "Bienvenue sur Pi TV");
        form.addView(h);
        TextView intro = Ui.text(a, 13, Ui.DIM, false);
        intro.setText(s.isSet() ? "Votre fournisseur IPTV." : "Ajoutez votre fournisseur IPTV : ici avec la télécommande, ou plus simplement depuis votre téléphone (à droite).");
        intro.setLineSpacing(a.dp(2), 1f);
        form.addView(intro, Ui.margins(-1, -2, 0, a.dp(6), 0, a.dp(14)));

        LinearLayout types = new LinearLayout(a);
        typeX = Ui.button(a, 0, "Xtream Codes", v -> setType(Source.XTREAM));
        typeX.setTag("first");
        typeM = Ui.button(a, 0, "Playlist M3U", v -> setType(Source.M3U));
        types.addView(typeX);
        types.addView(typeM, Ui.margins(-2, -2, a.dp(10), 0, 0, 0));
        form.addView(types);

        xtFields = new LinearLayout(a);
        xtFields.setOrientation(LinearLayout.VERTICAL);
        server = field(xtFields, "Serveur", "http://exemple.com:8080", s.server, false);
        user = field(xtFields, "Nom d'utilisateur", "", s.user, false);
        pass = field(xtFields, "Mot de passe", "", s.pass, true);
        form.addView(xtFields);
        m3uFields = new LinearLayout(a);
        m3uFields.setOrientation(LinearLayout.VERTICAL);
        m3u = field(m3uFields, "Lien de la playlist", "http://…/get.php?…&type=m3u_plus", s.m3u, false);
        form.addView(m3uFields);
        epg = field(form, "Guide XMLTV (facultatif)", "http://…/xmltv.php?…", s.epg, false);
        agent = field(form, "User-Agent", Source.DEFAULT_AGENT, s.agent, false);

        LinearLayout actions = new LinearLayout(a);
        actions.addView(Ui.button(a, 0, "Enregistrer et charger", v -> save(read())));
        actions.addView(Ui.button(a, R.drawable.ic_replay, "Actualiser", v -> {
            a.cat.load(true);
            Toast.makeText(a, "Mise à jour de la liste…", Toast.LENGTH_SHORT).show();
        }), Ui.margins(-2, -2, a.dp(10), 0, 0, 0));
        form.addView(actions, Ui.margins(-2, -2, 0, a.dp(22), 0, 0));

        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(18));
        side.setBackground(Ui.rounded(0xFF1F1F1F, a.dp(12)));
        TextView ph = Ui.text(a, 15, Color.WHITE, true);
        ph.setText("Depuis votre téléphone");
        side.addView(ph);
        phone = Ui.text(a, 13, Ui.DIM, false);
        phone.setLineSpacing(a.dp(3), 1f);
        side.addView(phone, Ui.margins(-1, -2, 0, a.dp(8), 0, 0));
        state = Ui.text(a, 12, Ui.DIM, false);
        state.setLineSpacing(a.dp(3), 1f);
        side.addView(state, Ui.margins(-1, -2, 0, a.dp(18), 0, 0));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.leftMargin = a.dp(28);
        sp.topMargin = a.dp(48);
        cols.addView(side, sp);

        setType(type);
        refresh();
    }

    private EditText field(LinearLayout parent, String label, String hint, String value, boolean secret) {
        TextView l = Ui.text(a, 12, Ui.FAINT, false);
        l.setText(label);
        parent.addView(l, Ui.margins(-1, -2, 0, a.dp(12), 0, a.dp(4)));
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(0x55FFFFFF);
        e.setTextColor(Color.WHITE);
        e.setTextSize(14);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        e.setBackground(Ui.rounded(0xFF262626, a.dp(6)));
        e.setOnFocusChangeListener((v, has) -> e.setBackground(has ? Ui.outline(Color.WHITE, a.dp(2), a.dp(6)) : Ui.rounded(0xFF262626, a.dp(6))));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private void setType(String t) {
        type = t;
        boolean x = Source.XTREAM.equals(t);
        xtFields.setVisibility(x ? View.VISIBLE : View.GONE);
        m3uFields.setVisibility(x ? View.GONE : View.VISIBLE);
        typeX.setText(x ? "✓ Xtream Codes" : "Xtream Codes");
        typeM.setText(x ? "Playlist M3U" : "✓ Playlist M3U");
    }

    private Source read() {
        Source s = new Source();
        s.type = type;
        s.server = server.getText().toString();
        s.user = user.getText().toString();
        s.pass = pass.getText().toString();
        s.m3u = m3u.getText().toString();
        s.epg = epg.getText().toString();
        s.agent = agent.getText().toString();
        if (s.agent.trim().isEmpty()) s.agent = Source.DEFAULT_AGENT;
        return s;
    }

    private void save(Source s) {
        if (!s.isSet()) {
            Toast.makeText(a, Source.XTREAM.equals(s.type) ? "Il manque le serveur ou l'utilisateur" : "Il manque le lien de la playlist", Toast.LENGTH_LONG).show();
            return;
        }
        s.save(a);
        a.cat.source = s;
        Net.agent = s.agent;
        a.lib = Library.get(a);
        a.homeWhenLoaded = true;
        a.cat.load(true);
        Toast.makeText(a, "Enregistré — chargement de la liste…", Toast.LENGTH_LONG).show();
    }

    /** the same fields, filled from the phone */
    private void savedFromPhone(Source s) {
        type = s.type;
        server.setText(s.server);
        user.setText(s.user);
        pass.setText(s.pass);
        m3u.setText(s.m3u);
        epg.setText(s.epg);
        agent.setText(s.agent);
        setType(type);
        save(s);
    }

    void refresh() {
        String ip = ip();
        phone.setText(ip == null ? "Pas de réseau." : "Sur le même Wi-Fi, ouvrez dans le navigateur :\n" + "http://" + ip + ":" + SetupServer.PORT
                + "\npuis remplissez le formulaire.");
        Catalog c = a.cat;
        StringBuilder b = new StringBuilder();
        if (!c.source.isSet()) b.append("Aucun fournisseur.");
        else if (c.error != null) b.append(c.error);
        else if (!c.loaded) b.append("Chargement…");
        else {
            b.append(c.allLive.size()).append(" chaînes · ").append(c.allMovies.size()).append(" films · ")
                    .append(c.allSeries.size()).append(" séries");
            b.append(c.epg.hasGuide ? "\nGuide des programmes chargé" : Source.XTREAM.equals(c.source.type) ? "\nGuide : celui du fournisseur, par chaîne" : "\nPas de guide des programmes");
        }
        if (c.status != null && !c.status.isEmpty()) b.append("\n").append(c.status);
        state.setText(b);
    }

    void focus() {
        typeX.requestFocus();
    }

    void resume() {
        if (setup == null) setup = new SetupServer(a.cat.source, this::savedFromPhone);
        setup.start();
    }

    void pause() {
        if (setup != null) setup.stop();
        setup = null;
    }

    private String ip() {
        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return null;
        for (LinkAddress l : lp.getLinkAddresses()) if (l.getAddress() instanceof Inet4Address) return l.getAddress().getHostAddress();
        return null;
    }
}
