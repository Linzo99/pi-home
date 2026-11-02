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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ServerSocket server;

    SetupServer(Source current, Saved saved) {
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
            ui.post(() -> saved.onSaved(s));
            html = page("<p class=ok>✓ Enregistré. La liste se charge sur la télé.</p>", s);
        } else {
            html = page("", current);
        }
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + data.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
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

    private static String page(String message, Source s) {
        boolean x = !Source.M3U.equals(s.type);
        return "<!doctype html><html lang=fr><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Pi TV</title><style>"
                + "body{font-family:-apple-system,system-ui,sans-serif;background:#141414;color:#fff;margin:0;padding:24px;max-width:520px}"
                + "h1{color:#E50914;margin:0 0 4px}p{color:#aaa}label{display:block;margin:16px 0 6px;color:#ccc;font-size:14px}"
                + "input,select{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;border:1px solid #333;background:#222;color:#fff;font-size:16px}"
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
                + "<button>Enregistrer</button></form>"
                + "<script>function sw(){var m=document.getElementById('type').value=='m3u';"
                + "document.querySelector('.m3u').className='m3u'+(m?' show':'');document.querySelector('.xt').className='xt'+(m?'':' show');}</script>"
                + "</body></html>";
    }
}
