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
# A cold start draws a flat first frame and turns the glass on once the window has focus (GlassStage). The frame that
# turns it on builds the glass shaders: up to 7 s on this software GPU when the cache is empty (CI 40656925148). A tap
# while Main waits behind that frame is an ANR, so wait for this process's "glass on" line and that frame first.
wait_glass() {
  local pid; pid=$($T adb shell pidof "$PKG" | tr -d '\r')
  for i in $(seq 1 30); do
    $T adb logcat -d -s PagelGlass:I | grep -q " $pid .*glass on" && break
    sleep 1
  done
  echo "glass on: $($T adb logcat -d -s PagelGlass:I | grep " $pid " | tail -1 | sed 's/.*PagelGlass: //')"
  sleep 8
}

echo "== root adb (google_apis image): debuggerd -j needs it to dump the freeze stacks"
$T adb root; $T adb wait-for-device

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
python3 .github/scripts/launch-numbers.py "first launch (pair)" "$OUT/am-start.txt" "$OUT/logcat-launch.txt" | tee "$OUT/launch-numbers.txt"
echo "ok: running as pid $PID after 30 s, no crash, no ANR"

echo "== forced test crash"
$T adb shell am force-stop "$PKG"
$T adb shell run-as "$PKG" rm -rf files/crashes
$T adb shell am start -n "$PKG/.MainActivity" --ez pagel.test_crash true
# Wait on the file itself, up to 40 s: on CI's software GPU a cold start alone took 11 s, and one check after 10 s
# failed a docs-only commit (run 40530963214).
for i in $(seq 1 40); do
  FILES=$($T adb shell run-as "$PKG" ls files/crashes 2>/dev/null | tr -d '\r')
  case "$FILES" in *crash-*.json*) break;; esac
  sleep 1
done
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
grep -q "ANR in $PKG" "$OUT/logcat-relaunch.txt" && fail "ANR after relaunch"
echo "== pair with a code against the fake pagel.uz"
python3 .github/scripts/fake-pagel.py 8799 "$OUT/fake-pagel.log" &
FAKE=$!
# A failed step must not leave the fake server running: it kept run 40546542662's job open 35 min after the failure.
trap 'kill $FAKE 2>/dev/null' EXIT
sleep 1
$T adb reverse tcp:8799 tcp:8799
$T adb shell am force-stop "$PKG"
$T adb shell am start -W -n "$PKG/.MainActivity" --es pagel.server http://127.0.0.1:8799 > /dev/null
wait_glass
$T adb exec-out screencap -p > "$OUT/pair-screen.png"
$T adb shell input text 482913
sleep 1
$T adb shell input keyevent 66
# Wait on the real signal (the app opened the live stream), not a fixed sleep: the emulator draws in software.
for i in $(seq 1 40); do grep -q "GET /api/v1/stream" "$OUT/fake-pagel.log" && break; sleep 1; done
# 2026-10-27: the first frame after pairing froze the main thread ~13 s. Dump every thread's stack 3 s in, so the
# freeze names its own cause (debuggable build: debuggerd -j prints the Java stacks).
APP=$($T adb shell pidof "$PKG" | tr -d '\r')
sleep 3
[ -n "$APP" ] && $T adb shell debuggerd -j "$APP" > "$OUT/stacks-after-pair.txt" 2>&1
grep -A25 '"main"' "$OUT/stacks-after-pair.txt" | head -40
# Then up to 40 s for Today's first task to be on screen (uiautomator needs an idle app, so retry).
for i in $(seq 1 8); do
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml > "$OUT/paired-ui.xml" < /dev/null
  grep -q 'text="Ship the native app"' "$OUT/paired-ui.xml" && break
  sleep 5
done
$T adb exec-out screencap -p > "$OUT/paired-today.png"
for want in Today "Ship the native app" "Record the fractions lesson" Inbox More; do
  grep -q "text=\"$want\"" "$OUT/paired-ui.xml" || fail "after pairing the screen has no \"$want\""
done
grep -q "POST /api/v1/devices/pair" "$OUT/fake-pagel.log" || fail "the app never called pair"
grep -q "GET /api/v1/sync?since=0 auth=True" "$OUT/fake-pagel.log" || fail "no full sync with the device token"
grep -q "GET /api/v1/stream" "$OUT/fake-pagel.log" || fail "the app never opened the live stream"
grep -q "GET /api/v1/today auth=True" "$OUT/fake-pagel.log" || fail "the app never read Today's order"
echo "ok: paired, synced, listening live; Today's tasks are on screen"

echo "== leave and come back: the saved crash is sent"
$T adb shell input keyevent 3
sleep 2
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 5
grep -q "POST /api/v1/app/crash .*PAGEL test crash" "$OUT/fake-pagel.log" || fail "the forced crash never reached /api/v1/app/crash"
[ -z "$($T adb shell run-as "$PKG" ls files/crashes 2>/dev/null | tr -d '\r')" ] || fail "the crash file was not deleted after upload"
echo "ok: the forced crash reached the crash endpoint and left the phone"

# Boss 2026-10-28: "keeps asking to pair every time I update". The Home key above keeps the process (and the account in
# memory); only a cold start reads the sealed file back. Up to app-v0.8.1 it could not be opened after its rename.
echo "== a cold start keeps the pairing"
$T adb shell am force-stop "$PKG"
$T adb logcat -c
$T adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/am-start-cold.txt"
for i in $(seq 1 20); do
  sleep 2
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml > "$OUT/cold-start-ui.xml" < /dev/null
  grep -q 'text="Ship the native app"' "$OUT/cold-start-ui.xml" && break
done
grep -q 'text="Ship the native app"' "$OUT/cold-start-ui.xml" || fail "after a cold start the app asked to pair again (no Today)"
$T adb exec-out screencap -p > "$OUT/cold-start.png"
wait_glass
$T adb exec-out screencap -p > "$OUT/cold-start-glass.png"
grep -q "POST /api/v1/app/crash .*AccountUnreadable" "$OUT/fake-pagel.log" && fail "the saved account could not be opened (AccountUnreadable)"
echo "ok: force-stop and relaunch: still paired, Today on screen"
$T adb logcat -d > "$OUT/logcat-cold.txt"
python3 .github/scripts/launch-numbers.py "cold Today" "$OUT/am-start-cold.txt" "$OUT/logcat-cold.txt" | tee -a "$OUT/launch-numbers.txt"
COLD=$(tail -1 "$OUT/launch-numbers.txt")
echo "$COLD" | grep -q "anr=YES" && fail "ANR after a cold start (see logcat-cold.txt)"
TOTAL=$(echo "$COLD" | grep -oE 'TotalTime=[0-9-]+' | cut -d= -f2)
SKIP=$(echo "$COLD" | grep -oE 'skipped-launch=[0-9]+' | cut -d= -f2)
ISSUE=$(echo "$COLD" | grep -oE 'max-issue=[0-9]+' | cut -d= -f2)
# Targets, not gates: the glass frame's render time depends on whether CI's shader cache already holds the glass (51 ms
# when it does, 6,004-7,055 ms when it does not, same code; CI 40666139501, 40679698108). The ANR above is the gate.
[ "${ISSUE:-0}" -lt 4000 ] || echo "WARN: a cold Today frame spent ${ISSUE} ms on the render thread (target < 4000)"
[ "${TOTAL:-99999}" -lt 4000 ] || echo "WARN: cold Today TotalTime ${TOTAL} ms (target < 4000)"
[ "${SKIP:-0}" -le 100 ] || echo "WARN: cold Today skipped ${SKIP} frames at launch (target <= 100)"
echo "ok: cold Today TotalTime=${TOTAL} ms, max render frame ${ISSUE} ms, skipped ${SKIP} frames"
$T adb logcat -c
bash .github/scripts/walkthrough.sh || fail "the critic walkthrough crashed (see walk/logcat-walk.txt)"
kill $FAKE 2>/dev/null
cat "$OUT/fake-pagel.log"
echo "SMOKE PASSED"
