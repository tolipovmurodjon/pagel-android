#!/usr/bin/env bash
# Emulator smoke test (NATIVE-PLAN Phase 0): install the debug APK, open it, and prove two things.
#   1. PAGEL is still running 30 s after launch, with no crash and no ANR in logcat.
#   2. A forced test crash (debug builds only) is written to files/crashes/ by CrashRecorder.
# Evidence (screenshots, logcat, the crash file) goes to smoke-out/ for the workflow artifact.
set -u
PKG=org.pagel.ai
OUT=smoke-out
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -1)
fail() { echo "SMOKE FAILED: $*"; adb logcat -d > "$OUT/logcat-at-failure.txt"; exit 1; }

echo "== install $APK"
adb install -r -g "$APK" || fail "install"

echo "== launch, wait 30 s"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/am-start.txt"
sleep 30
adb exec-out screencap -p > "$OUT/after-30s.png"
adb logcat -d > "$OUT/logcat-launch.txt"
PID=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$PID" ] || fail "PAGEL is not running 30 s after launch"
grep -q "Process: $PKG, PID" "$OUT/logcat-launch.txt" && fail "crash in logcat (see logcat-launch.txt)"
grep -q "ANR in $PKG" "$OUT/logcat-launch.txt" && fail "ANR in logcat"
echo "ok: running as pid $PID after 30 s, no crash, no ANR"

echo "== forced test crash"
adb shell am force-stop "$PKG"
adb shell run-as "$PKG" rm -rf files/crashes
adb shell am start -n "$PKG/.MainActivity" --ez pagel.test_crash true
sleep 10
FILES=$(adb shell run-as "$PKG" ls files/crashes 2>/dev/null | tr -d '\r')
echo "files/crashes: $FILES"
case "$FILES" in *crash-*.json*) ;; *) fail "no crash file after the forced crash";; esac
adb shell run-as "$PKG" sh -c 'cat files/crashes/crash-*.json' > "$OUT/crash-file.json"
head -c 400 "$OUT/crash-file.json"; echo
grep -q "PAGEL test crash" "$OUT/crash-file.json" || fail "crash file does not hold the test crash"
echo "ok: CrashRecorder wrote the forced crash"

echo "== relaunch after the crash, wait 15 s"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 15
adb exec-out screencap -p > "$OUT/after-relaunch.png"
adb logcat -d > "$OUT/logcat-relaunch.txt"
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || fail "PAGEL did not come back after the crash"
grep -q "Process: $PKG, PID" "$OUT/logcat-relaunch.txt" && fail "crash after relaunch"
echo "SMOKE PASSED"
