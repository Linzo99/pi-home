#!/bin/sh
# Installs Pi Home and the LG remote bridge on a Raspberry Pi 4 running KonstaKANG's
# LineageOS Android TV build (userdebug, so `adb root` and a writable /system work).
# Safe to run again: every step overwrites or re-applies the same thing.
#
# Usage: PI_HOST=192.168.1.116:5555 ./setup.sh
set -e
cd "$(dirname "$0")"
PI="${PI_HOST:-192.168.1.116:5555}"
PKG=com.sall.pihome
a() { adb -s "$PI" "$@"; }

wait_for_boot() {
    sleep 20
    n=0
    until adb connect "$PI" 2>/dev/null | grep -q "connected to" &&
          [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        n=$((n + 1)); [ $n -gt 60 ] && { echo "the Pi did not come back"; exit 1; }
        sleep 4
    done
}

as_root() {
    a root >/dev/null 2>&1 || true
    sleep 4
    adb connect "$PI" >/dev/null
}

echo "== building Pi Home"
./build.sh

echo "== connecting to $PI"
adb connect "$PI"
as_root

echo "== installing system files"
# Every privileged permission in the manifest must be in the whitelist, or the Pi won't boot
# (ro.control_privapp_permissions=enforce).
a push build/pihome.apk /data/local/tmp/PiHome.apk >/dev/null
a push privapp-permissions-pihome.xml /data/local/tmp/ >/dev/null
a push bridge/lg-simplink.sh /data/local/tmp/ >/dev/null
a push bridge/lg-simplink.rc /data/local/tmp/ >/dev/null
a shell 'set -e
mount -o remount,rw /
mkdir -p /system/priv-app/PiHome
cp /data/local/tmp/PiHome.apk /system/priv-app/PiHome/PiHome.apk
cp /data/local/tmp/privapp-permissions-pihome.xml /system/etc/permissions/
cp /data/local/tmp/lg-simplink.sh /system/bin/lg-simplink.sh
cp /data/local/tmp/lg-simplink.rc /system/etc/init/lg-simplink.rc
chmod 755 /system/priv-app/PiHome /system/bin/lg-simplink.sh
chmod 644 /system/priv-app/PiHome/PiHome.apk /system/etc/permissions/privapp-permissions-pihome.xml /system/etc/init/lg-simplink.rc
chcon -R u:object_r:system_file:s0 /system/priv-app/PiHome
chcon u:object_r:system_file:s0 /system/etc/permissions/privapp-permissions-pihome.xml /system/bin/lg-simplink.sh /system/etc/init/lg-simplink.rc
sync
mount -o remount,ro /
rm /data/local/tmp/PiHome.apk /data/local/tmp/privapp-permissions-pihome.xml /data/local/tmp/lg-simplink.sh /data/local/tmp/lg-simplink.rc'

echo "== handing HDMI-CEC to the bridge"
# Android's own CEC answers the LG TV as a generic player, and the TV then never forwards the remote
a shell cmd hdmi_control cec_setting set hdmi_cec_enabled 0

echo "== rebooting so the system app and the boot service are picked up"
a reboot
wait_for_boot
as_root

echo "== settings and permissions"
UID_=$(a shell dumpsys package $PKG | grep -m1 -oE "appId=[0-9]+" | cut -d= -f2 | tr -d '\r')
a shell "
cmd package set-home-activity $PKG/.MainActivity
pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
appops set $PKG SYSTEM_ALERT_WINDOW allow
appops set $PKG WRITE_SETTINGS allow
# LineageOS can install an app with no network access (uid policy REJECT_ALL); setUidPolicy(uid, 0)
service call netpolicy 1 i32 $UID_ i32 0 >/dev/null
# Android cuts the network of apps it considers in the background, which breaks SmartTube's
# background playback (UnknownHostException) and Pi Home's update and prayer-time downloads
cmd deviceidle whitelist +$PKG
cmd deviceidle whitelist +org.smarttube.stable
# the cached-app freezer stops SmartTube's audio-only background playback (no foreground service);
# frozen apps keep their memory anyway, so this only costs a little idle CPU. Needs a reboot.
settings put global cached_apps_freezer disabled
settings put secure screensaver_enabled 1
settings put secure screensaver_components $PKG/.ClockDream
settings put secure screensaver_default_component $PKG/.ClockDream
am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
"

echo "== checking"
a shell "
echo \"bridge: \$(getprop init.svc.lg-simplink)\"
dumpsys package $PKG | grep -E '(ACCESS_ALL_EPG_DATA|MEDIA_CONTENT_CONTROL): granted' | sort -u
echo \"home: \$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)\"
"
a unroot >/dev/null 2>&1 || true

echo
echo "Done. If the LG remote doesn't respond, switch the TV input away from HDMI 3 and back."
