#!/usr/bin/env bash
# Emulator smoke test (NATIVE-PLAN Phase 0): install the debug APK, open it, and prove two things.
#   1. PAGEL is still running 30 s after launch, with no crash and no ANR in logcat.
#   2. A forced test crash (debug builds only) is written to files/crashes/ by CrashRecorder.
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
echo "SMOKE PASSED"
