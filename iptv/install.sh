#!/bin/sh
# Builds Pi TV and installs it on the Pi.
# Usage: PI_HOST=192.168.1.116:5555 ./install.sh
set -e
cd "$(dirname "$0")"
PI="${PI_HOST:-192.168.1.116:5555}"
PKG=com.sall.pitv
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
a() { adb -s "$PI" "$@"; }

[ -f pitv.keystore ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore pitv.keystore \
    -alias pitv -keyalg RSA -keysize 2048 -validity 10000 -storepass pitvpitv \
    -keypass pitvpitv -dname "CN=Pi TV"
./gradlew -q assembleRelease

adb connect "$PI" >/dev/null
a install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
UID_=$(a shell dumpsys package $PKG | grep -m1 -oE "appId=[0-9]+" | cut -d= -f2 | tr -d '\r')
# LineageOS can install an app with no network access (uid policy REJECT_ALL)
a shell "service call netpolicy 1 i32 $UID_ i32 0 >/dev/null"
# the launcher row's "Chaînes récentes" channel: the stock launcher would ask, Pi Home needs it
# set (root; the channel exists once Pi TV has run, so a first install needs a second run)
a root >/dev/null 2>&1 && sleep 2 && adb connect "$PI" >/dev/null || true
a shell "content update --uri content://android.media.tv/channel --where \"package_name='$PKG'\" --bind browsable:i:1" 2>/dev/null || true
echo "Installed. Open Pi TV from Pi Home."
