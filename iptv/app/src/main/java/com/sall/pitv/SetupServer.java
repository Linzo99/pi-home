package com.sall.pitv;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A page on the local network for typing the provider's details on a phone instead of with the
 * remote. Runs only while Settings is on screen.
 */
final class SetupServer {
    static final int PORT = 8765;

    interface Saved {
        void onSaved(Source s);
    }

    private final Saved saved;
    private final Source current;
    private final android.content.Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ServerSocket server;

    SetupServer(android.content.Context c, Source current, Saved saved) {
        this.app = c.getApplicationContext();
        this.current = current;
        this.saved = saved;
    }

    void start() {
        if (server != null) return;
        Thread t = new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                while (!s.isClosed()) {
                    try (Socket c = s.accept()) {
                        c.setSoTimeout(10_000);
                        handle(c);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }, "setup-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    private void handle(Socket c) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        int length = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        if (request.startsWith("POST /collections")) {
            reply(c, "text/plain", importCollections(utf8Body(in, length)));
            return;
        }
        String body = "";
        if (length > 0 && length < 64 * 1024) {
            char[] buf = new char[length];
            int n = 0;
            while (n < length) {
                int r = in.read(buf, n, length - n);
                if (r < 0) break;
                n += r;
            }
            body = new String(buf, 0, n);
        }
        String html;
        if (request.startsWith("POST")) {
            Map<String, String> f = form(body);
            Source s = new Source();
            s.type = Source.M3U.equals(f.get("type")) ? Source.M3U : Source.XTREAM;
            s.server = f.getOrDefault("server", "");
            s.user = f.getOrDefault("user", "");
            s.pass = f.getOrDefault("pass", "");
            if (s.pass.isEmpty() && Source.XTREAM.equals(s.type)) s.pass = current.pass;
            s.m3u = f.getOrDefault("m3u", "");
            s.epg = f.getOrDefault("epg", "");
            s.agent = f.getOrDefault("agent", Source.DEFAULT_AGENT);
            if (s.agent.trim().isEmpty()) s.agent = Source.DEFAULT_AGENT;
            s.proxy = f.getOrDefault("proxy", "");
            if (f.containsKey("addons")) {
                // one link per line
                Addons.get(app).setUrls(java.util.Arrays.asList(f.get("addons").split("\\s+")));
                Catalog.get(app).loadAddons(true);
            }
            ui.post(() -> saved.onSaved(s));
            html = page("<p class=ok>✓ Enregistré. La liste se charge sur la télé.</p>", s, addons());
        } else {
            html = page("", current, addons());
        }
        reply(c, "text/html", html);
    }

    private static void reply(Socket c, String type, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + data.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    /** a body of [bytes] UTF-8 bytes, read as text: emoji and accents take more than one byte */
    private static String utf8Body(BufferedReader in, int bytes) throws Exception {
        if (bytes <= 0 || bytes > 4 * 1024 * 1024) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        while (n < bytes) {
            int ch = in.read();
            if (ch < 0) break;
            b.append((char) ch);
            n += ch < 0x80 ? 1 : ch < 0x800 ? 2 : Character.isSurrogate((char) ch) ? 2 : 3;
        }
        return b.toString();
    }

    /** a Nuvio collections export from the phone: kept if it holds shelves the app can show */
    private String importCollections(String json) {
        try {
            int shelves = Folders.parse(json).size();
            if (shelves == 0) return "Ce fichier ne contient aucune collection lisible.";
            java.nio.file.Files.write(Folders.file(app).toPath(), json.getBytes(StandardCharsets.UTF_8));
            return "✓ " + shelves + " collections importées. Elles sont dans Collections et sur l'Accueil.";
        } catch (Exception e) {
            return "Fichier illisible : choisissez l'export JSON des collections de Nuvio.";
        }
    }

    private static Map<String, String> form(String body) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8").trim());
        }
        return m;
    }

    private static String esc(String s) {
        return s == null ? "" : Html.escapeHtml(s);
    }

    private String addons() {
        return String.join("\n", Addons.get(app).urls());
    }

    private static String page(String message, Source s, String addons) {
        boolean x = !Source.M3U.equals(s.type);
        return "<!doctype html><html lang=fr><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Pi TV</title><style>"
                + "body{font-family:-apple-system,system-ui,sans-serif;background:#141414;color:#fff;margin:0;padding:24px;max-width:520px}"
                + "h1{color:#E50914;margin:0 0 4px}p{color:#aaa}label{display:block;margin:16px 0 6px;color:#ccc;font-size:14px}"
                + "input,select,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;border:1px solid #333;background:#222;color:#fff;font-size:16px}"
                + "button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:8px;background:#E50914;color:#fff;font-size:17px;font-weight:600}"
                + ".ok{color:#46D369;font-weight:600}.m3u,.xt{display:none}.show{display:block}small{color:#777}"
                + "</style></head><body><h1>Pi TV</h1><p>Les informations de votre fournisseur IPTV.</p>" + message
                + "<form method=post action=/save>"
                + "<label>Type</label><select name=type id=type onchange=sw()>"
                + "<option value=xtream" + (x ? " selected" : "") + ">Xtream Codes (serveur + identifiants)</option>"
                + "<option value=m3u" + (x ? "" : " selected") + ">Playlist M3U (lien)</option></select>"
                + "<div class='xt" + (x ? " show" : "") + "'>"
                + "<label>Serveur</label><input name=server placeholder='http://exemple.com:8080' value='" + esc(s.server) + "' autocapitalize=off autocorrect=off>"
                + "<label>Nom d'utilisateur</label><input name=user value='" + esc(s.user) + "' autocapitalize=off autocorrect=off>"
                + "<label>Mot de passe</label><input name=pass type=password placeholder='" + (s.pass.isEmpty() ? "" : "inchangé") + "' autocapitalize=off>"
                + "</div><div class='m3u" + (x ? "" : " show") + "'>"
                + "<label>Lien de la playlist M3U</label><input name=m3u placeholder='http://…/get.php?…&type=m3u_plus' value='" + esc(s.m3u) + "' autocapitalize=off autocorrect=off>"
                + "</div>"
                + "<label>Guide des programmes XMLTV <small>(facultatif)</small></label><input name=epg placeholder='http://…/xmltv.php?…' value='" + esc(s.epg) + "' autocapitalize=off autocorrect=off>"
                + "<label>User-Agent <small>(à changer seulement si le fournisseur l'exige)</small></label><input name=agent value='" + esc(s.agent) + "' autocapitalize=off autocorrect=off>"
                + "<label>Proxy pour les chaînes géo-bloquées <small>(facultatif, hôte:port)</small></label><input name=proxy placeholder='100.113.181.10:8888' value='" + esc(s.proxy) + "' autocapitalize=off autocorrect=off>"
                + "<label>Add-ons Stremio <small>(un lien manifest.json par ligne)</small></label><textarea name=addons rows=5 autocapitalize=off autocorrect=off>" + esc(addons) + "</textarea>"
                + "<button>Enregistrer</button></form>"
                + "<h2 style='margin-top:40px'>Collections</h2><p>L'export des collections de Nuvio (fichier .json) : il remplace celles de la télé.</p>"
                + "<input type=file id=coll accept='.json,application/json'><button onclick=imp()>Importer les collections</button><p id=collmsg></p>"
                + "<script>function imp(){var f=document.getElementById('coll').files[0],m=document.getElementById('collmsg');"
                + "if(!f){m.textContent='Choisissez d\\'abord le fichier.';return;}m.textContent='Envoi…';"
                + "f.text().then(t=>fetch('/collections',{method:'POST',body:t})).then(r=>r.text()).then(t=>m.textContent=t).catch(e=>m.textContent='Échec : '+e);}"
                + "function sw(){var m=document.getElementById('type').value=='m3u';"
                + "document.querySelector('.m3u').className='m3u'+(m?' show':'');document.querySelector('.xt').className='xt'+(m?'':' show');}</script>"
                + "</body></html>";
    }
}
