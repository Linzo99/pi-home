#!/bin/sh
# Builds Pi AirPlay and installs it on the Pi, with the permissions and exemptions it needs.
# Usage: PI_HOST=192.168.1.116:5555 ./install.sh
set -e
cd "$(dirname "$0")"
PI="${PI_HOST:-192.168.1.116:5555}"
PKG=com.sall.piairplay
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
a() { adb -s "$PI" "$@"; }

[ -f piairplay.keystore ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore piairplay.keystore \
    -alias piairplay -keyalg RSA -keysize 2048 -validity 10000 -storepass piairplay \
    -keypass piairplay -dname "CN=Pi AirPlay"
./gradlew -q assembleRelease

adb connect "$PI" >/dev/null
a install -r app/build/outputs/apk/release/app-release.apk
UID_=$(a shell dumpsys package $PKG | grep -m1 -oE "appId=[0-9]+" | cut -d= -f2 | tr -d '\r')
a shell "
# opening the mirror screen by itself when a Mac or iPhone connects
appops set $PKG SYSTEM_ALERT_WINDOW allow
pm grant $PKG android.permission.POST_NOTIFICATIONS
# Android 16 cuts the network of apps in the background, and the receiver always is
cmd deviceidle whitelist +$PKG
# LineageOS can install an app with no network access (uid policy REJECT_ALL)
service call netpolicy 1 i32 $UID_ i32 0 >/dev/null
am start -n $PKG/.MirrorActivity >/dev/null
sleep 3
input keyevent BACK
"
echo "Installed. On the Mac or iPhone, choose « Pi » in Screen Mirroring."
