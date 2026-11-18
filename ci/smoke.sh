#!/bin/sh
# Install the release APK on the running emulator, launch it and fail when it
# crashes or is not running ten seconds later. The logcat goes to logcat.txt.
set -eu
apk="$1"
package=org.mindenit.glidephone
adb install -r "$apk"
adb logcat -c
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 10
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
    grep -A40 "FATAL EXCEPTION" logcat.txt
    exit 1
fi
if ! adb shell pidof "$package" >/dev/null; then
    echo "$package is not running"
    tail -80 logcat.txt
    exit 1
fi
echo "$package started and is running"
