#!/bin/sh
# Builds Pi Home without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh            build build/pihome.apk
#        ./build.sh install    build, install on the Pi and make it the Home app
# Pi Home also lives in /system/priv-app/PiHome (for ACCESS_ALL_EPG_DATA) with its whitelist in
# /system/etc/permissions/privapp-permissions-pihome.xml; "install" updates over that copy. If the
# manifest ever requests another privileged permission, add it to the whitelist and refresh the
# system copy first, or the Pi will not boot.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
PI="${PI_HOST:-192.168.1.116:5555}"

rm -rf build && mkdir -p build/gen build/classes

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 28 --target-sdk-version 35 build/res.zip

javac --release 11 -Xlint:-options -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output build $(find build/classes -name '*.class')

(cd build && zip -qj unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

[ -f pihome.keystore ] || keytool -genkeypair -keystore pihome.keystore -alias pihome \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass pihome -keypass pihome -dname "CN=Pi Home"
"$BT/apksigner" sign --ks pihome.keystore --ks-pass pass:pihome --out build/pihome.apk build/aligned.apk
ls -l build/pihome.apk

if [ "$1" = install ]; then
    adb connect "$PI" >/dev/null
    adb -s "$PI" install -r build/pihome.apk
    adb -s "$PI" shell pm grant com.sall.pihome android.permission.ACCESS_FINE_LOCATION || true
    adb -s "$PI" shell cmd package set-home-activity com.sall.pihome/.MainActivity
    adb -s "$PI" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
fi
