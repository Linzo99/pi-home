package com.sall.piairplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.IBinder;
import android.util.Log;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.discovery.AdvertisedReceiver;
import com.github.serezhka.airplay.server.internal.ControlServer;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the AirPlay receiver all the time: the control server, and its announcement on the
 * network so Macs and iPhones list it. Idle, it only waits on a socket.
 */
public class AirPlayService extends Service {
    static final String NAME = "Pi";
    private static final String TAG = "PiAirPlay";

    private NsdManager nsd;
    private final NsdManager.RegistrationListener airplayReg = new Registration("_airplay._tcp");
    private final NsdManager.RegistrationListener raopReg = new Registration("_raop._tcp");
    private Thread server;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, AirPlayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("airplay", "AirPlay", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "airplay")
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle("AirPlay prêt · " + NAME)
                .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        nsd = getSystemService(NsdManager.class);
        server = new Thread(this::serve, "airplay-control");
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void serve() {
        try {
            AirPlayConfig cfg = new AirPlayConfig();
            cfg.setServerName(NAME);
            // what the Mac and iPhone send: 1080p at 30 fps keeps the stream light on Wi-Fi
            cfg.setWidth(1920);
            cfg.setHeight(1080);
            cfg.setFps(30);
            // no "play this video on the TV" (HLS): only screen mirroring and sound
            cfg.setHlsEnabled(false);
            cfg.setMdnsLoopback(false);
            cfg.setTrayEnabled(false);
            PairingIdentity identity = identity();
            ControlServer control = new ControlServer(cfg, Receiver.get(this), identity);
            // ControlServer.start() can miss its own notify(); run it directly and wait for the port
            Thread t = new Thread(control, "airplay-netty");
            t.start();
            while (control.getPort() == 0 && t.isAlive()) Thread.sleep(50);
            Log.i(TAG, "AirPlay server on port " + control.getPort());
            advertise(control.getPort(), identity.publicKeyHex());
            t.join();
        } catch (InterruptedException ignored) {
            // service destroyed
        } catch (Exception e) {
            Log.e(TAG, "AirPlay server", e);
        }
    }

    /** the same keys and device id across restarts, so senders recognise the Pi */
    private PairingIdentity identity() {
        SharedPreferences p = getSharedPreferences("airplay", MODE_PRIVATE);
        String seed = p.getString("seed", null);
        if (seed != null) return PairingIdentity.fromSeed(HexFormat.of().parseHex(seed));
        PairingIdentity id = PairingIdentity.generate();
        p.edit().putString("seed", HexFormat.of().formatHex(id.seed())).apply();
        return id;
    }

    /** a made-up, stable hardware address: Android hides the real one from apps */
    private String deviceId() {
        SharedPreferences p = getSharedPreferences("airplay", MODE_PRIVATE);
        String id = p.getString("deviceId", null);
        if (id == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xfc) | 0x02); // locally administered, unicast
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(sb.length() > 0 ? ":" : "").append(String.format("%02X", x));
            id = sb.toString();
            p.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    /** the same TXT records as java-airplay's MdnsAdvertiser, through Android's own mDNS */
    private void advertise(int port, String pk) {
        String mac = deviceId();
        String features = AdvertisedReceiver.featuresTxt(false);

        Map<String, String> airplay = new LinkedHashMap<>();
        airplay.put("deviceid", mac);
        airplay.put("features", features);
        airplay.put("srcvers", AdvertisedReceiver.SOURCE_VERSION);
        airplay.put("flags", AdvertisedReceiver.MDNS_FLAGS);
        airplay.put("vv", Integer.toString(AdvertisedReceiver.VV));
        airplay.put("model", AdvertisedReceiver.MODEL);
        airplay.put("rhd", "5.6.0.0");
        airplay.put("pw", "false");
        airplay.put("pk", pk);
        register(NAME, "_airplay._tcp", port, airplay, airplayReg);

        Map<String, String> raop = new LinkedHashMap<>();
        raop.put("ch", "2");
        raop.put("cn", "1,3");
        raop.put("da", "true");
        raop.put("et", "0,3,5");
        raop.put("ek", "1");
        raop.put("ft", features);
        raop.put("am", AdvertisedReceiver.MODEL);
        raop.put("md", "0,1,2");
        raop.put("sr", "44100");
        raop.put("ss", "16");
        raop.put("sv", "false");
        raop.put("sm", "false");
        raop.put("tp", "UDP");
        raop.put("txtvers", "1");
        raop.put("sf", AdvertisedReceiver.MDNS_FLAGS);
        raop.put("vs", AdvertisedReceiver.SOURCE_VERSION);
        raop.put("vn", "65537");
        raop.put("pk", pk);
        register(mac.replace(":", "") + "@" + NAME, "_raop._tcp", port, raop, raopReg);
    }

    private void register(String name, String type, int port, Map<String, String> txt, NsdManager.RegistrationListener l) {
        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceName(name);
        s.setServiceType(type);
        s.setPort(port);
        for (Map.Entry<String, String> e : txt.entrySet()) s.setAttribute(e.getKey(), e.getValue());
        nsd.registerService(s, NsdManager.PROTOCOL_DNS_SD, l);
    }

    @Override
    public void onDestroy() {
        try {
            nsd.unregisterService(airplayReg);
            nsd.unregisterService(raopReg);
        } catch (Exception ignored) {
        }
        if (server != null) server.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class Registration implements NsdManager.RegistrationListener {
        private final String type;

        Registration(String type) {
            this.type = type;
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo s) {
            Log.i(TAG, "announced " + s.getServiceName() + " " + type);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo s, int error) {
            Log.e(TAG, "announcing " + type + " failed: " + error);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo s) {
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo s, int error) {
        }
    }
}
