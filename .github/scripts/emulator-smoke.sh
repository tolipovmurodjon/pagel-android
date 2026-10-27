#!/usr/bin/env bash
# Emulator smoke test (NATIVE-PLAN Phases 0 and 1): install the debug APK, open it, and prove:
#   1. PAGEL is still running 30 s after launch, with no crash and no ANR in logcat.
#   2. A forced test crash (debug builds only) is written to files/crashes/ by CrashRecorder.
#   3. Pairing with a 6-digit code against a fake pagel.uz (fake-pagel.py through adb reverse) shows the project
#      list on the glass shell, the app listens live, and the saved crash reaches POST /api/v1/app/crash.
# Evidence (screenshots, logcat, the crash file) goes to smoke-out/ for the workflow artifact.
set -u
# Any single adb step that takes longer than this is a failure, never a 30-minute hang.
T="timeout 60"
PKG=org.pagel.ai
OUT=smoke-out
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -1)
fail() { echo "SMOKE FAILED: $*"; timeout 30 adb logcat -d > "$OUT/logcat-at-failure.txt"; exit 1; }

echo "== install $APK"
$T adb install -r -g "$APK" || fail "install"

echo "== launch, wait 30 s"
$T adb logcat -c
$T adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/am-start.txt"
sleep 30
$T adb exec-out screencap -p > "$OUT/after-30s.png"
$T adb logcat -d > "$OUT/logcat-launch.txt"
PID=$($T adb shell pidof "$PKG" | tr -d '\r')
[ -n "$PID" ] || fail "PAGEL is not running 30 s after launch"
grep -q "Process: $PKG, PID" "$OUT/logcat-launch.txt" && fail "crash in logcat (see logcat-launch.txt)"
grep -q "ANR in $PKG" "$OUT/logcat-launch.txt" && fail "ANR in logcat"
echo "ok: running as pid $PID after 30 s, no crash, no ANR"

echo "== forced test crash"
$T adb shell am force-stop "$PKG"
$T adb shell run-as "$PKG" rm -rf files/crashes
$T adb shell am start -n "$PKG/.MainActivity" --ez pagel.test_crash true
sleep 10
FILES=$($T adb shell run-as "$PKG" ls files/crashes 2>/dev/null | tr -d '\r')
echo "files/crashes: $FILES"
case "$FILES" in *crash-*.json*) ;; *) fail "no crash file after the forced crash";; esac
# adb joins its arguments into one device command (quotes are lost), so name the one file: a glob inside
# "sh -c 'cat …'" became a bare `cat` that waited on stdin forever (2026-10-27, the first smoke run hung here).
FIRST=$(echo "$FILES" | tr ' ' '\n' | grep '^crash-.*\.json$' | head -1)
timeout 20 adb exec-out run-as "$PKG" cat "files/crashes/$FIRST" > "$OUT/crash-file.json" < /dev/null
head -c 400 "$OUT/crash-file.json"; echo
grep -q "PAGEL test crash" "$OUT/crash-file.json" || fail "crash file does not hold the test crash"
echo "ok: CrashRecorder wrote the forced crash"

echo "== relaunch after the crash, wait 15 s"
$T adb logcat -c
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 15
$T adb exec-out screencap -p > "$OUT/after-relaunch.png"
$T adb logcat -d > "$OUT/logcat-relaunch.txt"
[ -n "$($T adb shell pidof "$PKG" | tr -d '\r')" ] || fail "PAGEL did not come back after the crash"
grep -q "Process: $PKG, PID" "$OUT/logcat-relaunch.txt" && fail "crash after relaunch"
echo "== pair with a code against the fake pagel.uz"
python3 .github/scripts/fake-pagel.py 8799 "$OUT/fake-pagel.log" &
FAKE=$!
sleep 1
$T adb reverse tcp:8799 tcp:8799
$T adb shell am force-stop "$PKG"
$T adb shell am start -W -n "$PKG/.MainActivity" --es pagel.server http://127.0.0.1:8799 > /dev/null
sleep 4
$T adb exec-out screencap -p > "$OUT/pair-screen.png"
$T adb shell input text 482913
sleep 1
$T adb shell input keyevent 66
sleep 6
$T adb exec-out screencap -p > "$OUT/paired-projects.png"
$T adb shell uiautomator dump /sdcard/ui.xml > /dev/null
$T adb exec-out cat /sdcard/ui.xml > "$OUT/paired-ui.xml" < /dev/null
for want in Projects Humopedia ABABIL Today Inbox More; do
  grep -q "text=\"$want\"" "$OUT/paired-ui.xml" || fail "after pairing the screen has no \"$want\""
done
grep -q "POST /api/v1/devices/pair" "$OUT/fake-pagel.log" || fail "the app never called pair"
grep -q "GET /api/v1/sync?since=0 auth=True" "$OUT/fake-pagel.log" || fail "no full sync with the device token"
grep -q "GET /api/v1/stream" "$OUT/fake-pagel.log" || fail "the app never opened the live stream"
echo "ok: paired, synced, listening live; the project list is on screen"

echo "== leave and come back: the saved crash is sent"
$T adb shell input keyevent 3
sleep 2
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 5
grep -q "POST /api/v1/app/crash .*PAGEL test crash" "$OUT/fake-pagel.log" || fail "the forced crash never reached /api/v1/app/crash"
[ -z "$($T adb shell run-as "$PKG" ls files/crashes 2>/dev/null | tr -d '\r')" ] || fail "the crash file was not deleted after upload"
echo "ok: the forced crash reached the crash endpoint and left the phone"
kill $FAKE 2>/dev/null
cat "$OUT/fake-pagel.log"
echo "SMOKE PASSED"
