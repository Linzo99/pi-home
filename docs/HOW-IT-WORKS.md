# How Pi Home works

The parts behind the [guide](../README.md): the LG remote bridge, the launcher's system
permissions, the changes made to Android, and what to do when something goes wrong.

## Overview

```
 LG remote ──IR──▶ LG TV ──HDMI-CEC──▶ lg-simplink.sh (root) ──uinput──▶ Android ──▶ Pi Home
                                             ▲                                        │
                                             └──── files/request (sleep, install…) ◀──┘
```

| Part | Runs as | Lives in |
|---|---|---|
| Pi Home (launcher, screensaver) | system app | `/system/priv-app/PiHome/`, with updates in `/data/app/` |
| Permission whitelist | — | `/system/etc/permissions/privapp-permissions-pihome.xml` |
| LG remote bridge | root, started by init | `/system/bin/lg-simplink.sh`, `/system/etc/init/lg-simplink.rc` |

## The LG remote bridge

### Why it's needed

The TV only forwards its remote over HDMI-CEC ("SimpLink") to devices it accepts as LG. Android's
own CEC presents the Pi as a generic player, so the TV kept asking who it was and never sent a
key. The bridge replaces it: Android's CEC is switched off (`hdmi_cec_enabled 0`), and the
bridge drives `/dev/cec0` with `cec-ctl`.

### The handshake

The bridge copies what libcec's `CSLCommandHandler` does for LG TVs. It claims logical address 1
(recording device), LG's vendor ID `0x00e091`, and physical address 3.0.0.0 (HDMI 3). It then
answers the TV's vendor commands:

| TV sends | Bridge answers |
|---|---|
| `01` (SimpLink init) | `02 05` (acknowledge, as an HDD recorder) |
| `04 xx` (connect) | `05 01` (device mode), deck status `0x20` (LG's "active" code), power on |
| `03 xx` (power on) | power "to on", physical address, then makes itself the active source 3 s later |
| `0b` / `a0` (status) | power "to on" |
| Give deck status | `0x20`, and becomes the active source if asked to report changes |

Every 2 minutes it re-sends its physical address and vendor ID so the TV doesn't drop it. On
start it keeps an existing registration rather than claiming it again: re-claiming made the TV
forget the Pi until its input was switched away and back.

### From CEC to key presses

Remote buttons arrive as CEC "User Control Pressed" codes. The bridge turns them into Linux key
codes on a virtual input device, **LG SimpLink Remote**, created with Android's `uinput` tool
through the FIFO `/data/local/tmp/slb.fifo`.

| CEC code | Key | | CEC code | Key |
|---|---|---|---|---|
| `00` select | DPAD_CENTER | | `44` play | MEDIA_PLAY |
| `01`–`04` arrows | DPAD | | `45` stop | HOME |
| `0d` back | BACK | | `46` pause | MEDIA_PLAY_PAUSE |
| `09`–`0b` menus | MENU | | `4b`/`4c` | next / previous |

Three quirks shaped this code:

- `uinput` runs a command only when the next one arrives. Each event is followed by a 1 ms
  `delay` no-op so it happens straight away.
- Its `delay` command doesn't hold back later events, so key-ups wait with a real `sleep 0.12`.
  Presses shorter than about 80 ms get ignored by Android.
- The TV's own "released" message comes about 70 ms after the press. The bridge ignores it and
  makes every press a full 120 ms press; holding still repeats, because the TV resends the press
  about every 250 ms.

When the screen is off (`/sys/class/drm/card0-HDMI-A-1/dpms` reads `Off`), a press only wakes
the Pi.

The TV keeps EXIT, the colour buttons and the numbers to itself.

### Requests from the launcher

Pi Home can't sleep, restart or install apps without root, so it writes a request to
`/data/data/com.sall.pihome/files/request`. The bridge checks it every second:

| Request | Does |
|---|---|
| `sleep` | `input keyevent KEYCODE_SLEEP` |
| `sleep-in <seconds>` / `sleep-cancel` | starts or cancels the sleep timer |
| `reboot` | `svc power reboot` |
| `install <apk> …` | `pm install -r` each file, logged to `files/install.log` |

The bridge logs what the TV sends to `/data/local/tmp/slb.log`.

## The launcher's permissions

Pi Home is a privileged system app so it can read three kinds of data that belong to other apps:

| Permission | For |
|---|---|
| `ACCESS_ALL_EPG_DATA` | other apps' TV channels and "continue watching" |
| `MEDIA_CONTENT_CONTROL` | other apps' media sessions (now playing) |
| `PACKAGE_USAGE_STATS` | every app's data usage, for Infos Pi |

The build enforces the whitelist (`ro.control_privapp_permissions=enforce`). **Adding a
privileged permission to the manifest without listing it in `privapp-permissions-pihome.xml`
stops the Pi from booting.** Update the whitelist and the system copy together, then reboot.

Other permissions are granted by `setup.sh`: location (to read the Wi-Fi name), overlay (the
prayer banner) and settings (the navigation sounds switch).

`./build.sh install` puts updates in `/data/app/` over the system copy, which is how Android
updates system apps. The system copy only needs refreshing when the permissions change.

## Data sources

| What | From | How often |
|---|---|---|
| App rows | Android TV provider: `watch_next_program`, `preview_program`, `channel` | when they change |
| Weather | [Open-Meteo](https://open-meteo.com) | every 30 min |
| Location | [ipapi.co](https://ipapi.co), from the public IP | once a day |
| Prayer times | [Aladhan](https://aladhan.com), method 3 (Muslim World League) | once a day |
| App updates | GitHub `releases/latest` of `yuliskov/SmartTube` and `NuvioMedia/NuvioTV` | every 12 h |

Posters are cached in the app's cache folder. All of this together uses well under 1 MB a day.

Apple TV registers a channel but publishes nothing: its background workers fail with
`IOException: closed` on this Pi, so its row falls back to everyone's "Continuer à regarder".

## Changes made to Android

`setup.sh` applies these. Each one fixed a specific problem.

| Change | Why | Undo |
|---|---|---|
| `cmd hdmi_control cec_setting set hdmi_cec_enabled 0` | the bridge handles CEC | `… hdmi_cec_enabled 1` |
| `cmd deviceidle whitelist +org.smarttube.stable +com.sall.pihome` | Android 16 cuts background apps' network; SmartTube's background audio failed with `UnknownHostException` | `cmd deviceidle whitelist -<package>` |
| `settings put global cached_apps_freezer disabled`, then reboot | the freezer stopped SmartTube's background audio after about a minute. Frozen apps keep their memory anyway, so this only costs a little idle CPU | `… cached_apps_freezer device_default` |
| Play Store `RUN_ANY_IN_BACKGROUND ignore`, standby bucket `restricted` | ~44% CPU in the background for automatic updates | `cmd appops set com.android.vending RUN_ANY_IN_BACKGROUND allow; am set-standby-bucket com.android.vending active` |
| AirScreen disabled | ~9% CPU waiting for casts | `pm enable com.ionitech.airscreen` |
| Pi Home's uid policy set to 0 (`service call netpolicy 1 i32 <uid> i32 0`) | LineageOS installed it with no network access (`REJECT_ALL`) | — |
| Screensaver set to `com.sall.pihome/.ClockDream` | the clock screensaver | pick another in Android settings |

## Troubleshooting

| Problem | Try |
|---|---|
| The remote does nothing | Switch the TV input away from HDMI 3 and back. As root: `getprop init.svc.lg-simplink` should say `running`; `stop lg-simplink; start lg-simplink` restarts it |
| The first press after a pause is ignored | Was caused by a stale CEC registration; a clean reboot fixed it |
| SmartTube's background audio stops | Check the two background exemptions above. Updating Pi Home or SmartTube also stops it |
| No posters, weather or prayer times | Pi Home has no network: check `dumpsys netpolicy` for its uid, and the Wi-Fi |
| The row shows nothing for an app | The app doesn't publish to the Android TV provider |
| The Pi doesn't boot after a change | A privileged permission is missing from the whitelist |
| Hot (above 80 °C) | Add a fan or heatsinks; lower video quality; ⚙ → Infos Pi shows the temperature |

Useful commands, as root:

```sh
cec-ctl -d0                                   # the Pi's CEC registration
tail /data/local/tmp/slb.log                  # what the TV sent
cat /data/data/com.sall.pihome/files/install.log
dumpsys media_session | grep -A4 package=     # who's playing
cat /sys/class/thermal/thermal_zone0/temp     # temperature, in thousandths of °C
```

## Undo everything

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
