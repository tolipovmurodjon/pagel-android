#!/usr/bin/env bash
# The critic rig (NATIVE-PLAN Phase 2, Boss 2026-10-27: "use it yourself like a real critic and improve the UI/UX").
# Runs after emulator-smoke.sh has paired the app with the fake pagel.uz. Uses the app like a person: every bottom
# tab, scroll, re-tap, back, rotate, 1.5× font, dark mode. Each step leaves a screenshot + UI tree in walk/, and
# the whole run leaves a screen recording and Android's own frame stats (janky %, slow frames) to judge by numbers.
set -u
T="timeout 60"
PKG=org.pagel.ai
OUT=smoke-out/walk
FAKE_LOG=${FAKE_LOG:-smoke-out/fake-pagel.log}
mkdir -p "$OUT"
N=0
log() { echo "$*" | tee -a "$OUT/steps.txt"; }

# A screenshot + the UI tree for step N. uiautomator needs an idle app, so it is retried briefly.
shot() {
  N=$((N + 1)); local name; name=$(printf "%02d-%s" "$N" "$1")
  sleep "${2:-1.5}"
  $T adb exec-out screencap -p > "$OUT/$name.png"
  for i in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break; sleep 2; done
  timeout 20 adb exec-out cat /sdcard/ui.xml > "$OUT/$name.xml" < /dev/null
  log "$name"
}

# Tap the centre of the first node whose text or content-desc equals $1. Returns 1 if it is not on screen.
tap() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local xml; xml=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null)
  local b; b=$(echo "$xml" | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || { log "   (no \"$1\" on screen)"; return 1; }
  local n; n=$(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $n
  $T adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

log "== critic walkthrough"
$T adb shell dumpsys gfxinfo "$PKG" reset > /dev/null
$T adb shell screenrecord --time-limit 170 /sdcard/walk.mp4 &
REC=$!
sleep 1

shot home
for tab in Today Inbox Habits Notebook More; do
  tap "$tab" && shot "tab-$tab"
done
log "-- projects live under More now"
tap Projects && shot projects
grep -q 'text="Humopedia"' "$OUT/$(printf '%02d' $N)-projects.xml" || { log "WALKTHROUGH: Projects under More shows no projects"; exit 1; }
$T adb shell input swipe 540 1700 540 500 400; shot projects-down 1.5
$T adb shell input swipe 540 900 540 1150 300; shot projects-up-shows-title 1.5
$T adb shell input keyevent 4; shot back-to-more
tap Today && shot today-again

log "-- tick the first task: ring fills, confetti, the row folds, the 4th task is dealt, the tick reaches pagel.uz"
tap "Done: Ship the native app" && shot ticked 0.3
shot after-fold 2.5
grep -q 'text="Measure the drone' "$OUT/$(printf '%02d' $N)-after-fold.xml" || { log "WALKTHROUGH: after the tick the 4th task was not dealt"; exit 1; }
for i in $(seq 1 30); do grep -q "PATCH /api/v1/tasks/t_ci1 {\"done\":true} auth=True" "$FAKE_LOG" && break; sleep 1; done
grep -q "PATCH /api/v1/tasks/t_ci1" "$FAKE_LOG" || { log "WALKTHROUGH: the tick never reached pagel.uz (no PATCH in the fake log)"; exit 1; }
log "   tick reached pagel.uz after ~${i} s"

log "-- add a task fast: + → type the line → Add task → it reaches pagel.uz with project and priority read from it"
tap "Add a task" && shot add-sheet 2
$T adb shell "input text 'Call%sAziz%stomorrow%s3pm%s#me%sp1'"; shot add-typed 1.5
tap "Add task" && shot add-saved 1.5
for i in $(seq 1 30); do grep -q 'POST /api/v1/tasks .*"text":"Call Aziz"' "$FAKE_LOG" && break; sleep 1; done
grep -q 'POST /api/v1/tasks {"project":"me","text":"Call Aziz","priority":0,"due":"[0-9-]* 15:00"}' "$FAKE_LOG"   || { log "WALKTHROUGH: the added task never reached pagel.uz as {me, Call Aziz, P0, tomorrow 15:00}"; grep "POST /api/v1/tasks" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   added task reached pagel.uz after ~${i} s"
$T adb shell input keyevent 4; sleep 1; $T adb shell input keyevent 4; shot after-add 1.5
$T adb shell input swipe 540 1700 540 600 300; shot today-scrolled
tap Today && shot today-retap-top
$T adb shell input keyevent 4; shot after-back

log "-- rotate"
$T adb shell settings put system accelerometer_rotation 0
$T adb shell settings put system user_rotation 1; shot landscape 3
$T adb shell settings put system user_rotation 0; shot portrait-again 3

log "-- font 1.5x"
$T adb shell settings put system font_scale 1.5; shot font-1.5 3
$T adb shell settings put system font_scale 1.0

log "-- dark mode"
$T adb shell cmd uimode night yes; shot dark 3
$T adb shell cmd uimode night no; shot light-again 3

kill -INT $REC 2>/dev/null; sleep 3
timeout 60 adb pull /sdcard/walk.mp4 "$OUT/walk.mp4" > /dev/null 2>&1
$T adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
log "-- frames (Android's own counters for the whole walkthrough)"
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile|Number Slow UI thread|Number Frozen frames" "$OUT/gfxinfo.txt" | tee -a "$OUT/steps.txt"
$T adb logcat -d > "$OUT/logcat-walk.txt"
grep -E "Davey!|Skipped [0-9]+ frames" "$OUT/logcat-walk.txt" | tail -15 | tee -a "$OUT/steps.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-walk.txt" && { log "WALKTHROUGH: CRASH (see logcat-walk.txt)"; exit 1; }
log "WALKTHROUGH DONE: $N steps"
