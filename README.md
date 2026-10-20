# Pi Home

A lightweight Apple TV-style launcher for a Raspberry Pi 4 running Android TV (KonstaKANG's
LineageOS build), plus a bridge that lets an old LG TV remote control the Pi over HDMI-CEC.

## What's in here

| Path | What it is |
|---|---|
| `src/`, `res/`, `AndroidManifest.xml` | The launcher: plain Java, no AndroidX, about 60 KB |
| `bridge/lg-simplink.sh` | Root service: speaks LG SimpLink to the TV and turns its remote into key presses. Also carries out the launcher's sleep, restart and app-install requests |
| `bridge/lg-simplink.rc` | Starts the bridge at boot |
| `privapp-permissions-pihome.xml` | Whitelist for the launcher's privileged permissions |
| `build.sh` | Builds the APK without Gradle (aapt2, javac, d8, apksigner) |
| `setup.sh` | Installs everything on a fresh Pi |
| `pihome.keystore` | Signing key. **Back it up**: updates must be signed with the same key. It is not in Git |

## Features

- Dock of 4 favourite apps and a grid of the rest; focused app's name and a glow in its colour
- Row under the dock that follows the highlighted app: SmartTube's "Recommandé", Nuvio's
  "Continuer à regarder", or everyone's "continue watching" for apps that publish nothing
- Backdrop of the highlighted card's artwork
- "Now playing" button for media playing in the background, with play/pause
- Pause menu on an app: move, hide, rename, app info
- Greeting, weather (Open-Meteo, location from ipapi.co), next prayer time for the location
  (Aladhan, Muslim World League) with an on-screen reminder
- Sleep now or on a timer, restart, automatic sleep after an hour idle past midnight
- Clock screensaver that drifts to avoid burn-in; dims the home screen 23:00–06:00
- ⚙ menu: Android settings, Infos Pi (temperature, memory, storage, IP, uptime), update check,
  prayer reminder and navigation sounds switches
- Updates for SmartTube and Nuvio from their GitHub releases, installed silently by the bridge

## LG remote

| Button | Does |
|---|---|
| Arrows, OK, Back | Navigate, select, back |
| Play | Play; on the Home screen, opens the highlighted app |
| Pause | Play/pause; on the Home screen, opens the app menu |
| Stop | Home |
| EXIT, colours, numbers | Nothing: the TV keeps them |

## Build and install

```sh
./build.sh            # build build/pihome.apk
./build.sh install    # build and install over the Pi's copy (keeps settings)
./setup.sh            # full install on a fresh Pi: system files, reboot, settings
```

`PI_HOST` overrides the Pi's address (default `192.168.1.116:5555`). The bridge has no build
step: copy `bridge/lg-simplink.sh` to `/system/bin/` (see `setup.sh`) and restart it with
`stop lg-simplink; start lg-simplink` as root.

## Things to know

- **Privileged permissions.** The launcher is a priv-app (`/system/priv-app/PiHome`) to read other
  apps' TV channels (`ACCESS_ALL_EPG_DATA`) and media sessions (`MEDIA_CONTENT_CONTROL`). The
  build enforces the whitelist: adding a privileged permission without listing it in
  `privapp-permissions-pihome.xml` stops the Pi from booting. Update the whitelist and the system
  copy together, then reboot.
- **LG SimpLink.** The TV only forwards its remote to a device that claims LG's vendor ID and
  answers its handshake the way libcec's `CSLCommandHandler` does. The bridge presents the Pi as an
  LG recording device (logical address 1, physical address 3.0.0.0, i.e. HDMI 3), so Android's
  own HDMI-CEC is switched off.
- **uinput quirk.** Android's `uinput` tool runs each command only when the next one arrives, so
  every key event is followed by a no-op, and key-ups wait with a real `sleep` (its `delay`
  command doesn't hold events back).
- **If the remote stops responding,** switch the TV input away from HDMI 3 and back.
- **Requests to the bridge** go through `/data/data/com.sall.pihome/files/request`:
  `sleep`, `sleep-in <seconds>`, `sleep-cancel`, `reboot`, `install <apk paths>`.

## Undo

As root on the Pi:

```sh
mount -o remount,rw /
rm -r /system/priv-app/PiHome /system/etc/permissions/privapp-permissions-pihome.xml
rm /system/bin/lg-simplink.sh /system/etc/init/lg-simplink.rc
mount -o remount,ro /
cmd hdmi_control cec_setting set hdmi_cec_enabled 1
pm uninstall com.sall.pihome
reboot
```

Install another launcher first, or Android TV falls back to its own setup screen.
