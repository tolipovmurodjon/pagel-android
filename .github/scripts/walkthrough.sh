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
  grep -q "isn't responding" "$OUT/$name.xml" && { log "WALKTHROUGH: ANR dialog on step $name"; exit 1; }
  return 0
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

# A habit row's tick is its ring at the row's start: the row's centre can be its week dots (round B checker #4 put
# them on the meta line under the title).
tapring() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local xml; xml=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null)
  local b; b=$(echo "$xml" | tr '>' '\n' | grep -E "content-desc=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || { log "   (no \"$1\" on screen)"; return 1; }
  local n; n=$(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $n
  $T adb shell input tap $(( $1 + 75 )) $(( ($2 + $4) / 2 ))
}

# The PAGEL screen must be the one in front before a step that judges it (checker 2026-10-27: steps 27-31 shot the Pixel
# launcher, because Back on Today at step 26 had left the app). Waits up to 10 s for the focus to settle.
front() {
  local f=""
  for i in $(seq 1 10); do
    f=$($T adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus=' | tr -d '\r')
    echo "$f" | grep -q "$PKG/" && return 0
    sleep 1
  done
  log "WALKTHROUGH: PAGEL is not in front before \"$1\" ($f)"
  exit 1
}

log "== critic walkthrough"
# The launch numbers the smoke measured just before (launch-numbers.py: TotalTime, render ms, skipped frames, glass steps).
[ -f smoke-out/launch-numbers.txt ] && { log "-- launch numbers"; cat smoke-out/launch-numbers.txt | tee -a "$OUT/steps.txt"; }
$T adb shell dumpsys gfxinfo "$PKG" reset > /dev/null
$T adb shell screenrecord --time-limit 179 /sdcard/walk.mp4 &
REC=$!
trap 'kill -INT $REC 2>/dev/null' EXIT
sleep 1

shot home
for tab in Today Inbox Habits Notebook More; do
  tap "$tab" && shot "tab-$tab"
done
log "-- projects live under More now"
# More grew (Compass, Notes): Projects sits under the tab bar on a 640 dp screen, so scroll it up before the tap.
$T adb shell input swipe 160 450 160 120 400; sleep 1
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
# Round B (H): every tick throws confetti from the ring (the app logs each burst as it starts: a 1.1 s burst is gone
# before a UI dump) and Today's progress bar counts it.
$T adb logcat -d -s PagelFinish:I | grep -q "confetti" || { log "WALKTHROUGH: the tick threw no confetti (no PagelFinish line)"; exit 1; }
grep -qE 'content-desc="[1-9][0-9]* of [0-9]+ done today"' "$OUT/$(printf '%02d' $N)-after-fold.xml" || { log "WALKTHROUGH: Today has no progress bar counting the tick"; exit 1; }
log "   confetti thrown, progress: $(grep -oE '[0-9]+ of [0-9]+ done today' "$OUT/$(printf '%02d' $N)-after-fold.xml" | head -1)"
for i in $(seq 1 30); do grep -q "PATCH /api/v1/tasks/t_ci1 {\"done\":true} auth=True" "$FAKE_LOG" && break; sleep 1; done
grep -q "PATCH /api/v1/tasks/t_ci1" "$FAKE_LOG" || { log "WALKTHROUGH: the tick never reached pagel.uz (no PATCH in the fake log)"; exit 1; }
log "   tick reached pagel.uz after ~${i} s"

log "-- core round 4 (Today, the starting machine): All today opens in place, drag #5 to #1 = ONE order PATCH; What now -> Start -> tick reaches pagel.uz; Make it tiny (4 steps) -> Focus -> tick all -> the task is done"
# The shared helpers (centre, wait_log, tap_scroll) are defined further down, so this walk keeps its own small ones.
r4_xml() { $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null; }
r4_centre() {
  local b; b=$(r4_xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || return 1
  set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
r4_wait() { for i in $(seq 1 "${2:-30}"); do grep -qF "$1" "$FAKE_LOG" && return 0; sleep 1; done; return 1; }
r4_tap() { for k in 1 2 3; do tap "$1" && return 0; $T adb shell input swipe 160 460 160 260 300; sleep 1; done; return 1; }
# The Now card makes the folded list taller: scroll to the end, and the All today row must sit above the tab bar (not under it or the +).
for k in 1 2 3; do $T adb shell input swipe 160 520 160 200 300; done; sleep 1
R4X=$(r4_xml | tr '>' '
')
ROW_B=$(echo "$R4X" | grep -E 'content-desc="All today · [0-9]+"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | sed -n 4p)
NAV_T=$(echo "$R4X" | grep -E 'text="Inbox"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | sed -n 2p)
[ -n "$ROW_B" ] && [ -n "$NAV_T" ] || { log "WALKTHROUGH: no All today row ($ROW_B) or tab bar ($NAV_T) to measure"; exit 1; }
[ "$ROW_B" -le "$NAV_T" ] || { log "WALKTHROUGH: All today sits under the tab bar (row bottom $ROW_B, tab bar top $NAV_T)"; exit 1; }
log "   All today's bottom $ROW_B is above the tab bar's top $NAV_T"
tap 'All today · [0-9]+' || { log "WALKTHROUGH: Today has no All today row"; exit 1; }
sleep 1; for k in 1 2 3 4; do $T adb shell input swipe 160 260 160 520 300; done; sleep 1
shot r4-all-today 2
for t in "Call the bank about the report" "Weigh in" "Clean the desk" "Write the release note"; do
  grep -q "text=\"$t\"" "$OUT/$(printf '%02d' $N)-r4-all-today.xml" || { log "WALKTHROUGH: All today did not open in place (no \"$t\")"; exit 1; }
done
# (Show 3 sits under the last row, below the fold on a 640 dp screen: it is tapped after the drag, scrolled to.)
FROM=$(r4_centre "Clean the desk") || { log "WALKTHROUGH: no Clean the desk row to drag"; exit 1; }
TO=$(r4_centre "Call the bank about the report") || { log "WALKTHROUGH: no first row to drop on"; exit 1; }
set -- $FROM $TO
log "   drag #5 (Clean the desk) from $1,$2 to the first row at $3,$4"
$T adb shell input draganddrop $1 $2 $3 $(( $4 - 12 )) 1800
shot r4-dragged 2
r4_wait 'PATCH /api/v1/tasks/t_now1 {"today_sort":' 30 || { log "WALKTHROUGH: the drag sent no order PATCH"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
sleep 3
ORDERS=$(grep -c '"today_sort"' "$FAKE_LOG")
[ "$ORDERS" -eq 1 ] || { log "WALKTHROUGH: one drag sent $ORDERS order PATCHes (want exactly 1)"; grep today_sort "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got ONE order PATCH: $(grep -o 'PATCH /api/v1/tasks/t_now1 {"today_sort":[^}]*}' "$FAKE_LOG" | head -1)"
A=$(r4_centre "Clean the desk"); B=$(r4_centre "Call the bank about the report")
[ -n "$A" ] && [ -n "$B" ] && [ "${A#* }" -lt "${B#* }" ] || { log "WALKTHROUGH: after the drag Clean the desk is not above Call the bank ($A vs $B)"; exit 1; }
# Show 3 sits under the last row: scroll to the end so it clears the tab bar, then tap.
for k in 1 2; do $T adb shell input swipe 160 520 160 200 300; done; sleep 1
r4_tap "Show 3" || { log "WALKTHROUGH: the open list has no Show 3 chevron"; exit 1; }
# The list is taller than the screen now (the Now card + the bottom inset): back to the top for the Now card.
sleep 1; for k in 1 2 3 4; do $T adb shell input swipe 160 260 160 520 300; done; sleep 1
shot r4-folded 2
grep -q 'text="Clean the desk"' "$OUT/$(printf '%02d' $N)-r4-folded.xml" || { log "WALKTHROUGH: the dragged task is not one of the 3 after folding"; exit 1; }
grep -q 'text="Measure the drone' "$OUT/$(printf '%02d' $N)-r4-folded.xml" && { log "WALKTHROUGH: folded Today still shows a 4th task"; exit 1; }

log "-- What now: energy once, one pick (Clean the desk is first in Boss's order), Start · 10 min, the tick reaches pagel.uz"
r4_tap 'What now\?' || { log "WALKTHROUGH: no What now button on Today"; exit 1; }
shot r4-energy 2
tap "Energy: OK" || { log "WALKTHROUGH: What now did not ask for the energy"; exit 1; }
shot r4-what-now 2
grep -q 'content-desc="Start 10 minutes: Clean the desk"' "$OUT/$(printf '%02d' $N)-r4-what-now.xml" || { log "WALKTHROUGH: What now did not pick Clean the desk with Start · 10 min"; exit 1; }
# Clean the desk has no steps: the first step is the plain 30-second start, and the card offers Make it tiny (checker round 2 #1).
grep -q 'Put it in front of you · 30 s' "$OUT/$(printf '%02d' $N)-r4-what-now.xml" || { log "WALKTHROUGH: a task with no steps did not get the 30-second first step"; exit 1; }
grep -q 'content-desc="Make it tiny"' "$OUT/$(printf '%02d' $N)-r4-what-now.xml" || { log "WALKTHROUGH: the pick of a task with no steps has no Make it tiny link"; exit 1; }
r4_wait '"event":"what_now_pick"' 30 || { log "WALKTHROUGH: no what_now_pick event reached pagel.uz"; exit 1; }
tap "Start 10 minutes: Clean the desk" && shot r4-running 2
grep -qE 'content-desc="(9:[0-9]{2}|10:00) left"' "$OUT/$(printf '%02d' $N)-r4-running.xml" || { log "WALKTHROUGH: the 10-minute clock is not running"; exit 1; }
tap "Done: Clean the desk" && shot r4-what-now-done 0.5
r4_wait 'PATCH /api/v1/tasks/t_now1 {"done":true}' 30 || { log "WALKTHROUGH: What now's tick never reached pagel.uz"; grep t_now1 "$FAKE_LOG" | tail -3 | tee -a "$OUT/steps.txt"; exit 1; }
log "   What now: picked, started, ticked; pagel.uz has t_now1 done"
shot r4-after-what-now 2

log "-- Make it tiny: open Write the release note, spice 2, 4 funny steps from pagel.uz, Start -> Focus 1 of 4 -> tick all -> done"
$T adb shell input swipe 160 460 160 260 300; sleep 1
tap 'All today · [0-9]+' || r4_tap 'All today · [0-9]+' || { log "WALKTHROUGH: no All today row the second time"; exit 1; }
sleep 1; $T adb shell input swipe 160 260 160 520 300; $T adb shell input swipe 160 260 160 520 300; sleep 1
r4_tap "Write the release note" && shot r4-panel 2.5 || { log "WALKTHROUGH: Write the release note is not on the open list"; exit 1; }
r4_tap "Make it tiny" || { log "WALKTHROUGH: the panel has no Make it tiny row"; exit 1; }
shot r4-tiny-sheet 2
tap "Write tiny steps" || { log "WALKTHROUGH: the tiny sheet has no Write tiny steps"; exit 1; }
r4_wait 'POST /api/v1/tiny {"id":"t_now2","spice":2}' 30 || { log "WALKTHROUGH: Make it tiny never asked pagel.uz"; exit 1; }
shot r4-tiny-steps 2.5
grep -q 'Stand up. Yes, right now' "$OUT/$(printf '%02d' $N)-r4-tiny-steps.xml" || { log "WALKTHROUGH: the 4 tiny steps did not show"; exit 1; }
tap "Start focus" || { log "WALKTHROUGH: no Start on the tiny steps"; exit 1; }
r4_wait 'PATCH /api/v1/tasks/t_now2 {"micro":["Stand up.' 30 || { log "WALKTHROUGH: the micro-steps were never saved to pagel.uz"; grep t_now2 "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
shot r4-focus-1 2.5
grep -q 'content-desc="Step 1 of 4"' "$OUT/$(printf '%02d' $N)-r4-focus-1.xml" || { log "WALKTHROUGH: Focus mode did not open on step 1 of 4"; exit 1; }
for k in 1 2 3 4; do
  tap 'Done: [^"]*' || { log "WALKTHROUGH: Focus step $k has no tick"; exit 1; }
  [ "$k" = 2 ] && shot r4-focus-3 1.2 || sleep 1.5
done
r4_wait 'PATCH /api/v1/tasks/t_now2 {"done":true}' 30 || { log "WALKTHROUGH: the last micro-step did not finish the task on pagel.uz"; grep t_now2 "$FAKE_LOG" | tail -4 | tee -a "$OUT/steps.txt"; exit 1; }
MD=$(grep -c '"event":"micro_done"' "$FAKE_LOG"); log "   Focus: 4 ticks, micro_done events: $MD, the task is done on pagel.uz"
shot r4-focus-finished 2
$T adb shell input keyevent 4; sleep 1.5
front r4-back-on-today
r4_tap "Show 3" || true
shot r4-today-end 2
$T adb logcat -d > "$OUT/logcat-r4.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-r4.txt" && { log "WALKTHROUGH: CRASH in core round 4 (see logcat-r4.txt)"; exit 1; }
log "CORE ROUND 4 WALK DONE: $N steps"

log "-- add a task fast: + → type the line → Add task → it reaches pagel.uz with project and priority read from it"
tap "Add a task" && shot add-sheet 2
$T adb shell "input text 'Call%sAziz%stomorrow%s3pm%s#me%sp1'"; shot add-typed 1.5
tap "Add task" && shot add-saved 1.5
for i in $(seq 1 30); do grep -q 'POST /api/v1/tasks .*"text":"Call Aziz"' "$FAKE_LOG" && break; sleep 1; done
grep -qE 'POST /api/v1/tasks \{"project":"me","text":"Call Aziz","priority":0,"due":"[0-9-]* 15:00","client_id":"local_[0-9a-z]+"\}' "$FAKE_LOG"   || { log "WALKTHROUGH: the added task never reached pagel.uz as {me, Call Aziz, P0, tomorrow 15:00}"; grep "POST /api/v1/tasks" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   added task reached pagel.uz after ~${i} s"
# Round B (E): the sheet closes by itself once the task is added (it used to stay open under the toast).
shot after-add 1.5
grep -q 'content-desc="Add task"' "$OUT/$(printf '%02d' $N)-after-add.xml" && { log "WALKTHROUGH: the Add sheet stayed open after the task was added"; exit 1; }
front after-add

log "-- the task panel (Phase 1): tap a row → its panel; tick step 1, set P1, pick a day 2 days ahead in the calendar"
DAY=$(TZ=Asia/Tashkent date -d '+2 days' +%Y-%m-%d)
SPOKEN=$(LC_ALL=C TZ=Asia/Tashkent date -d '+2 days' '+%-d %B %Y')
tap "Call the bank about the report" && shot panel-open 2.5
grep -q 'Find the account number' "$OUT/$(printf '%02d' $N)-panel-open.xml" || { log "WALKTHROUGH: the task panel did not open with its steps"; exit 1; }
tap "Done: Find the account number" && shot panel-step-ticked 1.5
# Priority is a row like Date and Repeat (checker 2026-10-28 #8): it opens its sheet, P1 picks and closes it.
# Core round 4 put Make it tiny / I'm stuck above the props: on a 640 dp screen Priority is scrolled to first.
r4_tap "Priority: P[1-4]" && shot panel-priority-sheet 2
tap "P1" && shot panel-p1 1.5
tap "Date: None" && shot panel-date-sheet 2.5
grep -q "content-desc=\"$SPOKEN\"" "$OUT/$(printf '%02d' $N)-panel-date-sheet.xml" || { log "WALKTHROUGH: the calendar has no day \"$SPOKEN\""; exit 1; }
tap "$SPOKEN" && shot panel-day-picked 2.5
for want in '{"steps":["Find the account number","Call at 10"],"ticked":[0]}' '{"priority":0}' "{\"due\":\"$DAY\"}"; do
  for i in $(seq 1 30); do grep -qF "PATCH /api/v1/tasks/t_ci2 $want auth=True" "$FAKE_LOG" && break; sleep 1; done
  grep -qF "PATCH /api/v1/tasks/t_ci2 $want auth=True" "$FAKE_LOG" || { log "WALKTHROUGH: pagel.uz never got $want"; grep "PATCH" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
  log "   pagel.uz got $want"
done
log "-- a time range (round B, D: \"10:00-12:00, 24 hour\"): Date -> Add time -> 09:00 -> + End -> Done; pagel.uz gets DAY 09:00-10:00"
tap 'Date: [^"]*' && shot panel-date-again 2
tap "Add time" && shot panel-time-wheel 1.5
tap "Set 09:00" && shot panel-time-start 1.5
tap "Add an end time" && shot panel-time-range 1.5
grep -q 'content-desc="End 10:00"' "$OUT/$(printf '%02d' $N)-panel-time-range.xml" || { log "WALKTHROUGH: the date sheet shows no end chip 10:00"; exit 1; }
tap "Done" && shot panel-range-done 1.5
for i in $(seq 1 30); do grep -qF "PATCH /api/v1/tasks/t_ci2 {\"due\":\"$DAY 09:00-10:00\"} auth=True" "$FAKE_LOG" && break; sleep 1; done
grep -qF "PATCH /api/v1/tasks/t_ci2 {\"due\":\"$DAY 09:00-10:00\"} auth=True" "$FAKE_LOG" || { log "WALKTHROUGH: pagel.uz never got the range"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got $DAY 09:00-10:00"
grep -q 'content-desc="Date: [^"]*09:00–10:00"' "$OUT/$(printf '%02d' $N)-panel-range-done.xml" || log "   (WARN: the panel's Date row does not read 09:00–10:00)"
log "-- the panel's ring (Boss 2026-10-28: \"no sound, no toast, not marking strike\"): the panel stays open on the done task with Reopen and a Done · Undo toast; pagel.uz gets done, then Reopen opens it again"
$T adb logcat -c
# The Priority step scrolled the panel down (core round 4 rows): back to its top, where the ring is.
# One slow swipe back (Priority took one 200 px swipe): slow, so the full sheet never reads it as a fling to close.
onscreen_r4() { r4_xml | grep -qE "(text|content-desc)=\"$1\""; }
onscreen_r4 'Complete: Call the bank about the report' || $T adb shell input swipe 160 260 160 450 2500
tap "Complete: Call the bank about the report" && shot panel-ticked 0.6
X="$OUT/$(printf '%02d' $N)-panel-ticked.xml"
grep -q 'content-desc="Reopen"' "$X" || { log "WALKTHROUGH: after its ring the panel did not stay open on the done task (no Reopen)"; exit 1; }
grep -q 'text="Undo"' "$X" || { log "WALKTHROUGH: no Undo toast after the panel's ring"; exit 1; }
$T adb logcat -d -s PagelFinish:I | grep -q "confetti" || { log "WALKTHROUGH: the panel's ring threw no confetti"; exit 1; }
for i in $(seq 1 30); do grep -qF 'PATCH /api/v1/tasks/t_ci2 {"done":true} auth=True' "$FAKE_LOG" && break; sleep 1; done
grep -qF 'PATCH /api/v1/tasks/t_ci2 {"done":true} auth=True' "$FAKE_LOG" || { log "WALKTHROUGH: the panel's tick never reached pagel.uz"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   the panel's tick reached pagel.uz after ~${i} s"
tap Reopen && shot panel-reopened 1.5
for i in $(seq 1 30); do grep -qF 'PATCH /api/v1/tasks/t_ci2 {"done":false} auth=True' "$FAKE_LOG" && break; sleep 1; done
grep -qF 'PATCH /api/v1/tasks/t_ci2 {"done":false} auth=True' "$FAKE_LOG" || { log "WALKTHROUGH: Reopen never reached pagel.uz"; exit 1; }
$T adb shell input keyevent 4; shot panel-closed 1.5

wait_picker() { for i in $(seq 1 30); do grep -qE "$1" "$FAKE_LOG" && return 0; sleep 1; done; return 1; }
xml_of() { echo "$OUT/$(printf '%02d' $N)-$1.xml"; }
# Every window: a half sheet's toast is its own popup window, which the plain dump (the active window only) leaves out.
dump_all() {
  $T adb shell rm -f /sdcard/uiw.xml > /dev/null 2>&1
  $T adb shell uiautomator dump --windows /sdcard/uiw.xml > /dev/null 2>&1
  local x; x=$(timeout 20 adb exec-out cat /sdcard/uiw.xml < /dev/null 2>/dev/null)
  if echo "$x" | grep -q '<node'; then echo "$x"; return; fi
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null
}
DPI=$($T adb shell wm density | grep -oE '[0-9]+' | tail -1); DPI=${DPI:-160}
SW=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); SW=${SW:-320}
# The Undo on the toast above the Add sheet: by its node when the dump has it, else by its place (the right end of the
# toast, 83 dp above the middle of the sheet's "New task" title, as measured on CI 41076461041 shot 35).
undo_toast() {
  local all b; all=$(dump_all | tr '>' '\n')
  b=$(echo "$all" | grep -E '(text|content-desc)="Undo"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  if [ -n "$b" ]; then
    set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
    log "   Undo found in the dump at $(( ($1 + $3) / 2 )),$(( ($2 + $4) / 2 ))"
    $T adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); return 0
  fi
  b=$(echo "$all" | grep -F 'text="New task"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || return 1
  set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  local x=$(( SW - 65 * DPI / 160 )) y=$(( ($2 + $4) / 2 - 83 * DPI / 160 ))
  log "   Undo is not in the dump: tapping its place $x,$y above the sheet"
  $T adb shell input tap $x $y
}

log "-- the task panel's project (checker #2): open a task -> tap its project -> pick Humopedia; pagel.uz gets one PATCH {project}"
tap 'Measure the drone[^"]*' && shot move-panel 2.5 || { log "WALKTHROUGH: no Measure the drone task on Today"; exit 1; }
tap "Project: ABABIL" && shot move-picker 2 || { log "WALKTHROUGH: the panel's project capsule opens no picker (no \"Project: ABABIL\")"; exit 1; }
grep -q 'content-desc="New project"' "$(xml_of move-picker)" || { log "WALKTHROUGH: the panel's picker is not the full picker (no + New project)"; exit 1; }
tap Humopedia && shot move-picked 2 || { log "WALKTHROUGH: the panel's picker lists no Humopedia"; exit 1; }
wait_picker 'PATCH /api/v1/tasks/t_ci4 \{"project":"humopedia"\}' || { log "WALKTHROUGH: the move never reached pagel.uz"; grep "PATCH /api/v1/tasks/t_ci4" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
[ "$(grep -cE 'PATCH /api/v1/tasks/t_ci4 \{"project"' "$FAKE_LOG")" -eq 1 ] || { log "WALKTHROUGH: one pick sent more than one PATCH {project}"; grep "PATCH /api/v1/tasks/t_ci4" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
grep -q 'content-desc="Project: Humopedia"' "$(xml_of move-picked)" || { log "WALKTHROUGH: after the pick the panel does not say Humopedia"; exit 1; }
# Humopedia's tasks mention lessons but are not a course: no Lesson steps chip (checker #9; CI 41086207276 shot 34 had one).
grep -q 'content-desc="Add lesson steps"' "$(xml_of move-picked)" && { log "WALKTHROUGH: a Humopedia task offers the Lesson steps chip (not a course)"; exit 1; }
log "   the task moved: one PATCH {project:humopedia}, the panel says Humopedia, no Lesson steps chip"
$T adb shell input keyevent 4; sleep 1.5
front move-closed

log "-- the project picker (Boss 2026-10-30): Add -> a task -> Project -> + New project \"CI Picker\" -> Enter -> Add; pagel.uz gets the project, then the task with that project"
tap "Add a task" && shot picker-add 2 || { log "WALKTHROUGH: no Add a task on Today"; exit 1; }
$T adb shell "input text 'Read%schapter%sone'"
tap "Project" && shot picker-open 2 || { log "WALKTHROUGH: the Add sheet has no Project chip"; exit 1; }
grep -q 'content-desc="New project"' "$(xml_of picker-open)" || { log "WALKTHROUGH: the project picker has no + New project row"; exit 1; }
tap "New project" && shot picker-field 1.5 || { log "WALKTHROUGH: + New project did not open"; exit 1; }
grep -q 'content-desc="New project name"' "$(xml_of picker-field)" || { log "WALKTHROUGH: + New project opened no name field"; exit 1; }
# Checker #10: with the keyboard up, at least 5 project rows stay in view (the title steps aside).
# The name field is a row too; each project row carries its ⋯ (Edit …).
ROWS=$(( $(tr '>' '\n' < "$(xml_of picker-field)" | grep -cE 'content-desc="Edit [^"]+"') + 1 ))
[ "$ROWS" -ge 5 ] || { log "WALKTHROUGH: with the keyboard up the picker shows only $ROWS rows (5 wanted)"; exit 1; }
log "   $ROWS rows in view with the keyboard up (the name field + projects)"
$T adb shell "input text 'CI%sPicker'"; $T adb shell input keyevent 66
shot picker-created 1.5
# Checker #1: the chosen project is on screen in the Project chip (2nd chip, the row scrolled to it).
CB=$(tr '>' '\n' < "$(xml_of picker-created)" | grep -F 'text="CI Picker"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
[ -n "$CB" ] || { log "WALKTHROUGH: after the pick the Project chip does not show CI Picker"; exit 1; }
set -- $CB; [ "$1" -ge 0 ] && [ "$3" -le "$SW" ] && [ "$3" -gt "$1" ] || { log "WALKTHROUGH: the CI Picker chip is cut off the screen ($CB)"; exit 1; }
log "   the Project chip reads CI Picker, on screen at $CB"
tap "Add task" && shot picker-added 1.5
wait_picker 'POST /api/v1/tasks .*"project":"ci-picker"' || { log "WALKTHROUGH: the task never reached pagel.uz with project ci-picker"; grep "POST /api/v1/" "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
PP=$(grep -nE 'POST /api/v1/projects \{"name": ?"ci-picker"' "$FAKE_LOG" | head -1 | cut -d: -f1)
PT=$(grep -nE 'POST /api/v1/tasks .*"project":"ci-picker"' "$FAKE_LOG" | head -1 | cut -d: -f1)
[ -n "$PP" ] && [ -n "$PT" ] && [ "$PP" -lt "$PT" ] || { log "WALKTHROUGH: pagel.uz did not get the project (line ${PP:-none}) before the task (line ${PT:-none})"; exit 1; }
log "   pagel.uz got the project, then the task linked to it"

log "-- Undo a new project (checker #3): Add -> Project -> + New project \"CI Picker 2\" -> Enter -> the toast's Undo; pagel.uz deletes it or never hears of it"
tap "Add a task" && shot undo-add 2 || { log "WALKTHROUGH: no Add a task on Today"; exit 1; }
tap "Project" && shot undo-open 1.5 || { log "WALKTHROUGH: the Add sheet has no Project chip"; exit 1; }
tap "New project" || { log "WALKTHROUGH: + New project is not in the picker"; exit 1; }
sleep 1; $T adb shell "input text 'CI%sPicker%s2'"; $T adb shell input keyevent 66
sleep 0.5
undo_toast || { log "WALKTHROUGH: no Undo after the new project (no toast, no Add sheet)"; exit 1; }
shot undo-done 1.5
grep -qE 'text="(CI Picker 2|ci-picker-2)"' "$(xml_of undo-done)" && { log "WALKTHROUGH: after Undo the Project chip still names CI Picker 2"; exit 1; }
sleep 6
if grep -qE 'POST /api/v1/projects \{"name": ?"ci-picker-2"' "$FAKE_LOG"; then
  wait_picker 'PATCH /api/v1/projects/ci-picker-2 \{"deleted": ?true' || { log "WALKTHROUGH: CI Picker 2 reached pagel.uz and its Undo sent no delete"; grep ci-picker-2 "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
  log "   Undo reached pagel.uz: PATCH ci-picker-2 {deleted:true}"
else
  log "   Undo took the create back before it left: pagel.uz never heard of ci-picker-2"
fi

log "-- drag in the picker: Project -> long-press Me and drop it above Humopedia; pagel.uz gets one sort for Me"
S0=$(grep -cE 'PATCH /api/v1/projects/[a-z0-9-]+ \{"sort"' "$FAKE_LOG")
tap "Project" && shot picker-open2 2 || { log "WALKTHROUGH: the Add sheet has no Project chip"; exit 1; }
$T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
PX=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n')
bounds_of() { echo "$PX" | grep -F "text=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '; }
ME=$(bounds_of Me); HU=$(bounds_of Humopedia)
[ -n "$ME" ] && [ -n "$HU" ] || { log "WALKTHROUGH: the picker does not list Me and Humopedia"; exit 1; }
set -- $ME; MX=$(( ($1 + $3) / 2 )); MY=$(( ($2 + $4) / 2 ))
set -- $HU; HY=$(( $2 + 8 ))
$T adb shell input draganddrop $MX $MY $MX $HY 1500
shot picker-dragged 1.5
wait_picker 'PATCH /api/v1/projects/me \{"sort": ?-?[0-9]+\}' || { log "WALKTHROUGH: the drag never reached pagel.uz as a sort for Me"; grep "PATCH /api/v1/projects" "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
[ "$(grep -cE 'PATCH /api/v1/projects/[a-z0-9-]+ \{"sort"' "$FAKE_LOG")" -eq $(( S0 + 1 )) ] || { log "WALKTHROUGH: one drag sent more than one sort"; grep 'PATCH /api/v1/projects' "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   one drag = one PATCH of one row"
log "-- a drop into the middle (checker #7): Labs between Me and Humopedia; still one PATCH, for Labs only"
$T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
PX=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n')
LA=$(bounds_of Labs); HU=$(bounds_of Humopedia)
[ -n "$LA" ] && [ -n "$HU" ] || { log "WALKTHROUGH: the picker does not list Labs and Humopedia"; exit 1; }
set -- $LA; LX=$(( ($1 + $3) / 2 )); LY=$(( ($2 + $4) / 2 ))
set -- $HU; HY=$(( $2 + 8 ))
$T adb shell input draganddrop $LX $LY $LX $HY 1500
shot picker-dragged-middle 1.5
wait_picker 'PATCH /api/v1/projects/labs \{"sort": ?-?[0-9]+\}' || { log "WALKTHROUGH: the middle drop never reached pagel.uz as a sort for Labs"; grep "PATCH /api/v1/projects" "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
sleep 2
[ "$(grep -cE 'PATCH /api/v1/projects/[a-z0-9-]+ \{"sort"' "$FAKE_LOG")" -eq $(( S0 + 2 )) ] || { log "WALKTHROUGH: the middle drop renumbered other rows"; grep 'PATCH /api/v1/projects' "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   middle drop: $(grep -oE 'PATCH /api/v1/projects/labs \{"sort": ?-?[0-9]+\}' "$FAKE_LOG" | tail -1), nothing else"

log "-- edit from the picker (checker #4): ⋯ on Humopedia -> colour amber -> Done; the picker row shows amber, pagel.uz gets one PATCH"
tap "Edit Humopedia" && shot edit-settings 2 || { log "WALKTHROUGH: the picker row has no ⋯ (Edit Humopedia)"; exit 1; }
for k in 1 2 3 4; do tap "Colour amber" && break; $T adb shell input swipe 160 460 160 260 300; sleep 1; done
wait_picker 'PATCH /api/v1/projects/humopedia \{"color": ?"amber"\}' || { log "WALKTHROUGH: the colour from the picker's ⋯ never reached pagel.uz"; grep 'PATCH /api/v1/projects/humopedia' "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
[ "$(grep -cE 'PATCH /api/v1/projects/humopedia \{"color"' "$FAKE_LOG")" -eq 1 ] || { log "WALKTHROUGH: one colour pick sent more than one PATCH"; exit 1; }
tap Done || $T adb shell input keyevent 4
shot edit-back 2
grep -q 'content-desc="Humopedia, amber"' "$(xml_of edit-back)" || { log "WALKTHROUGH: back in the picker, the Humopedia row does not show amber"; exit 1; }
log "   ⋯ edit: one PATCH {color:amber}, the picker row shows it"
pp_on() { $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | grep -qE "content-desc=\"($1)\""; }
for k in 1 2 3 4; do pp_on 'Add task|Find a project|Done' || break; $T adb shell input keyevent 4; sleep 1; done
front picker-closed
tap Today; sleep 1

log "-- the Inbox (Phase 2): sort 5 saves with no network, come back online, pagel.uz gets all 5 in the order made"
tap Inbox && shot inbox 3
grep -q 'text="10 AI tools lists are mostly ads"' "$OUT/$(printf '%02d' $N)-inbox.xml" || { log "WALKTHROUGH: the Inbox shows no card from pagel.uz"; exit 1; }
# Offline for real: the app's only road to the fake pagel.uz is adb reverse, so removing it refuses every call.
$T adb reverse --remove tcp:8799
log "   offline (adb reverse removed)"
BEFORE=$(grep -c "POST /api/v1/inbox" "$FAKE_LOG")
# Is the node labelled $2 selected in dump $1? Compose's selectable puts "selected" on the outer node and the label on
# its first child with the same bounds, never both on one line (smoke 40999222896, dump 51), so accept either shape.
pill_selected() {
  awk -v want="content-desc=\"$2\"" 'BEGIN{RS=">"} {
    b=""; if (match($0, /bounds="[^"]*"/)) b=substr($0, RSTART, RLENGTH)
    if (index($0, want) && (index($0, "selected=\"true\"") || (prev && b == pb))) { found=1; exit }
    prev = index($0, "selected=\"true\"") > 0; pb = b
  } END { exit !found }' "$1"
}
# The centre of the first node whose text is $1, as "x y".
centre() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || { log "   (no \"$1\" on screen)"; return 1; }
  set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
# The dock sits fully above the glass bar (0.8.1 drew the round choices behind it): every choice ends above the bar's
# top, read as the top of the "Today" tab.
XML="$OUT/$(printf '%02d' $N)-inbox.xml"
y_of() { tr '>' '\n' < "$XML" | grep -E "(text|content-desc)=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | sed -n "$2p"; }
BAR=$(y_of Today 2)
[ -n "$BAR" ] || { log "WALKTHROUGH: no Today tab on the Inbox screen"; exit 1; }
CHECKED=0
for c in Action Note Learn "Do now" Later Drop "AI pick: [A-Za-z ]+"; do
  B=$(y_of "$c" 4); [ -n "$B" ] || continue
  CHECKED=$((CHECKED + 1))
  [ "$B" -le "$BAR" ] || { log "WALKTHROUGH: \"$c\" ends at y=$B, under the tab bar's top y=$BAR"; exit 1; }
done
[ "$CHECKED" -ge 6 ] || { log "WALKTHROUGH: only $CHECKED choices on screen, wanted the pick + 5"; exit 1; }
log "   the dock's $CHECKED choices all end above the bar (top y=$BAR)"
RING=$(grep -oE 'content-desc="[0-9]+ of 20 sorted today"' "$XML" | grep -oE '[0-9]+' | head -1)
[ -n "$RING" ] || { log "WALKTHROUGH: the Inbox ring says nothing"; exit 1; }
# Boss's swipe map (2026-10-28): up shuffles, down drops, left learns, right makes an Action, a hold is Do now. Each
# swipe is 220 dp from the claim's centre over 400 ms, kept inside the 320x640 screen. CI 40654453531: a 150-px swipe in
# 200 ms reached the app (down y=204, up y=354) but its last moves came batched, so the card stopped short of the 110 dp
# line; a lost swipe sorts nothing, so it is tried once more while the same card is still on top.
DPI=$($T adb shell wm density | grep -oE '[0-9]+' | tail -1); DPI=${DPI:-160}
D=$(( 220 * DPI / 160 ))
SIZE=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
clamp() { local v=$1; [ "$v" -lt 8 ] && v=8; [ "$v" -gt $(( $2 - 8 )) ] && v=$(( $2 - 8 )); echo "$v"; }
swipe_once() { # $1 claim, $2 direction
  local P; P=$(centre "$1") || return 1; set -- $P "$2"
  local x2=$1 y2=$2
  case $3 in up) y2=$(( $2 - D ));; down) y2=$(( $2 + D ));; left) x2=$(( $1 - D ));; right) x2=$(( $1 + D ));; esac
  $T adb shell input swipe "$1" "$2" "$(clamp $x2 $W)" "$(clamp $y2 $H)" 400
}
# Whether the screen shows $1 now (a text or content-desc equal to it).
onscreen() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | grep -qE "(text|content-desc)=\"$1\""
}
# Swipe a card and wait up to 8 s for what shows once it worked. Root cause of the "ring 12 -> 13" and "swipe right was
# lost" flakes (CI 40748995035, 40760037893): a fixed 1.5 s look came before the software-GPU emulator drew the result,
# and the blind second swipe then sorted a SECOND card (ring 12 -> 14) or swiped the open action form away. Now a second
# swipe happens only when the result never showed AND the same card is still on screen.
swipe_card() { # $1 claim, $2 direction, $3 what shows once it worked (text or content-desc)
  swipe_once "$1" "$2" || exit 1
  for i in $(seq 1 16); do sleep 0.5; onscreen "$3" && return 0; done
  if onscreen "$1"; then
    log "   the swipe $2 on \"$1\" was lost (the card is still there after 8 s); once more"
    swipe_once "$1" "$2" || return 0
    for i in $(seq 1 16); do sleep 0.5; onscreen "$3" && return 0; done
  fi
  log "   (no \"$3\" after the swipe $2 on \"$1\")"
}
swipe_card "10 AI tools lists are mostly ads" down "$((RING + 1)) of 20 sorted today"; shot inbox-swiped-down-drop 1.5
grep -q "content-desc=\"$((RING + 1)) of 20 sorted today\"" "$OUT/$(printf '%02d' $N)-inbox-swiped-down-drop.xml" \
  || { log "WALKTHROUGH: the ring did not go $RING -> $((RING + 1)) after one sort"; grep -oE 'content-desc="[0-9]+ of 20 sorted today"' "$OUT/$(printf '%02d' $N)-inbox-swiped-down-drop.xml" | tee -a "$OUT/steps.txt"; exit 1; }
log "   the ring went $RING -> $((RING + 1))"
# Later stays a button in the dock (up is the shuffle now).
tap Later && shot inbox-later-button 1.5
swipe_card "A meme about Mondays" down "The IT Park grant round closes on 15 November"; shot inbox-swiped-down-ai-drop 1.5
swipe_card "The IT Park grant round closes on 15 November" right "Add to plan"; shot inbox-swiped-right-action-form 2
tap "Add to plan" && shot inbox-action-sent 1.5
tap "AI pick: Note" && shot inbox-note-form 2
tap "Save note" && shot inbox-note-sent 1.5
# Up = shuffle: ib6 goes back into the deck (two or more cards on), ib7 is on top, and nothing is sent.
swipe_card "Spaced repetition apps keep 90 % after a month" up "Buy a second battery for the A05s"; shot inbox-swiped-up-shuffle 1.5
XML="$OUT/$(printf '%02d' $N)-inbox-swiped-up-shuffle.xml"
grep -q 'text="Buy a second battery for the A05s"' "$XML" || { log "WALKTHROUGH: after the shuffle ib7 is not on top"; exit 1; }
grep -q 'text="Spaced repetition apps keep 90 % after a month"' "$XML" && { log "WALKTHROUGH: the shuffled card is still on top"; exit 1; }
# Hold = Do now: a still finger for ~0.9 s fills the ring and opens Do now's sheet; Back closes it, nothing is sent.
P=$(centre "Buy a second battery for the A05s") || exit 1; set -- $P
$T adb shell input swipe "$1" "$2" "$1" "$2" 900; shot inbox-held-do-now 1.5
grep -qE '(text|content-desc)="Done"' "$OUT/$(printf '%02d' $N)-inbox-held-do-now.xml" || { log "WALKTHROUGH: holding the card did not open Do now"; exit 1; }
$T adb shell input keyevent 4; shot inbox-do-now-closed 1.5
# Release → next card on screen, as the app measured it on the frame that drew it (Boss: "slow to show the next one").
MS=$($T adb logcat -d -s PagelInbox:I | grep -oE 'next card in [0-9]+ ms' | grep -oE '[0-9]+' | tr '
' ' ')
log "   release -> next card on screen (ms, emulator with a software GPU): ${MS:-none logged}"
sleep 3
[ "$(grep -c "POST /api/v1/inbox" "$FAKE_LOG")" = "$BEFORE" ] || { log "WALKTHROUGH: a sort reached the fake pagel.uz while offline"; exit 1; }
log "   5 sorted offline, none sent yet"
$T adb reverse tcp:8799 tcp:8799
log "   online again"
WANT="drop:ib1 later:ib2 drop:ib3 act:ib4 note:ib5"
GOT=""
for i in $(seq 1 120); do
  GOT=$(grep -oE 'POST /api/v1/inbox \{"action":"[a-z]+","file":"inbox/ib[0-9]+' "$FAKE_LOG" | sed -E 's/.*"action":"([a-z]+)","file":"inbox\/(ib[0-9]+)/\1:\2/' | uniq | tr '\n' ' ' | sed 's/ $//')
  [ "$GOT" = "$WANT" ] && break
  sleep 1
done
[ "$GOT" = "$WANT" ] || { log "WALKTHROUGH: pagel.uz got the sorts as \"$GOT\", wanted \"$WANT\""; grep "/api/v1/inbox" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got all 5 in order after ~${i} s: $GOT"
grep -F 'POST /api/v1/inbox {"action":"act","file":"inbox/ib4.md","sentence":"Open the grant form and fill page one","project":"humopedia","someday":false}' "$FAKE_LOG" > /dev/null \
  || { log "WALKTHROUGH: the Action did not carry its step and project"; grep "POST /api/v1/inbox" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
for i in $(seq 1 60); do grep -q 'POST /api/v1/inbox {"action":"triage","files":\[[^]]*"inbox/ib8.md"' "$FAKE_LOG" && break; sleep 1; done
grep -q 'POST /api/v1/inbox {"action":"triage","files":\[[^]]*"inbox/ib8.md"' "$FAKE_LOG" \
  || { log "WALKTHROUGH: the unread save ib8 never asked pagel.uz's AI to read it"; grep "/api/v1/inbox" "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   the AI was asked to read ib8 after ~${i} s online"
shot inbox-online 2
log "-- Inbox: where did it go (round D item 2): Sorted today lists the day's choices by where they went; More -> Notes reads a project's notes"
$T adb shell input swipe 160 200 160 600 400; sleep 1
tap 'Sorted today \([0-9]+\)' && shot sorted-today 2
# Match the word, not the emoji: uiautomator writes 📝 (outside the basic plane) as &#128221; but ✅ as itself.
grep -qE 'text="[^"]*Notes · [0-9]+"' "$OUT/$(printf '%02d' $N)-sorted-today.xml" || { log "WALKTHROUGH: Sorted today does not list the Note"; exit 1; }
grep -qE 'text="[^"]*Tasks · [0-9]+"' "$OUT/$(printf '%02d' $N)-sorted-today.xml" || { log "WALKTHROUGH: Sorted today does not list the Action"; exit 1; }
$T adb shell input keyevent 4; sleep 1
tap More && sleep 1
# The More list may still be scrolled from the Projects step: back to its top first.
$T adb shell input swipe 160 200 160 600 400; sleep 1
for k in 1 2 3; do onscreen Notes && break; $T adb shell input swipe 160 500 160 300 400; sleep 1; done
tap Notes && shot notes-projects 3
tap '[^"]*notes, 1' || { log "WALKTHROUGH: More -> Notes lists no project with a note"; exit 1; }
shot notes-ababil 3
grep -q 'RunCam Phoenix 2' "$OUT/$(printf '%02d' $N)-notes-ababil.xml" || { log "WALKTHROUGH: the project's notes did not come from pagel.uz"; exit 1; }
grep -q 'GET /api/v1/notes' "$FAKE_LOG" || { log "WALKTHROUGH: the phone never asked pagel.uz for notes"; exit 1; }
log "   Sorted today and the notes list read right"
$T adb shell input keyevent 4; sleep 1; $T adb shell input keyevent 4; sleep 1

log "-- Habits 2.0 (round D): the chain strip, Morning/Afternoon/Evening, bold cards; a tick reaches pagel.uz; a reminder; Namoz"
tap Habits && shot habits 2.5
tap Morning && shot habits-morning 1.5
for want in "Stretch for ten minutes" "Drink water"; do
  grep -q "text=\"$want\"" "$OUT/$(printf '%02d' $N)-habits-morning.xml" || { log "WALKTHROUGH: Habits' Morning has no \"$want\""; exit 1; }
done
grep -q 'content-desc="Namoz"\|text="Namoz"' "$OUT/$(printf '%02d' $N)-habits-morning.xml" || log "   (WARN: no Namoz group on screen)"
tap Evening && shot habits-evening 1.5
grep -q 'text="Read 10 pages"' "$OUT/$(printf '%02d' $N)-habits-evening.xml" || { log "WALKTHROUGH: Habits' Evening has no \"Read 10 pages\""; exit 1; }
log "-- the parts are pages (Boss's note 5): a swipe right on Evening's habits brings Afternoon, its pill selected"
# Checker 3 #13: shots 49 -> 50 showed the page 27 px higher. That was this walk's own scroll (a vertical swipe here
# before the sideways one, and 27 px is all the page could scroll), so it is gone: the row is on screen already. The
# sideways swipe must leave the page where it was: the + button's top is read before and after.
plus_top() { tr '>' '\n' < "$1" | grep -F 'content-desc="New habit"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | sed -n 2p; }
TOP_BEFORE=$(plus_top "$OUT/$(printf '%02d' $N)-habits-evening.xml")
XY=$(centre "Read 10 pages") || { log "WALKTHROUGH: Evening's row went missing before the swipe"; exit 1; }
set -- $XY; $T adb shell input swipe 50 "$2" 290 "$2" 300
shot habits-swiped 1.5
TOP_AFTER=$(plus_top "$OUT/$(printf '%02d' $N)-habits-swiped.xml")
log "   the header's top before / after the sideways swipe: ${TOP_BEFORE:-?} / ${TOP_AFTER:-?}"
[ -n "$TOP_BEFORE" ] && [ "$TOP_BEFORE" = "$TOP_AFTER" ] || { log "WALKTHROUGH: the sideways swipe moved the page (header top ${TOP_BEFORE:-none} -> ${TOP_AFTER:-none})"; exit 1; }
$T adb shell input swipe 160 200 160 600 400; sleep 1
shot habits-swiped-top 1
pill_selected "$OUT/$(printf '%02d' $N)-habits-swiped-top.xml" Afternoon || { log "WALKTHROUGH: a swipe on the habits did not bring Afternoon"; exit 1; }
log "-- a new habit in two taps (note 8): + → name → Add reaches pagel.uz; the toast's Undo takes it back"
REMOVES=$(grep -c '"remove":true' "$FAKE_LOG")
tap "New habit" && shot new-habit-sheet 1.5 || { log "WALKTHROUGH: Habits has no + New habit"; exit 1; }
$T adb shell "input text 'Floss%sat%snight'"; sleep 0.5
tap "Add habit" || { log "WALKTHROUGH: the New habit sheet has no Add habit"; exit 1; }
for i in $(seq 1 8); do grep -q 'POST /api/v1/tasks .*Floss at night' "$FAKE_LOG" && break; sleep 0.5; done
grep -q 'POST /api/v1/tasks .*Floss at night' "$FAKE_LOG" || { log "WALKTHROUGH: the new habit never reached pagel.uz"; grep "POST /api/v1/tasks" "$FAKE_LOG" | tail -3 | tee -a "$OUT/steps.txt"; exit 1; }
tap Undo && shot new-habit-undone 1.5 || { log "WALKTHROUGH: the Habit added toast had no Undo"; exit 1; }
for i in $(seq 1 30); do [ "$(grep -c '"remove":true' "$FAKE_LOG")" -gt "$REMOVES" ] && break; sleep 1; done
[ "$(grep -c '"remove":true' "$FAKE_LOG")" -gt "$REMOVES" ] || { log "WALKTHROUGH: Undo of the new habit never reached pagel.uz"; exit 1; }
log "   new habit added and taken back"
tap Morning && sleep 1
# The cards sit under the strip, the pills and the Namoz group: scroll the list up first.
$T adb shell input swipe 160 460 160 260 400; sleep 1
tap "Done: Drink water" && shot habit-ticked 0.4
shot habit-finished 2.5
grep -q 'text="Finished"' "$OUT/$(printf '%02d' $N)-habit-finished.xml" || { log "WALKTHROUGH: the ticked habit's card did not go quiet with Finished"; exit 1; }
TODAY=$(TZ=Asia/Tashkent date +%Y-%m-%d)
H3="PATCH /api/v1/tasks/t_h3 {\"done\":true,\"day\":\"$TODAY\",\"if_due\":\"$TODAY\",\"n\":1} auth=True"
for i in $(seq 1 30); do grep -qF "$H3" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$H3" "$FAKE_LOG" || { log "WALKTHROUGH: the habit tick never reached pagel.uz as $H3"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   habit tick reached pagel.uz after ~${i} s"
log "-- a timed habit: Stretch's play ring runs its 10-minute clock (the row itself opens the habit's page)"
tap "Start: Stretch for ten minutes" && shot habit-timer 3
grep -q 'text="0:0[1-9] / 10 min"\|text="0:1[0-9] / 10 min"' "$OUT/$(printf '%02d' $N)-habit-timer.xml" || log "   (WARN: the timer shows no running seconds)"
grep -q 'content-desc="Reminder: \|content-desc="More: ' "$OUT/$(printf '%02d' $N)-habit-timer.xml" && { log "WALKTHROUGH: a habit row still has a bell or a menu (Boss 2026-10-29: minimal rows)"; exit 1; }
tap "Pause: Stretch for ten minutes" && sleep 1
log "-- a 3x habit (round B): Log food tapped 3 times in Afternoon; pagel.uz gets 3 ticks"
$T adb shell input swipe 160 200 160 600 400; sleep 1
tap Afternoon && sleep 1
$T adb shell input swipe 160 460 160 260 400; sleep 1
for k in 1 2 3; do tap "Done: Log food" && shot "habit-3x-$k" 1.3 || { log "WALKTHROUGH: no \"Done: Log food\" check for tap $k"; exit 1; }; done
grep -q 'text="2 / 3"' "$OUT/$(printf '%02d' $((N - 1)))-habit-3x-2.xml" || log "   (WARN: the 2nd tap's shot shows no 2 / 3)"
for i in $(seq 1 30); do [ "$(grep -c 'PATCH /api/v1/tasks/t_h4 {"done":true' "$FAKE_LOG")" -ge 3 ] && break; sleep 1; done
[ "$(grep -c 'PATCH /api/v1/tasks/t_h4 {"done":true' "$FAKE_LOG")" -ge 3 ] || { log "WALKTHROUGH: the 3x habit's taps did not all reach pagel.uz"; grep "t_h4" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   3 taps reached pagel.uz after ~${i} s"
log "-- Namoz (Boss 2026-10-28): Start tracking adds the five prayers as daily habits in the group namoz, from 1 November"
$T adb shell input swipe 160 200 160 600 400; sleep 1
tap "Start tracking" && shot namoz-started 3
for i in $(seq 1 30); do [ "$(grep -c 'POST /api/v1/tasks .*"group":"namoz"' "$FAKE_LOG")" -ge 5 ] && break; sleep 1; done
[ "$(grep -c 'POST /api/v1/tasks .*"group":"namoz"' "$FAKE_LOG")" -ge 5 ] || { log "WALKTHROUGH: the five prayers did not reach pagel.uz with group namoz"; grep "POST /api/v1/tasks" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   5 prayers reached pagel.uz after ~${i} s"
shot namoz-group 2
grep -q 'text="Bomdod"' "$OUT/$(printf '%02d' $N)-namoz-group.xml" || log "   (WARN: the Namoz group shows no Bomdod row yet)"
tap History && shot habits-history 2
$T adb shell input keyevent 4; sleep 1
tap Morning && sleep 1
# The Namoz group (5 rows now) pushes Stretch's row down: scroll until it is on screen, then open its page, where the
# reminder lives now (Boss 2026-10-29: no bell on the row).
# The row must sit above the glass tab bar (CI 40845716723: found under the bar, the tap hit Notebook): scroll until
# its bottom edge is above y=480.
for k in 1 2 3 4 5; do
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  SB=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -F 'text="Stretch for ten minutes"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | sed -n 4p)
  [ -n "$SB" ] && [ "$SB" -lt 480 ] && break
  $T adb shell input swipe 160 460 160 300 400; sleep 1
done
tap "Stretch for ten minutes" && shot habit-page 2.5
# The page is mainly stats (Boss 2026-10-29); every setting sits behind ⋮.
grep -q 'content-desc="Habit settings"' "$OUT/$(printf '%02d' $N)-habit-page.xml" || { log "WALKTHROUGH: the habit's page has no ⋮ settings"; exit 1; }
grep -q 'text="Reminder · off"' "$OUT/$(printf '%02d' $N)-habit-page.xml" && { log "WALKTHROUGH: the reminder card is still on the page body"; exit 1; }
# Exact alarms are a special grant Boss gives once in Settings; the emulator is given it here so the sheet stays open.
$T adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow
ALARMS_BEFORE=$($T adb shell dumpsys alarm | grep -c "org.pagel.ai.HABIT_FIRE")
tap "Habit settings" && shot reminder-sheet 2.5
# The Reminder block sits under Look and Schedule in the sheet: scroll until its switch is on screen.
for k in 1 2 3 4 5; do onscreen "Remind me" && break; $T adb shell input swipe 160 500 160 220 400; sleep 1; done
tap "Remind me" && shot reminder-on 1.5
front reminder-on
# The sheet reads the grant when it opens (checker #7): the walk granted it, so no "Exact time · Allow" row.
grep -q 'content-desc="Exact time · Allow"' "$OUT/$(printf '%02d' $N)-reminder-on.xml" && { log "WALKTHROUGH: the sheet still says exact alarms are off after the grant"; exit 1; }
$T adb shell input keyevent 4; shot reminder-closed 1.5
# Proof the reminder is on AlarmManager (checker #7: the old grep for the data URI read 0 and was never asserted).
sleep 2
$T adb shell dumpsys alarm > "$OUT/alarm-dump.txt"
ALARMS_AFTER=$(grep -c "org.pagel.ai.HABIT_FIRE" "$OUT/alarm-dump.txt")
echo "$ALARMS_BEFORE -> $ALARMS_AFTER" > "$OUT/alarm-count.txt"
log "   PAGEL habit alarms on AlarmManager: $ALARMS_BEFORE -> $ALARMS_AFTER"
grep -B2 -A6 "org.pagel.ai.HABIT_FIRE" "$OUT/alarm-dump.txt" | head -20 >> "$OUT/steps.txt"
[ "$ALARMS_AFTER" -gt "$ALARMS_BEFORE" ] || { log "WALKTHROUGH: turning the reminder on set no alarm"; exit 1; }
# The alarm rings now: root adb sends the receiver the same intent PAGEL's own alarm carries.
$T adb shell am broadcast -n "$PKG/.reminders.HabitAlarmReceiver" -a org.pagel.ai.HABIT_FIRE -d pagel://habit/t_h1 --es habit t_h1 > /dev/null
sleep 2
$T adb shell cmd statusbar expand-notifications
shot notification 2.5
grep -q 'Stretch for ten minutes' "$OUT/$(printf '%02d' $N)-notification.xml" || { log "WALKTHROUGH: no reminder notification for Stretch"; exit 1; }
if tap "Done" || tap "DONE"; then log "   tapped the notification's Done"; else
  log "   (Done not found in the shade; sending the Done button's own intent)"
  $T adb shell am broadcast -n "$PKG/.reminders.HabitAlarmReceiver" -a org.pagel.ai.HABIT_DONE -d pagel://habit-done/t_h1 --es habit t_h1 > /dev/null
fi
sleep 1
$T adb shell cmd statusbar collapse
H1="PATCH /api/v1/tasks/t_h1 {\"done\":true,\"day\":\"$TODAY\""
for i in $(seq 1 60); do grep -qF "$H1" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$H1" "$FAKE_LOG" || { log "WALKTHROUGH: the notification's Done never reached pagel.uz"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   the notification's Done reached pagel.uz after ~${i} s"
front habits-after-done
shot habits-after-notification 2
# Leave the habit's page for the tabs.
$T adb shell input keyevent 4; sleep 1

log "-- Upcoming + Completed (Phase 4): More → Upcoming; drag a task onto the day after tomorrow; Move all to today; untick"
# The centre of the first node whose content-desc starts with $1 (the strip's days say "Friday 30 October, 2 tasks").
centre_desc() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -F "content-desc=\"$1" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || { log "   (no content-desc \"$1…\" on screen)"; return 1; }
  set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
tap More && shot more-phase4 1.5
tap Upcoming && shot upcoming 2.5
for want in 'text="Carried over"' 'text="Send the grant form"' 'text="Pay the internet bill"' 'content-desc="Move to today"'; do
  grep -qF "$want" "$OUT/$(printf '%02d' $N)-upcoming.xml" || { log "WALKTHROUGH: Upcoming has no $want"; exit 1; }
done
TOMORROW_SPOKEN=$(LC_ALL=C TZ=Asia/Tashkent date -d '+1 day' '+%A %-d %B')
AFTER2=$(TZ=Asia/Tashkent date -d '+2 days' +%Y-%m-%d)
AFTER2_SPOKEN=$(LC_ALL=C TZ=Asia/Tashkent date -d '+2 days' '+%A %-d %B')
# Tapping a day of the strip brings its section up (the tomorrow pane may be below the fold on a 640 dp screen).
P=$(centre_desc "$TOMORROW_SPOKEN") || exit 1; set -- $P
$T adb shell input tap "$1" "$2"; shot upcoming-tomorrow 2
P=$(centre "Review the lesson draft") || exit 1; set -- $P; FX=$1; FY=$2
P=$(centre_desc "$AFTER2_SPOKEN") || exit 1; set -- $P; TX=$1; TY=$2
log "   drag \"Review the lesson draft\" ($FX,$FY) onto $AFTER2_SPOKEN ($TX,$TY)"
# Long-press (held 1.2 s), then the finger travels to the strip's day in steps, then lets go.
$T adb shell input motionevent DOWN "$FX" "$FY"
sleep 1.2
for k in 1 2 3 4 5 6; do
  $T adb shell input motionevent MOVE $(( FX + (TX - FX) * k / 6 )) $(( FY + (TY - FY) * k / 6 ))
done
$T adb shell screencap -p /sdcard/drag.png; timeout 20 adb pull /sdcard/drag.png "$OUT/$(printf '%02d' $((N + 1)))-upcoming-dragging.png" > /dev/null 2>&1; N=$((N + 1)); log "$(printf '%02d' $N)-upcoming-dragging"
$T adb shell input motionevent UP "$TX" "$TY"
shot upcoming-dropped 1.5
WANT="PATCH /api/v1/tasks/t_u3 {\"due\":\"$AFTER2\"} auth=True"
for i in $(seq 1 30); do grep -qF "$WANT" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$WANT" "$FAKE_LOG" || { log "WALKTHROUGH: the drop never reached pagel.uz as $WANT"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got the drop: {\"due\":\"$AFTER2\"} after ~${i} s"
# Today on the strip brings the list back to the top, where Overdue is.
TODAY_SPOKEN=$(LC_ALL=C TZ=Asia/Tashkent date '+%A %-d %B')
P=$(centre_desc "$TODAY_SPOKEN") || exit 1; set -- $P
$T adb shell input tap "$1" "$2"; sleep 1.5
tap "Move to today" && shot upcoming-moved-all 1.5
for id in t_u2 t_u1; do
  WANT="PATCH /api/v1/tasks/$id {\"due\":\"$TODAY\"} auth=True"
  for i in $(seq 1 30); do grep -qF "$WANT" "$FAKE_LOG" && break; sleep 1; done
  grep -qF "$WANT" "$FAKE_LOG" || { log "WALKTHROUGH: Move all to today never reached pagel.uz as $WANT"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
done
log "   pagel.uz got both overdue tasks moved to $TODAY"
grep -q 'text="Carried over"' "$OUT/$(printf '%02d' $N)-upcoming-moved-all.xml" && { log "WALKTHROUGH: Overdue still shows after Move all to today"; exit 1; }
log "-- Round C calendar: swipe the strip to next week, Today comes back, a day tapped, Day / Week / Month / List"
P=$(centre_desc "$TOMORROW_SPOKEN") || exit 1; set -- $P
# The emulator's own width (a 320 dp screen here): from the strip's right edge to its left, in 400 ms.
W=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)
# Never from the very edge: gesture navigation takes an edge swipe as Back (CI 40737393541 started at W - 12).
$T adb shell input swipe "$(( W * 85 / 100 ))" "$2" "$(( W * 12 / 100 ))" "$2" 350; shot calendar-next-week 1.5
NEXTWEEK_SPOKEN=$(LC_ALL=C TZ=Asia/Tashkent date -d '+7 days' '+%A %-d %B')
grep -qF "content-desc=\"$NEXTWEEK_SPOKEN" "$OUT/$(printf '%02d' $N)-calendar-next-week.xml" || { log "WALKTHROUGH: the strip did not page to next week ($NEXTWEEK_SPOKEN)"; exit 1; }
tap "Back to today" && shot calendar-back-to-today 1.5
grep -qF "content-desc=\"$TODAY_SPOKEN" "$OUT/$(printf '%02d' $N)-calendar-back-to-today.xml" || { log "WALKTHROUGH: Today did not bring this week back"; exit 1; }
P=$(centre_desc "$TOMORROW_SPOKEN") || exit 1; set -- $P
$T adb shell input tap "$1" "$2"; sleep 1
# Core round 4: the view is a small button that opens a glass menu (the five-way switch took a third of the top).
cal_xml() { echo "$OUT/$(printf '%02d' $N)-$1.xml"; }
DPI=$($T adb shell wm density | grep -oE '[0-9]+' | tail -1); DPI=${DPI:-160}
dp() { echo $(( $1 * DPI / 160 )); }
# The bounds of the first node whose content-desc is exactly $1, as "x1 y1 x2 y2" (nothing when it is not there).
cal_bounds() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -F "content-desc=\"$1\"" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '
}
# Change view → the menu's row; if the popup is not in the dump, its row by place (List 0, Day 1, 3 days 2, Week 3, Month 4).
pick_view() {
  local b; b=$(cal_bounds "Change view"); set -- "$1" "$2" $b
  [ -n "${6:-}" ] || { log "   (no Change view on screen)"; return 1; }
  $T adb shell input tap $(( ($3 + $5) / 2 )) $(( ($4 + $6) / 2 )); sleep 1
  tap "$1" && return 0
  log "   (the menu's \"$1\" is not in the dump: its row by place)"
  $T adb shell input tap $(( $3 + $(dp 60) )) $(( $6 + $(dp 36) + $(dp 48) * $2 ))
}
# A finger held still before it lifts: the grid stops where it was put (no fling).
cal_drag() { # x1 y1 x2 y2
  $T adb shell input motionevent DOWN "$1" "$2"
  for k in 1 2 3 4 5 6 7 8; do $T adb shell input motionevent MOVE $(( $1 + ($3 - $1) * k / 8 )) $(( $2 + ($4 - $2) * k / 8 )); done
  sleep 0.5; $T adb shell input motionevent MOVE "$3" "$4"; $T adb shell input motionevent UP "$3" "$4"
}
pick_view Day 1 && shot calendar-day 2 || { log "WALKTHROUGH: no Day in the view menu"; exit 1; }
pick_view Week 3 && shot calendar-week 2 || { log "WALKTHROUGH: no Week in the view menu"; exit 1; }
pick_view Month 4 && shot calendar-month 2 || { log "WALKTHROUGH: no Month in the view menu"; exit 1; }
grep -q 'content-desc="Previous month"' "$(cal_xml calendar-month)" || { log "WALKTHROUGH: the Month view has no month arrows"; exit 1; }

log "-- Core round 4 calendar (Builder B): Week = Monday to Sunday; swipe to next week; drag its Sunday 10:00 block to Thursday 14:30; Undo sends 10:00 back"
TODAY_ISO=$(TZ=Asia/Tashkent date +%F)
MON=$(date -d "$TODAY_ISO -$(( $(TZ=Asia/Tashkent date +%u) - 1 )) days" +%F)
MON_SPOKEN=$(LC_ALL=C date -d "$MON" '+%A %-d %B'); SUN_SPOKEN=$(LC_ALL=C date -d "$MON +6 days" '+%A %-d %B')
NEXT_THU=$(date -d "$MON +10 days" +%F); NEXT_THU_SPOKEN=$(LC_ALL=C date -d "$NEXT_THU" '+%A %-d %B')
NEXT_SUN=$(date -d "$MON +13 days" +%F); NEXT_MON_SPOKEN=$(LC_ALL=C date -d "$MON +7 days" '+%A %-d %B')
pick_view Week 3 || { log "WALKTHROUGH: no Week in the view menu"; exit 1; }
shot cal4-week 2
grep -qF "content-desc=\"$MON_SPOKEN\"" "$(cal_xml cal4-week)" && grep -qF "content-desc=\"$SUN_SPOKEN\"" "$(cal_xml cal4-week)" \
  || { log "WALKTHROUGH: the Week is not Monday to Sunday ($MON_SPOKEN to $SUN_SPOKEN)"; exit 1; }
# One header row: no five-way switch in the Week (the dates live once, on the columns).
grep -q 'content-desc="View: ' "$(cal_xml cal4-week)" && { log "WALKTHROUGH: the Week still shows the five-way view switch"; exit 1; }
W=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); H=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
# Swipe the grid sideways (not from an edge: gesture navigation takes that as Back), well under the glass top.
$T adb shell input swipe "$(( W * 85 / 100 ))" "$(( H / 2 ))" "$(( W * 12 / 100 ))" "$(( H / 2 ))" 300
shot cal4-next-week 2
grep -qF "content-desc=\"$NEXT_MON_SPOKEN\"" "$(cal_xml cal4-next-week)" || { log "WALKTHROUGH: the swipe did not turn the Week to $NEXT_MON_SPOKEN"; exit 1; }
# Boss 2026-10-31: "swiping the week back glitches" and "I could not swipe right". Back x2 and forward x2, slow then fast, on
# the grid and on the dates; the week must follow both ways, and Android's frame stats for just these swipes go to steps.txt.
$T adb shell dumpsys gfxinfo "$PKG" reset > /dev/null
SW_L=$(( W * 12 / 100 )); SW_R=$(( W * 85 / 100 )); SW_Y=$(( H / 2 ))
$T adb shell input swipe "$SW_L" "$SW_Y" "$SW_R" "$SW_Y" 900; sleep 1
shot cal-swipe-back-1 2
grep -qF "content-desc=\"$MON_SPOKEN\"" "$(cal_xml cal-swipe-back-1)" || { log "WALKTHROUGH: swiping the Week back (right) did not turn it to $MON_SPOKEN"; exit 1; }
$T adb shell input swipe "$SW_L" "$SW_Y" "$SW_R" "$SW_Y" 150; sleep 1
$T adb shell input swipe "$SW_R" "$SW_Y" "$SW_L" "$SW_Y" 900; sleep 1
$T adb shell input swipe "$SW_R" "$SW_Y" "$SW_L" "$SW_Y" 150; sleep 1
shot cal-swipe-fwd-2 2
grep -qF "content-desc=\"$NEXT_MON_SPOKEN\"" "$(cal_xml cal-swipe-fwd-2)" || { log "WALKTHROUGH: after back x2 and forward x2 the Week is not $NEXT_MON_SPOKEN again"; exit 1; }
$T adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-week-swipe.txt"
log "   week swipe (back x2, forward x2, slow and fast): $(grep -E 'Total frames rendered|Janky frames' "$OUT/gfxinfo-week-swipe.txt" | tr '
' ' ')"
# Next Sunday's 10:00 block: the grid to its top through the hour column (no block there), then 10:00 under the dates.
GX=$(dp 26)
for k in 1 2 3; do $T adb shell input swipe "$GX" "$(( H * 35 / 100 ))" "$GX" "$(( H * 80 / 100 ))" 120; sleep 0.4; done
sleep 1
HEAD=$(cal_bounds "$NEXT_THU_SPOKEN"); set -- $HEAD; [ -n "${4:-}" ] || { log "WALKTHROUGH: no head for $NEXT_THU_SPOKEN"; exit 1; }
THU_X=$(( ($1 + $3) / 2 )); TOPLINE=$(( $4 + $(dp 40) ))
BLOCK=""; B=""
for k in 1 2 3 4 5 6 7 8; do
  B=$(cal_bounds "Plan the lesson week, 10:00"); set -- $B
  if [ -n "${4:-}" ]; then
    D=$(( $2 - TOPLINE - $(dp 15) ))
    if [ "$D" -ge "-$(dp 15)" ] && [ "$D" -le "$(dp 25)" ]; then BLOCK="$B"; break; fi
    [ "$D" -gt "$(dp 200)" ] && D=$(dp 200); [ "$D" -lt "-$(dp 200)" ] && D=-$(dp 200)
    S=$(dp 8); [ "$D" -lt 0 ] && S=$(( -S ))
    cal_drag "$GX" "$(( H * 55 / 100 ))" "$GX" "$(( H * 55 / 100 - D - S ))"
  else cal_drag "$GX" "$(( H * 75 / 100 ))" "$GX" "$(( H * 75 / 100 - $(dp 150) ))"; fi
  sleep 0.8
done
[ -n "$BLOCK" ] || { log "WALKTHROUGH: the Sunday 10:00 block never came under the dates (last bounds: $B, line $TOPLINE)"; shot cal4-no-block 0.5; exit 1; }
set -- $BLOCK; FX=$(( ($1 + $3) / 2 )); FY=$(( ($2 + $4) / 2 ))
# 10:00 → 14:30 is 4.5 hours of 52 dp.
TX=$THU_X; TY=$(( FY + 234 * DPI / 160 ))
log "   drag the block ($FX,$FY) to Thursday 14:30 ($TX,$TY)"
$T adb shell input motionevent DOWN "$FX" "$FY"
sleep 1.2
for k in 1 2 3 4 5 6 7 8; do $T adb shell input motionevent MOVE $(( FX + (TX - FX) * k / 8 )) $(( FY + (TY - FY) * k / 8 )); done
sleep 0.4; $T adb shell input motionevent MOVE "$TX" "$TY"
$T adb shell screencap -p /sdcard/drag.png; timeout 20 adb pull /sdcard/drag.png "$OUT/$(printf '%02d' $((N + 1)))-cal4-dragging.png" > /dev/null 2>&1; N=$((N + 1)); log "$(printf '%02d' $N)-cal4-dragging"
$T adb shell input motionevent UP "$TX" "$TY"
WANT="PATCH /api/v1/tasks/t_cal1 {\"due\":\"$NEXT_THU 14:30\"} auth=True"
for i in $(seq 1 14); do grep -qF "$WANT" "$FAKE_LOG" && break; sleep 0.5; done
grep -qF "$WANT" "$FAKE_LOG" || { log "WALKTHROUGH: the drop never reached pagel.uz as $WANT"; grep 't_cal1' "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; shot cal4-dropped 0.5; exit 1; }
log "   pagel.uz got the drop: due $NEXT_THU 14:30"
# Checker round 2 #3: a calendar move is counted (KPI cal_move), a count with no words.
wait_cal() { for i in $(seq 1 "${2:-30}"); do grep -qF "$1" "$FAKE_LOG" && return 0; sleep 1; done; return 1; }
wait_cal "POST /api/v1/kpi {\"day\":\"$TODAY_ISO\",\"event\":\"cal_move\"}" 30 || { log "WALKTHROUGH: the move was not counted (no cal_move KPI)"; grep 'api/v1/kpi' "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz counted the move: cal_move"
tap Undo || { log "WALKTHROUGH: the move's toast has no Undo"; exit 1; }
BACK="PATCH /api/v1/tasks/t_cal1 {\"due\":\"$NEXT_SUN 10:00\"} auth=True"
for i in $(seq 1 30); do grep -qF "$BACK" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$BACK" "$FAKE_LOG" || { log "WALKTHROUGH: Undo never sent the old due back as $BACK"; grep 't_cal1' "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
shot cal4-undone 2
grep -qF 'content-desc="Plan the lesson week, 10:00"' "$(cal_xml cal4-undone)" || log "   (WARN: the block is not back at 10:00 on screen)"
log "   Undo sent the old due back: $NEXT_SUN 10:00"

log "-- resize: drag the Sunday 10:00 block's bottom edge 30 min down; pagel.uz gets 10:00-11:00"
sleep 8   # the Undo toast leaves first
B=$(cal_bounds "Plan the lesson week, 10:00"); set -- $B
[ -n "${4:-}" ] || { log "WALKTHROUGH: the Sunday 10:00 block is not on screen for the resize"; shot cal4-no-resize 0.5; exit 1; }
# The block's node ends 2 dp above its drawn bottom; the bottom third (8 dp of a half-hour block) is the resize edge.
RX=$(( ($1 + $3) / 2 )); RY=$(( $4 - $(dp 3) )); RTO=$(( RY + 26 * DPI / 160 ))
log "   hold the bottom edge ($RX,$RY), carry it to $RTO"
$T adb shell input motionevent DOWN "$RX" "$RY"
sleep 1.2
for k in 1 2 3 4 5 6; do $T adb shell input motionevent MOVE "$RX" $(( RY + (RTO - RY) * k / 6 )); done
sleep 0.4; $T adb shell input motionevent MOVE "$RX" "$RTO"
$T adb shell screencap -p /sdcard/drag.png; timeout 20 adb pull /sdcard/drag.png "$OUT/$(printf '%02d' $((N + 1)))-cal4-resizing.png" > /dev/null 2>&1; N=$((N + 1)); log "$(printf '%02d' $N)-cal4-resizing"
$T adb shell input motionevent UP "$RX" "$RTO"
WANT="PATCH /api/v1/tasks/t_cal1 {\"due\":\"$NEXT_SUN 10:00-11:00\"} auth=True"
wait_cal "$WANT" 20 || { log "WALKTHROUGH: the resize never reached pagel.uz as $WANT"; grep 't_cal1' "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; shot cal4-resized 0.5; exit 1; }
shot cal4-resized 2
log "   pagel.uz got the new range: $NEXT_SUN 10:00-11:00"

log "-- Month drop: Month -> tap Sunday $NEXT_SUN -> long-press Plan the lesson week -> drop it on Saturday; the time is kept"
sleep 8
pick_view Month 4 || { log "WALKTHROUGH: no Month in the view menu"; exit 1; }
sleep 1
NEXT_SUN_SPOKEN=$(LC_ALL=C date -d "$NEXT_SUN" '+%A %-d %B')
NEXT_SAT=$(date -d "$MON +12 days" +%F); NEXT_SAT_SPOKEN=$(LC_ALL=C date -d "$NEXT_SAT" '+%A %-d %B')
P=$(centre_desc "$NEXT_SUN_SPOKEN") || { log "WALKTHROUGH: Month has no $NEXT_SUN_SPOKEN"; shot cal4-month-nosun 0.5; exit 1; }
set -- $P; $T adb shell input tap "$1" "$2"
shot cal4-month-sunday 1.5
row_of() { tr '>' '\n' < "$(cal_xml "$1")" | grep -F 'text="Plan the lesson week"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '; }
ROW=$(row_of cal4-month-sunday); set -- $ROW
# The day's list sits under the six weeks: on a short screen it is brought up above the tab bar first.
if [ -z "${4:-}" ] || [ "$(( ($2 + $4) / 2 ))" -gt "$(( H - $(dp 120) ))" ]; then
  cal_drag "$(( W / 2 ))" "$(( H * 70 / 100 ))" "$(( W / 2 ))" "$(( H * 70 / 100 - $(dp 160) ))"; sleep 1
  shot cal4-month-list-up 1.5; ROW=$(row_of cal4-month-list-up); set -- $ROW
fi
[ -n "${4:-}" ] || { log "WALKTHROUGH: the Sunday's list has no Plan the lesson week"; exit 1; }
CX=$(( ($1 + $3) / 2 )); CY=$(( ($2 + $4) / 2 ))
# The bounds of the first node whose content-desc starts with $1 (a month cell says ", 1 task" after its date).
desc_bounds() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -F "content-desc=\"$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '
}
log "   hold the row ($CX,$CY): the six weeks scroll back into view, then carry it to $NEXT_SAT_SPOKEN"
$T adb shell input motionevent DOWN "$CX" "$CY"
sleep 1.6
TB=$(desc_bounds "$NEXT_SAT_SPOKEN"); set -- $TB
[ -n "${4:-}" ] || { $T adb shell input motionevent UP "$CX" "$CY"; log "WALKTHROUGH: Month has no $NEXT_SAT_SPOKEN while a row is held"; exit 1; }
SX=$(( ($1 + $3) / 2 )); SY=$(( ($2 + $4) / 2 ))
for k in 1 2 3 4 5 6 7 8; do $T adb shell input motionevent MOVE $(( CX + (SX - CX) * k / 8 )) $(( CY + (SY - CY) * k / 8 )); done
sleep 0.4; $T adb shell input motionevent MOVE "$SX" "$SY"
$T adb shell screencap -p /sdcard/drag.png; timeout 20 adb pull /sdcard/drag.png "$OUT/$(printf '%02d' $((N + 1)))-cal4-month-dragging.png" > /dev/null 2>&1; N=$((N + 1)); log "$(printf '%02d' $N)-cal4-month-dragging"
# Checker round 3: the cell it lands on is below the glass top, in sight (walk 104 had the week under the header).
TB=$(desc_bounds "$NEXT_SAT_SPOKEN"); GB=$(cal_bounds "Next month")
set -- $TB; TTOP=${2:-0}; set -- $GB; GBOT=${4:-0}
log "   target cell top $TTOP, glass top's arrows end at $GBOT"
[ "$TTOP" -gt "$GBOT" ] && [ "$GBOT" -gt 0 ] || { $T adb shell input motionevent UP "$SX" "$SY"; log "WALKTHROUGH: the Month drop target ($NEXT_SAT_SPOKEN, top $TTOP) is under the glass top (ends $GBOT)"; exit 1; }
$T adb shell input motionevent UP "$SX" "$SY"
WANT="PATCH /api/v1/tasks/t_cal1 {\"due\":\"$NEXT_SAT 10:00-11:00\"} auth=True"
wait_cal "$WANT" 20 || { log "WALKTHROUGH: the Month drop never reached pagel.uz as $WANT"; grep 't_cal1' "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; shot cal4-month-dropped 0.5; exit 1; }
shot cal4-month-dropped 2
[ "$(grep -cF '"event":"cal_move"' "$FAKE_LOG")" -ge 3 ] || log "   (WARN: fewer than 3 cal_move counts: $(grep -cF '"event":"cal_move"' "$FAKE_LOG"))"
log "   pagel.uz got the Month drop with its time kept: $NEXT_SAT 10:00-11:00"
$T adb logcat -d > "$OUT/logcat-cal4.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-cal4.txt" && { log "WALKTHROUGH: CRASH in the calendar drag (see logcat-cal4.txt)"; exit 1; }
pick_view List 0 && shot calendar-list 1.5
log "-- Round C notifications: the task, morning-plan alarms are on AlarmManager; a reminder's Done ticks offline"
sleep 3
$T adb shell dumpsys alarm > "$OUT/alarm-dump-tasks.txt"
TASK_ALARMS=$(grep -c "org.pagel.ai.TASK_FIRE" "$OUT/alarm-dump-tasks.txt" || true)
MORNING_ALARMS=$(grep -c "org.pagel.ai.MORNING_FIRE" "$OUT/alarm-dump-tasks.txt" || true)
log "   alarms: TASK_FIRE $TASK_ALARMS, MORNING_FIRE $MORNING_ALARMS"
[ "$MORNING_ALARMS" -ge 1 ] || { log "WALKTHROUGH: no morning-plan alarm"; exit 1; }
[ "$TASK_ALARMS" -ge 1 ] || { log "WALKTHROUGH: no task reminder alarm (the range task should have one)"; grep -i "pagel" "$OUT/alarm-dump-tasks.txt" | head -20 | tee -a "$OUT/steps.txt"; exit 1; }
$T adb shell am broadcast -n "$PKG/.reminders.TaskAlarmReceiver" -a org.pagel.ai.TASK_DONE -d pagel://task-done/t_u1 --es task t_u1 > /dev/null
WANT='PATCH /api/v1/tasks/t_u1 {"done":true} auth=True'
for i in $(seq 1 30); do grep -qF "$WANT" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$WANT" "$FAKE_LOG" || { log "WALKTHROUGH: the reminder's Done never reached pagel.uz as $WANT"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   the reminder's Done reached pagel.uz after ~${i} s"
$T adb shell input keyevent 4; sleep 1
tap "Notifications" && shot notify-settings 2 || { log "WALKTHROUGH: no Notifications row in More"; exit 1; }
grep -q 'content-desc="Tone: No mercy"' "$OUT/$(printf '%02d' $N)-notify-settings.xml" || log "   (WARN: no tone switch on the Notifications page)"
$T adb shell input keyevent 4; sleep 1; tap Upcoming && sleep 2
log "-- Upcoming in landscape and at 1.5x on the 320 dp screen (checker P4 #3: only the strip is pinned now)"
$T adb shell settings put system accelerometer_rotation 0
$T adb shell settings put system user_rotation 1; sleep 3; front upcoming-landscape; shot upcoming-landscape 1
# The day may turn at Tashkent midnight mid-run (CI 40932552197: TODAY_SPOKEN said Thursday, the strip said Friday), so either day counts.
TODAY_NOW=$(LC_ALL=C TZ=Asia/Tashkent date '+%A %-d %B')
grep -qF -e "content-desc=\"$TODAY_SPOKEN" -e "content-desc=\"$TODAY_NOW" "$OUT/$(printf '%02d' $N)-upcoming-landscape.xml" || { log "WALKTHROUGH: Upcoming's strip is gone in landscape"; exit 1; }
$T adb shell input swipe 320 240 320 90 300; shot upcoming-landscape-scrolled 1
$T adb shell settings put system user_rotation 0; sleep 3; front upcoming-portrait
$T adb shell settings put system font_scale 1.5; sleep 3; front upcoming-font-1.5; shot upcoming-font-1.5 1
grep -qF -e "content-desc=\"$TODAY_SPOKEN" -e "content-desc=\"$TODAY_NOW" "$OUT/$(printf '%02d' $N)-upcoming-font-1.5.xml" || { log "WALKTHROUGH: Upcoming's strip is gone at 1.5x"; exit 1; }
$T adb shell settings put system font_scale 1.0; sleep 3; front upcoming-font-1.0
$T adb shell input keyevent 4; shot back-to-more-2 1.5
tap Completed && shot completed 2.5
grep -q 'text="Renew the domain"' "$OUT/$(printf '%02d' $N)-completed.xml" || { log "WALKTHROUGH: Completed has no \"Renew the domain\""; exit 1; }
grep -q 'text="Export the hover log"' "$OUT/$(printf '%02d' $N)-completed.xml" && log "   the 20-day-old one came from /api/v1/completed"
# The count is pagel.uz's total (3 rows + 4 with no done_at = 7), not the rows the phone holds (checker P4 #9).
grep -q 'text="7 tasks done"' "$OUT/$(printf '%02d' $N)-completed.xml" || shot completed-count 3
grep -q 'text="7 tasks done"' "$OUT/$(printf '%02d' $N)-completed"*.xml || { log "WALKTHROUGH: Completed does not show pagel.uz's total (7 tasks done)"; grep -oE 'text="[0-9]+ tasks? done"' "$OUT/$(printf '%02d' $N)-"*.xml | tee -a "$OUT/steps.txt"; exit 1; }
tap "Undo: Renew the domain" && shot completed-unticked 1.5
WANT='PATCH /api/v1/tasks/t_c1 {"done":false} auth=True'
for i in $(seq 1 30); do grep -qF "$WANT" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$WANT" "$FAKE_LOG" || { log "WALKTHROUGH: the untick never reached pagel.uz as $WANT"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got the untick (restore) after ~${i} s"
tap "Write the IT Park report" && shot completed-panel 2.5
grep -q 'content-desc="Reopen"' "$OUT/$(printf '%02d' $N)-completed-panel.xml" || { log "WALKTHROUGH: a done task's panel has no Reopen"; exit 1; }
# Back closes the panel and leaves us on the Completed list.
$T adb shell input keyevent 4; sleep 1

log "-- Black hole v2: Clear done -> the hole runs out of the camera -> pagel.uz hears the deletes -> the toast under the camera -> Undo -> the inverse writes (restore + done)"
REM0=$(grep -cE '"remove": ?true' "$FAKE_LOG")
RES0=$(grep -cE '"restore": ?true' "$FAKE_LOG")
# The emulator runs with animations off (reduced motion = the 120 ms fade); the hole needs them on for this step.
$T adb shell settings put global animator_duration_scale 1.0
tap "Clear done" || { log "WALKTHROUGH: Completed has no Clear done"; exit 1; }
sleep 0.7; $T adb exec-out screencap -p > "$OUT/hole-run-a.png"
sleep 0.5; $T adb exec-out screencap -p > "$OUT/hole-run-b.png"
sleep 3
$T adb logcat -d | grep -q "BlackHole run" || { log "WALKTHROUGH: Clear done played no black hole (no 'BlackHole run' in the log)"; exit 1; }
log "   $($T adb logcat -d | grep 'BlackHole run' | tail -1 | cut -c1-140)"
shot hole-after 1
$T adb shell settings put global animator_duration_scale 0.0
UB=$(tr '>' '\n' < "$(xml_of hole-after)" | grep -E 'text="Undo"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tail -1)
[ -n "$UB" ] && [ "$UB" -lt 500 ] || { log "WALKTHROUGH: the clear's Undo toast is not just under the camera (Undo top y=${UB:-none})"; exit 1; }
grep -qE 'text="[0-9]+ cleared"' "$(xml_of hole-after)" || { log "WALKTHROUGH: the toast does not say 'N cleared'"; exit 1; }
log "   the toast says N cleared, Undo at y=$UB (under the camera)"
wait_picker '"remove": ?true' || { log "WALKTHROUGH: the clear never reached pagel.uz as a remove"; exit 1; }
sleep 3
REM1=$(grep -cE '"remove": ?true' "$FAKE_LOG")
GONE=$((REM1 - REM0))
[ "$GONE" -ge 1 ] || { log "WALKTHROUGH: no deletes reached the fake log"; exit 1; }
log "   pagel.uz got $GONE deletes"
shot hole-cleared 1
grep -q 'text="Write the IT Park report"' "$(xml_of hole-cleared)" && { log "WALKTHROUGH: a cleared task is still on Completed"; exit 1; }
tap Undo || { log "WALKTHROUGH: the clear's toast has no Undo"; exit 1; }
for i in $(seq 1 30); do [ "$(( $(grep -cE '"restore": ?true' "$FAKE_LOG") - RES0 ))" -ge "$GONE" ] && break; sleep 1; done
BACK=$(( $(grep -cE '\{"restore": ?true, ?"done": ?true\}' "$FAKE_LOG") ))
[ "$(( $(grep -cE '"restore": ?true' "$FAKE_LOG") - RES0 ))" -ge "$GONE" ] || { log "WALKTHROUGH: Undo sent fewer restores than deletes ($GONE)"; grep -E 'restore|remove' "$FAKE_LOG" | tail -8 | tee -a "$OUT/steps.txt"; exit 1; }
[ "$BACK" -ge "$GONE" ] || { log "WALKTHROUGH: the restores did not carry done:true"; exit 1; }
log "   Undo sent $GONE inverse writes (restore + done)"
shot hole-restored 2
grep -q 'text="Write the IT Park report"' "$(xml_of hole-restored)" || { log "WALKTHROUGH: after Undo the cleared tasks are not back on Completed"; exit 1; }
sleep 1
# Leave Completed for More (its bar), where the walk goes on.
$T adb shell input keyevent 4; sleep 1
front after-phase4
tap Today && shot today-after-inbox 1.5
$T adb shell input swipe 540 1700 540 600 300; shot today-scrolled
tap Today && shot today-retap-top
$T adb shell input keyevent 4; shot after-back

log "-- back on Today leaves PAGEL; open it again for the screen checks"
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
front relaunch

log "-- rotate"
$T adb shell settings put system accelerometer_rotation 0
$T adb shell settings put system user_rotation 1; sleep 3; front landscape; shot landscape 0.5
$T adb shell settings put system user_rotation 0; sleep 3; front portrait-again; shot portrait-again 0.5

log "-- font 1.5x"
$T adb shell settings put system font_scale 1.5; sleep 3; front font-1.5; shot font-1.5 0.5
$T adb shell settings put system font_scale 1.0

log "-- dark mode"
$T adb shell cmd uimode night yes; sleep 3; front dark; shot dark 0.5
$T adb shell cmd uimode night no; sleep 3; front light-again; shot light-again 0.5

kill -INT $REC 2>/dev/null; sleep 3
timeout 60 adb pull /sdcard/walk.mp4 "$OUT/walk.mp4" > /dev/null 2>&1
$T adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
log "-- frames (Android's own counters for the whole walkthrough)"
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile|Number Slow UI thread|Number Frozen frames" "$OUT/gfxinfo.txt" | tee -a "$OUT/steps.txt"
$T adb logcat -d > "$OUT/logcat-walk.txt"
grep -E "Davey!|Skipped [0-9]+ frames" "$OUT/logcat-walk.txt" | tail -15 | tee -a "$OUT/steps.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-walk.txt" && { log "WALKTHROUGH: CRASH (see logcat-walk.txt)"; exit 1; }
# CI 40656925148: "PAGEL isn't responding" sat over step 01 and every later step judged the dialog.
grep -q "ANR in $PKG" "$OUT/logcat-walk.txt" && { log "WALKTHROUGH: ANR (see logcat-walk.txt)"; grep -A3 "ANR in $PKG" "$OUT/logcat-walk.txt" | tee -a "$OUT/steps.txt"; exit 1; }
log "WALKTHROUGH DONE: $N steps"

# ── Phase 5 · Rituals ─────────────────────────────────────────────────────────────────────────────────────────────
log "== Phase 5: Empty your head (3 lines -> 3 tasks on pagel.uz), the evening reminder opens Close the day, keep/later/drop + a pick -> the shutdown, the weekly review"
# Tap $1; if it is not on screen, scroll the list up a little and try again (twice).
tap_scroll() {
  for k in $(seq 1 "${2:-3}"); do
    tap "$1" && return 0
    $T adb shell input swipe 160 460 160 200 300; sleep 1
  done
  return 1
}
# Waits up to $2 s (default 30) for a fixed string in the fake pagel.uz's log.
wait_log() {
  for i in $(seq 1 "${2:-30}"); do grep -qiF "$1" "$FAKE_LOG" && return 0; sleep 1; done
  return 1
}
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
front p5-start
tap Today; shot p5-today 2.5
# The review pill waits for pagel.uz's answer (GET /api/v1/rituals): look again once if it is not there yet.
grep -qF 'content-desc="Weekly review is due"' "$OUT/$(printf '%02d' $N)-p5-today.xml" || shot p5-today 4
# Close the day is an evening pill (18:00-05:00 Tashkent); the sparkle and the due review show at any hour.
HOUR=$(TZ=Asia/Tashkent date +%-H)
EVENING_NOW=0; { [ "$HOUR" -ge 18 ] || [ "$HOUR" -lt 5 ]; } && EVENING_NOW=1
log "   Tashkent hour $HOUR (evening: $EVENING_NOW)"
# The weekly review is a Sunday pill on Today; any other day it lives in More only (Boss 2026-10-31).
SUNDAY_NOW=0; [ "$(TZ=Asia/Tashkent date +%u)" = 7 ] && SUNDAY_NOW=1
grep -qF 'content-desc="Empty your head"' "$OUT/$(printf '%02d' $N)-p5-today.xml" || { log "WALKTHROUGH: Today has no Empty your head"; exit 1; }
if [ "$SUNDAY_NOW" = 1 ]; then
  grep -qF 'content-desc="Weekly review is due"' "$OUT/$(printf '%02d' $N)-p5-today.xml" || { log "WALKTHROUGH: no review pill on Sunday"; exit 1; }
else
  grep -qF 'content-desc="Weekly review is due"' "$OUT/$(printf '%02d' $N)-p5-today.xml" && { log "WALKTHROUGH: the review pill shows on a day that is not Sunday"; exit 1; }
fi
if [ "$EVENING_NOW" = 1 ]; then
  grep -qF 'content-desc="Close the day"' "$OUT/$(printf '%02d' $N)-p5-today.xml" || { log "WALKTHROUGH: no Close the day pill in the evening"; exit 1; }
else
  grep -qF 'content-desc="Close the day"' "$OUT/$(printf '%02d' $N)-p5-today.xml" && { log "WALKTHROUGH: Close the day shows in the day"; exit 1; }
fi

log "-- Empty your head: dump 3 lines, the AI's rows come back, Add 3"
tap "Empty your head" && shot p5-sweep 2
tap "What is on your mind" || exit 1
$T adb shell "input text 'call%smom%sabout%ssunday'"; $T adb shell input keyevent 66
$T adb shell "input text 'fix%sthe%slogin%sbug'"; $T adb shell input keyevent 66
$T adb shell "input text 'learn%sthe%sguitar'"
shot p5-sweep-typed 1.5
# The keyboard covers the button: Back hides it, only while it is up (else Back would leave the screen).
$T adb shell dumpsys input_method | grep -q "mInputShown=true" && { $T adb shell input keyevent 4; sleep 1; }
tap_scroll "Turn into steps" || { log "WALKTHROUGH: no Turn into steps"; exit 1; }
wait_log 'POST /api/v1/sweep {"id":"sw_' || { log "WALKTHROUGH: the dump never reached pagel.uz's AI"; exit 1; }
grep -F 'POST /api/v1/sweep {"id":"sw_' "$FAKE_LOG" | grep -qiF '"dump":"call mom about sunday\nfix the login bug\nlearn the guitar"}' \
  || { log "WALKTHROUGH: the dump reached pagel.uz changed"; grep "/api/v1/sweep" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
shot p5-sweep-rows 2.5
grep -qi 'Call mom about sunday' "$OUT/$(printf '%02d' $N)-p5-sweep-rows.xml" || { log "WALKTHROUGH: the AI's rows are not on screen"; exit 1; }
tap_scroll "Add 3" || { log "WALKTHROUGH: no Add 3"; exit 1; }
shot p5-sweep-added 1.5
# Each add carries the phone's client_id after these fields (round B, J).
for want in '{"project":"humopedia","text":"Call mom about sunday","priority":1,' '{"project":"me","text":"Fix the login bug","priority":1,' '{"project":"ababil","text":"Learn the guitar","priority":3,'; do
  wait_log "POST /api/v1/tasks $want" || { log "WALKTHROUGH: pagel.uz never got the task $want"; grep "POST /api/v1/tasks" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
done
log "   3 lines -> 3 tasks on pagel.uz"
grep -q 'Your head is on paper' "$OUT/$(printf '%02d' $N)-p5-sweep-added.xml" || { log "WALKTHROUGH: no done card after Add 3"; exit 1; }
tap "Pick today's 3" && shot p5-back-today 2

log "-- the evening reminder rings (root adb sends the alarm's own intent): its notification opens Close the day"
$T adb shell am broadcast -n "$PKG/.reminders.ShutdownAlarmReceiver" -a org.pagel.ai.EVENING_FIRE -d pagel://evening > /dev/null
sleep 4
$T adb shell cmd statusbar expand-notifications
shot p5-evening-notification 2
grep -q 'Five minutes, then sleep' "$OUT/$(printf '%02d' $N)-p5-evening-notification.xml" || { log "WALKTHROUGH: no Close the day notification"; exit 1; }
# Its own group on a high channel (checker P5 #5): it stands alone in the shade, and a real tap on it opens the ritual.
$T adb shell dumpsys notification --noredact > "$OUT/notification-dump.txt"
grep -q 'Close the day' "$OUT/notification-dump.txt" || { log "WALKTHROUGH: no Close the day notification in dumpsys"; exit 1; }
tap "Close the day" || { log "WALKTHROUGH: the Close the day notification is not tappable in the shade"; exit 1; }
front p5-shutdown
shot p5-shutdown 2.5
grep -q 'content-desc="Star 1 of' "$OUT/$(printf '%02d' $N)-p5-shutdown.xml" || { log "WALKTHROUGH: the notification did not open Close the day on its first star"; exit 1; }

log "-- Close the day: Keep, Keep, Later, Drop; pick one more; close"
tap "Keep: Call the bank about the report" && shot p5-star-1 1.2 || exit 1
tap 'Keep: Measure the drone[^"]*' && shot p5-star-2 1.2 || exit 1
tap "Later: Record the fractions lesson" && shot p5-star-3 1.2 || exit 1
tap "Drop: Weigh in" && shot p5-picks 1.5 || exit 1
tap_scroll "Pick: Pay the internet bill" && shot p5-picked 1.2 || exit 1
tap_scroll "Finish: close the day" && shot p5-closed 1.5 || exit 1
for want in 'PATCH /api/v1/tasks/t_ci3 {"star":false} auth=True' 'PATCH /api/v1/tasks/t_u2 {"star":true} auth=True' 'PATCH /api/v1/tasks/t_ci5 {"remove":true} auth=True'; do
  wait_log "$want" 40 || { log "WALKTHROUGH: pagel.uz never got $want"; grep PATCH "$FAKE_LOG" | tail -8 | tee -a "$OUT/steps.txt"; exit 1; }
done
wait_log 'POST /api/v1/rituals {"action":"shutdown"' || { log "WALKTHROUGH: the shutdown event never reached pagel.uz"; exit 1; }
grep -F 'POST /api/v1/rituals {"action":"shutdown"' "$FAKE_LOG" | grep -qE '"kept":2,"pushed":1,"dropped":1,"picked":1}' \
  || { log "WALKTHROUGH: the shutdown counts are wrong"; grep rituals "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got later/drop/pick and the shutdown event (kept 2, pushed 1, dropped 1, picked 1)"
$T adb shell dumpsys alarm > "$OUT/alarm-dump-evening.txt"
EVENING=$(grep -c "org.pagel.ai.EVENING_FIRE" "$OUT/alarm-dump-evening.txt")
log "   evening alarms on AlarmManager: $EVENING"
grep -B2 -A6 "org.pagel.ai.EVENING_FIRE" "$OUT/alarm-dump-evening.txt" | head -12 >> "$OUT/steps.txt"
[ "$EVENING" -ge 1 ] || { log "WALKTHROUGH: no evening alarm is set"; exit 1; }
grep -q 'content-desc="Go to Today"' "$OUT/$(printf '%02d' $N)-p5-closed.xml" || { log "WALKTHROUGH: the closed day has no way back to Today"; exit 1; }
tap "Go to Today"; front p5-today-closed; shot p5-today-closed 2
# Boss 2026-10-28: "I closed the day … I should have an empty Today". The calm card, and not one task row.
grep -q 'Nothing needs you tonight' "$OUT/$(printf '%02d' $N)-p5-today-closed.xml" || { log "WALKTHROUGH: Today after Close the day has no closed card"; exit 1; }
ROWS=$(grep -c 'content-desc="Done: ' "$OUT/$(printf '%02d' $N)-p5-today-closed.xml" || true)
[ "$ROWS" -eq 0 ] || { log "WALKTHROUGH: Today still shows $ROWS task rows after Close the day"; exit 1; }
for id in t_u2; do wait_log "PATCH /api/v1/tasks/$id {\"due\":\"$(TZ=Asia/Tashkent date -d '+1 day' +%Y-%m-%d)\"}" 40 || log "   (WARN: no tomorrow date for the pick $id)"; done
if [ "$EVENING_NOW" = 1 ]; then
  grep -q 'content-desc="Close the day: closed tonight"' "$OUT/$(printf '%02d' $N)-p5-today-closed.xml" || { log "WALKTHROUGH: Today does not show the day as closed"; exit 1; }
fi

log "-- the weekly review: 4 steps, Finish -> the review event"
if [ "$SUNDAY_NOW" = 1 ]; then tap "Weekly review is due" && shot p5-review 2.5 || exit 1; else tap More && tap "Weekly review" && shot p5-review 2.5 || { log "WALKTHROUGH: no Weekly review in More"; exit 1; }; fi
grep -q 'content-desc="Step 1: Inbox"' "$OUT/$(printf '%02d' $N)-p5-review.xml" || { log "WALKTHROUGH: the review has no steps"; exit 1; }
tap_scroll Next && tap_scroll Next && shot p5-review-next-steps 1.5 || exit 1
grep -q 'Labs' "$OUT/$(printf '%02d' $N)-p5-review-next-steps.xml" || { log "WALKTHROUGH: the stuck project Labs is not asked for a next step"; exit 1; }
tap_scroll Next && shot p5-review-someday 1.5 || exit 1
tap_scroll "Finish review" && shot p5-review-done 1.5 || exit 1
wait_log 'POST /api/v1/rituals {"action":"review"' || { log "WALKTHROUGH: the review event never reached pagel.uz"; exit 1; }
log "   pagel.uz got the weekly review"
$T adb logcat -d > "$OUT/logcat-p5.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-p5.txt" && { log "WALKTHROUGH: CRASH in Phase 5 (see logcat-p5.txt)"; exit 1; }
log "PHASE 5 WALK DONE: $N steps"

log "-- Phase 6a, the Compass: a screen lock (PIN 1111, the test credential), More → Compass, start it with the PIN,"
log "   write why I live, link a project; the fake pagel.uz must get ciphertext only (no goal words anywhere in its log)"
# The Keystore guards the key only behind a secure lock screen; the emulator has none until this PIN. The screen must
# stay on, or the lock screen would cover every step after this.
$T adb shell svc power stayon true
$T adb shell locksettings set-pin 1111 > /dev/null 2>&1 || log "   (WARN: locksettings set-pin failed)"
# Back to a tab's root, where the bar (and More) shows.
for k in 1 2 3 4; do tap More && break; $T adb shell input keyevent 4; sleep 1; done
front c-more
$T adb shell input swipe 160 200 160 600 400; sleep 1
tap Compass && shot c-new 2.5 || { log "WALKTHROUGH: More has no Compass"; exit 1; }
grep -q 'content-desc="Start my Compass"' "$OUT/$(printf '%02d' $N)-c-new.xml" || { log "WALKTHROUGH: the empty Compass does not invite writing"; exit 1; }
# FLAG_SECURE: the window that shows the Compass is marked secure (screenshots of it come out black).
$T adb shell dumpsys window windows > "$OUT/window-dump-compass.txt" 2>&1
grep -B2 -A12 "$PKG/$PKG.MainActivity" "$OUT/window-dump-compass.txt" | grep -q "SECURE" || { log "WALKTHROUGH: the Compass window is NOT FLAG_SECURE (window-dump-compass.txt)"; exit 1; }
log "   the Compass window is FLAG_SECURE"
# A secure window's capture is black: a goal on screen in a readable capture is a leak (compass re-check N3).
secure_shot() {
  local said
  said=$(python3 .github/scripts/png-dark.py "$OUT/$(printf '%02d' $N)-$1.png" 0.01) \
    || { log "WALKTHROUGH: LEAK - $1 was captured readable ($said; at most 1 % may be not black)"; exit 1; }
  log "   $1 capture is black ($said)"
}
unlock_pin() {
  sleep 2.5
  $T adb shell input text 1111; sleep 0.5; $T adb shell input keyevent 66; sleep 3
}
tap "Start my Compass" && unlock_pin
shot c-open 2
grep -q 'content-desc="Write why you live"' "$OUT/$(printf '%02d' $N)-c-open.xml" || { log "WALKTHROUGH: the Compass did not open after the PIN"; exit 1; }
tap "Write why you live" && shot c-sheet 2.5 || exit 1
tap "Why I live" || { log "WALKTHROUGH: the goal sheet has no Why I live field"; exit 1; }
$T adb shell "input text 'Walkthrough%szebra%spurpose%sline'"
$T adb shell dumpsys input_method | grep -q "mInputShown=true" && { $T adb shell input keyevent 4; sleep 1; }
tap_scroll Save && shot c-saved 2.5 || { log "WALKTHROUGH: no Save in the goal sheet"; exit 1; }
secure_shot c-saved
grep -q 'Walkthrough zebra purpose line' "$OUT/$(printf '%02d' $N)-c-saved.xml" || { log "WALKTHROUGH: the purpose is not on the path after Save"; exit 1; }
wait_log 'PUT /api/v1/compass/items/g_' 40 || { log "WALKTHROUGH: the sealed purpose never reached pagel.uz"; exit 1; }
grep -F 'PUT /api/v1/compass/items/g_' "$FAKE_LOG" | head -1 | grep -qE '\{"kind":"purpose","parent":null,"blob":"[A-Za-z0-9+/=]{40,}","starts":null,"ends":null,"deleted":false,"base":0\} auth=True' \
  || { log "WALKTHROUGH: the purpose did not reach pagel.uz as a sealed item"; grep compass "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
grep -qi 'zebra' "$FAKE_LOG" && { log "WALKTHROUGH: GOAL WORDS REACHED pagel.uz IN THE CLEAR"; grep -i zebra "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
# PAGEL's own log lines (its pid): no goal words.
APP_PID=$($T adb shell pidof "$PKG" | tr -cd '0-9')
grep -qi 'zebra' <($T adb logcat -d --pid="$APP_PID") && { log "WALKTHROUGH: goal words in PAGEL's logcat"; exit 1; }
log "   pagel.uz got the purpose as ciphertext only (no goal words in its log or in logcat)"

log "-- a project serves the goal: Projects → Humopedia → Link to a goal → the purpose; pagel.uz gets only its id"
$T adb shell input keyevent 4; sleep 1
for k in 1 2 3; do $T adb shell input swipe 160 450 160 120 400; sleep 1; tap Projects && break; $T adb shell input keyevent 4; sleep 1; done
front c-projects
tap Humopedia && shot c-project 2 || { log "WALKTHROUGH: no Humopedia project"; exit 1; }
tap "Link to a goal" && shot c-picker 2 || { log "WALKTHROUGH: the project page has no Link to a goal"; exit 1; }
# Judge the capture only once the sheet is up (its own words are in the tree), never the page before it.
grep -q 'Which goal does it serve' "$OUT/$(printf '%02d' $N)-c-picker.xml" || shot c-picker 2
grep -q 'Which goal does it serve' "$OUT/$(printf '%02d' $N)-c-picker.xml" || { log "WALKTHROUGH: the goal picker did not open"; exit 1; }
secure_shot c-picker
if grep -q 'content-desc="Unlock"' "$OUT/$(printf '%02d' $N)-c-picker.xml"; then tap Unlock && unlock_pin; shot c-picker-open 1.5; fi
# The sheet may still be rising: look again once before calling it missing.
{ tap 'Serves: Why I live[^"]*' || { sleep 2; tap 'Serves: Why I live[^"]*'; }; } && shot c-linked 2 || { log "WALKTHROUGH: the picker has no purpose to pick"; exit 1; }
wait_log 'PUT /api/v1/compass/links/humopedia {"goal":"g_' 40 || { log "WALKTHROUGH: the link never reached pagel.uz as an id"; grep compass "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
grep -q 'content-desc="Serves a goal"' "$OUT/$(printf '%02d' $N)-c-linked.xml" || log "   (WARN: the project row did not turn to Serves a goal yet)"
grep -qi 'zebra' "$FAKE_LOG" && { log "WALKTHROUGH: GOAL WORDS REACHED pagel.uz IN THE CLEAR"; exit 1; }
log "   pagel.uz got humopedia → the goal's id, no words"
$T adb shell locksettings clear --old 1111 > /dev/null 2>&1 || true
$T adb logcat -d > "$OUT/logcat-compass.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-compass.txt" && { log "WALKTHROUGH: CRASH in the Compass (see logcat-compass.txt)"; exit 1; }
log "PHASE 6a WALK DONE: $N steps"

# ── Core round · projects you shape (2026-10-29) ───────────────────────────────────────────────────────────────────
log "== Core: new project in 2 taps -> a task inline -> recolour (pagel.uz gets it) -> new icon -> drag a task in -> merge -> Undo -> finish"
last() { echo "$OUT/$(printf '%02d' $N)-$1.xml"; }
hide_keys() { $T adb shell dumpsys input_method | grep -q "mInputShown=true" && { $T adb shell input keyevent 4; sleep 1; }; return 0; }
to_projects() {
  for k in 1 2 3 4; do tap More && break; $T adb shell input keyevent 4; sleep 1; done
  for k in 1 2 3; do $T adb shell input swipe 160 450 160 120 400; sleep 1; tap Projects && return 0; done
  return 1
}
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
front core-start
to_projects && shot core-projects 1.5 || { log "WALKTHROUGH: no Projects under More"; exit 1; }
tap "New project" || { log "WALKTHROUGH: Projects has no + (New project)"; exit 1; }
sleep 1.5
$T adb shell "input text 'CI%sRocket'"; $T adb shell input keyevent 66
shot core-new 2.5
grep -q 'Add the next step' "$(last core-new)" || { log "WALKTHROUGH: the new project's page did not open on Add the next step"; exit 1; }
wait_log 'POST /api/v1/projects {"name":"ci-rocket","title":"CI Rocket"' 30 || { log "WALKTHROUGH: the new project never reached pagel.uz"; exit 1; }
log "   a new project in 2 taps: + then its name and Enter; pagel.uz has ci-rocket"

tap "Add a task to CI Rocket" || { log "WALKTHROUGH: the project page has no inline Add"; exit 1; }
sleep 1; $T adb shell "input text 'Write%sthe%sflight%splan'"; $T adb shell input keyevent 66; sleep 1; hide_keys
shot core-task 2
grep -q 'Write the flight plan' "$(last core-task)" || { log "WALKTHROUGH: the inline task is not on the page"; exit 1; }
wait_log 'POST /api/v1/tasks {"project":"ci-rocket","text":"Write the flight plan"' 30 || { log "WALKTHROUGH: the inline task never reached pagel.uz"; exit 1; }
grep -q 'Next step' "$(last core-task)" || { log "WALKTHROUGH: the page does not open on its Next step"; exit 1; }

log "-- paste a list (checker #11): Paste a list -> 5 lessons, one per line -> Add 5 tasks; pagel.uz gets 5 POSTs in that order; the one Undo takes all 5 back"
REMOVES=$(grep -c '"remove":true' "$FAKE_LOG")
tap "Paste a list" || { log "WALKTHROUGH: the project page has no Paste a list"; exit 1; }
sleep 1.5
for n in 1 2 3 4 5; do $T adb shell "input text 'Lesson%s$n%sof%sfive'"; [ "$n" -lt 5 ] && $T adb shell input keyevent 66; done
shot core-paste 1.5
grep -qE '(text|content-desc)="Add 5 tasks"' "$(last core-paste)" || { log "WALKTHROUGH: the paste sheet does not offer Add 5 tasks"; exit 1; }
tap "Add 5 tasks" || { log "WALKTHROUGH: no Add 5 tasks button"; exit 1; }
# Undo at once: the toast lives 5 s. The adds left first (the outbox keeps its order), so all 5 POSTs still arrive.
sleep 0.5
tap Undo && shot core-paste-undo 0.5 || { log "WALKTHROUGH: the paste toast has no Undo"; exit 1; }
for i in $(seq 1 30); do [ "$(grep -cE 'POST /api/v1/tasks \{"project":"ci-rocket","text":"Lesson [1-5] of five"' "$FAKE_LOG")" -ge 5 ] && break; sleep 1; done
ORDER=$(grep -oE 'POST /api/v1/tasks \{"project":"ci-rocket","text":"Lesson [1-5] of five"' "$FAKE_LOG" | grep -oE 'Lesson [1-5]' | tr -dc '1-5')
[ "$ORDER" = "12345" ] || { log "WALKTHROUGH: the pasted lessons reached pagel.uz as \"$ORDER\", not 12345 in order"; exit 1; }
for i in $(seq 1 30); do [ "$(grep -c '"remove":true' "$FAKE_LOG")" -ge $(( REMOVES + 5 )) ] && break; sleep 1; done
[ "$(grep -c '"remove":true' "$FAKE_LOG")" -ge $(( REMOVES + 5 )) ] || { log "WALKTHROUGH: the paste's Undo did not take all 5 back on pagel.uz"; grep -E 'remove|Lesson' "$FAKE_LOG" | tail -12 | tee -a "$OUT/steps.txt"; exit 1; }
shot core-paste-gone 1.5
grep -q 'Lesson 1 of five' "$(last core-paste-gone)" && { log "WALKTHROUGH: after Undo the pasted lessons are still on the page"; exit 1; }
log "   5 pasted lessons reached pagel.uz in order; one Undo sent 5 removes"

tap "Project settings" && shot core-settings 2 || { log "WALKTHROUGH: no Project settings on the page"; exit 1; }
tap_scroll "Colour violet" || { log "WALKTHROUGH: the colour picker has no violet"; exit 1; }
wait_log 'PATCH /api/v1/projects/ci-rocket {"color":"violet"}' 30 || { log "WALKTHROUGH: the colour never reached pagel.uz"; exit 1; }
shot core-violet 1.5
log "   recoloured: pagel.uz got color=violet"
tap_scroll "Search icons" || { log "WALKTHROUGH: no icon search"; exit 1; }
sleep 1; $T adb shell input text rocket; sleep 1; hide_keys
tap_scroll "Emoji rocket" || { log "WALKTHROUGH: the icon search found no rocket"; exit 1; }
wait_log 'PATCH /api/v1/projects/ci-rocket {"icon":"emoji:' 30 || { log "WALKTHROUGH: the icon never reached pagel.uz"; exit 1; }
shot core-icon 1.5
tap Done || $T adb shell input keyevent 4
sleep 1

log "-- drag a task in: Me → long-press Pay the internet bill (a list row: the next step sits in its card, and Weigh in was dropped in Close the day) → drop it on CI Rocket in the Move to strip"
$T adb shell input keyevent 4; sleep 1
tap_scroll Me && shot core-me 2 || { log "WALKTHROUGH: no Me project"; exit 1; }
XML="$(last core-me)"
DPI=$($T adb shell wm density | grep -oE '[0-9]+' | tail -1); DPI=${DPI:-160}
H0=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2); BAR=$(( ${H0:-640} - 30 * DPI / 160 ))
# Me's habits come before it, so scroll in small steps until the row is clear of the Move to strip's place.
for k in 1 2 3 4 5 6; do FROM=$(centre "Pay the internet bill") && [ "${FROM#* }" -lt $(( ${BAR:-550} - 120 )) ] && break; FROM=""; $T adb shell input swipe 160 450 160 300 400; sleep 1; done
[ -n "$FROM" ] || { log "WALKTHROUGH: Pay the internet bill is not on the Me page"; exit 1; }
DPI=$($T adb shell wm density | grep -oE '[0-9]+' | tail -1); DPI=${DPI:-160}
# The project page has no tab bar: "Today" above matched a row's date ("Today 21:30"), so the drop landed in the list
# and became a reorder (CI 40936570317 sent {"sort":109.0}). The Move to strip is pinned to the screen bottom
# (~95 dp tall, 8 dp + the nav inset above the edge), so drop 85 dp above the bottom: inside it for any inset 0-56 dp.
H=$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2); H=${H:-640}
TX=$(( 46 * DPI / 160 )); TY=$(( H - 85 * DPI / 160 ))
log "   drag from $FROM to $TX $TY (screen height $H)"
$T adb shell input draganddrop $FROM $TX $TY 1800
shot core-dragged 2
wait_log 'PATCH /api/v1/tasks/t_u2 {"project":"ci-rocket"}' 30 || { log "WALKTHROUGH: the dragged task never moved on pagel.uz"; grep -E 'PATCH /api/v1/tasks/t_u2' "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   Pay the internet bill moved into CI Rocket (pagel.uz has it)"

log "-- merge CI Rocket into Humopedia, then Undo inside 5 s: pagel.uz never hears of the merge"
$T adb shell input keyevent 4; sleep 1
tap_scroll "CI Rocket" && shot core-rocket 2 || { log "WALKTHROUGH: CI Rocket is not in the list"; exit 1; }
tap "Project settings" || exit 1
sleep 1.5
tap_scroll "Merge into another project" 8 || { log "WALKTHROUGH: settings have no Merge"; exit 1; }
tap_scroll "Into Humopedia" 4 || { log "WALKTHROUGH: Merge offers no Humopedia"; exit 1; }
tap_scroll "Merge into Humopedia" 4 || { log "WALKTHROUGH: no Merge button"; exit 1; }
# Undo straight away: the hold is 5 s, and a shot (screencap + dump) before it took ~5 s on the emulator, so the
# Undo landed ~12 s after the merge and the merge had rightly left (CI 40966460030: POST at :12.5, Undo dump at :15.9).
sleep 0.5
tap Undo || { log "WALKTHROUGH: the merge toast has no Undo"; exit 1; }
shot core-merged 0.5
sleep 8
grep -q 'POST /api/v1/projects/ci-rocket/merge' "$FAKE_LOG" && { log "WALKTHROUGH: the merge reached pagel.uz although it was undone"; exit 1; }
$T adb shell input keyevent 4; sleep 1
to_projects; shot core-after-undo 2
grep -q 'text="CI Rocket"' "$(last core-after-undo)" || { log "WALKTHROUGH: CI Rocket did not come back after Undo"; exit 1; }
log "   merge undone in time: no POST merge, CI Rocket is back"

log "-- finish: tick both tasks → Everything is done → Finish project with a shipped line"
tap_scroll "CI Rocket" && shot core-rocket-2 2 || exit 1
for t in "Write the flight plan" "Pay the internet bill"; do tap "Done: $t" || { sleep 2; tap "Done: $t"; } || { log "WALKTHROUGH: no ring for $t"; exit 1; }; sleep 2; done
shot core-all-done 2
tap_scroll "Finish project" || { log "WALKTHROUGH: all done but no Finish project"; exit 1; }
sleep 1.5; tap "What I shipped"; sleep 1
$T adb shell "input text 'CI%srocket%sflew'"; $T adb shell input keyevent 66
shot core-finished 2.5
wait_log 'PATCH /api/v1/projects/ci-rocket {"finished":true,"shipped":"CI rocket flew"}' 30 || { log "WALKTHROUGH: the finish never reached pagel.uz"; grep ci-rocket "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
grep -q 'CI rocket flew' "$(last core-finished)" || shot core-finished 2
log "   finished: pagel.uz has finished=true and the shipped line"

log "-- KPI: the app counted opens and the first tick; Your week shows in More"
wait_log 'POST /api/v1/kpi {"day":"' 10 || { log "WALKTHROUGH: no app-open count reached pagel.uz"; exit 1; }
wait_log '"first_tick_ms":' 10 || { log "WALKTHROUGH: no open-to-first-tick count reached pagel.uz"; exit 1; }
grep -E 'POST /api/v1/kpi' "$FAKE_LOG" | grep -vqE '\{"day":"[0-9-]+","((open":true|first_tick_ms":[0-9]+)|event":"[a-z_]+"(,"reason":"[a-z_]+")?)\}' && { log "WALKTHROUGH: a KPI post carries more than counts"; exit 1; }
for k in 1 2 3 4; do tap More && break; $T adb shell input keyevent 4; sleep 1; done
for k in 1 2 3; do $T adb shell input swipe 160 450 160 120 400; sleep 1; done
shot core-week 2
grep -q 'Your week' "$(last core-week)" || { log "WALKTHROUGH: More has no Your week card"; exit 1; }
$T adb logcat -d > "$OUT/logcat-core.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-core.txt" && { log "WALKTHROUGH: CRASH in the projects round (see logcat-core.txt)"; exit 1; }
log "CORE PROJECTS WALK DONE: $N steps"

log "-- the Namoz page (namoz brief): open it from Habits; tick Asr; long-press Shom -> In the mosque; the counts change"
# Ticks count from 1 November. Last in the walk, the emulator's clock moves to Thu 12 November 2026, 16:10 Tashkent
# (11:10 UTC, Asr's time). adb root restarts adbd, which drops adb reverse: it is put back before the app starts.
$T adb root > /dev/null 2>&1; sleep 3; $T adb wait-for-device
$T adb shell "date -u 111211102026.00" > /dev/null || { log "WALKTHROUGH: could not set the emulator's date for Namoz"; exit 1; }
$T adb reverse tcp:8799 tcp:8799
$T adb shell am force-stop "$PKG"; $T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
front namoz-start
for k in 1 2 3; do tap Habits && break; $T adb shell input keyevent 4; sleep 1; done
sleep 2
for k in 1 2 3; do tap Namoz && break; $T adb shell input swipe 160 200 160 600 400; sleep 1; done
shot namoz-page 3
grep -q 'text="Next · ' "$(last namoz-page)" || { log "WALKTHROUGH: the Namoz page did not open (no Next · line)"; exit 1; }
grep -q 'text="Tracking starts 1 November"' "$(last namoz-page)" && { log "WALKTHROUGH: on 12 November the page still waits for 1 November"; exit 1; }
# A row reads "Asr 14:52–16:58" (its name + its window); the stats rows read "Asr: n of d days".
tap 'Asr [^"]*' || { log "WALKTHROUGH: no Asr row on the Namoz page"; exit 1; }
shot namoz-asr 2
$T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
B=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -E 'content-desc="Shom [^"]*"' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
[ -n "$B" ] || { log "WALKTHROUGH: no Shom row on the Namoz page"; exit 1; }
set -- $(echo "$B" | grep -oE '[0-9]+' | tr '\n' ' ')
$T adb shell input swipe $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) 900
shot namoz-how 2
tap "In the mosque" || { log "WALKTHROUGH: the long press showed no In the mosque choice"; exit 1; }
sleep 2
# The counts sit under the five rows: scroll until the mosque line shows.
for k in 1 2 3 4; do onscreen '[0-9]+ in the mosque this month' && break; $T adb shell input swipe 160 500 160 200 400; sleep 1; done
shot namoz-counts 2
grep -q 'text="1 in the mosque this month"' "$(last namoz-counts)" || { log "WALKTHROUGH: the mosque count did not become 1"; exit 1; }
grep -q 'content-desc="Asr: 1 of 12 days"' "$(last namoz-counts)" || { log "WALKTHROUGH: Asr's 30-day count did not become 1 of 12"; exit 1; }
grep -q 'content-desc="Shom: 1 of 12 days"' "$(last namoz-counts)" || { log "WALKTHROUGH: Shom's 30-day count did not become 1 of 12"; exit 1; }
wait_log '"day":"2026-11-12"' 30 && log "   the prayer ticks reached pagel.uz" || log "   (WARN: no tick for 2026-11-12 reached pagel.uz)"
$T adb logcat -d > "$OUT/logcat-namoz.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-namoz.txt" && { log "WALKTHROUGH: CRASH on the Namoz page (see logcat-namoz.txt)"; exit 1; }
# Put the real clock back: the next walks (Phase 5 Close the day) compare the app's today with the runner's date.
$T adb shell "date -u $(date -u +%m%d%H%M%Y.%S)" > /dev/null || { log "WALKTHROUGH: could not put the emulator's clock back"; exit 1; }
$T adb shell am force-stop "$PKG"; $T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 2
log "NAMOZ WALK DONE: $N steps (clock back to $(date -u +%FT%TZ))"

log "-- How it works (Boss 2026-10-30): More -> How it works; swipe through all 10 cards, each reads its title; Try it opens the real screen"
front guide-start
# After the Namoz restart something may sit over the tab bar: wait, then Back once, then bring PAGEL to the front again.
for k in 1 2 3 4 5 6; do
  tap More && break
  [ "$k" -ge 3 ] && { $T adb shell input keyevent 4; sleep 1; $T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
  sleep 3
done
shot guide-before-more 0.5
front guide-more
shot guide-more 1.5
tap "How it works" || { log "WALKTHROUGH: no How it works row under More"; exit 1; }
shot guide-card-1 2
TITLES=("Capture" "Clarify" "Next step" "Projects" "Areas" "Calendar" "Waiting and Someday" "Today.*s 3" "Close the day and the week" "Stuck")
for i in $(seq 0 9); do
  X="$(last guide-card-$((i + 1)))"
  grep -qE "text=\"${TITLES[$i]}\"" "$X" || { log "WALKTHROUGH: How it works card $((i + 1)) does not read \"${TITLES[$i]}\""; exit 1; }
  grep -qF "content-desc=\"Card $((i + 1)) of 10\"" "$X" || { log "WALKTHROUGH: card $((i + 1)) has no Card $((i + 1)) of 10 marker"; exit 1; }
  grep -qF 'content-desc="Try it"' "$X" || { log "WALKTHROUGH: card $((i + 1)) has no Try it"; exit 1; }
  [ "$i" -lt 9 ] && { $T adb shell input swipe 290 250 30 250 250; shot guide-card-$((i + 2)) 2.5; }
done
log "   all 10 cards read"
# Try it on the Close the day card (the last card's neighbour): the real Close the day opens.
$T adb shell input swipe 30 250 290 250 250; sleep 2.5
tap "Try it" || { log "WALKTHROUGH: Try it was not on screen"; exit 1; }
shot guide-try-close 2
# Only the real Close the day has this line; the guide's own card title would also read "Close the day".
grep -qE 'Five minutes\. Then nothing needs you tonight|Closed tonight|Day closed' "$(last guide-try-close)" || { log "WALKTHROUGH: Try it on the Close the day card did not open the real Close the day"; exit 1; }
grep -q 'content-desc="Card [0-9]* of 10"' "$(last guide-try-close)" && { log "WALKTHROUGH: the guide is still under Close the day after Try it"; exit 1; }
log "-- the same-tab case: Inbox -> How sorting works -> How it works -> Clarify -> Try it -> the Inbox, the guide gone"
$T adb shell input keyevent 4; sleep 1.5
tap Inbox && shot guide-inbox 2
tap "How sorting works" || { log "WALKTHROUGH: no How sorting works button on the Inbox"; exit 1; }
shot guide-sheet 2
for k in 1 2 3 4 5; do onscreen 'How it works [^"]*' && break; $T adb shell input swipe 160 500 160 200 300; sleep 1; done
tap 'How it works [^"]*' || { log "WALKTHROUGH: the sorting sheet has no How it works row"; exit 1; }
shot guide-from-inbox 2.5
grep -q 'text="Clarify"' "$(last guide-from-inbox)" || { log "WALKTHROUGH: the Inbox sheet did not open the Clarify card"; exit 1; }
tap "Try it" || { log "WALKTHROUGH: Try it was not on screen (Clarify)"; exit 1; }
shot guide-back-inbox 2.5
grep -q 'content-desc="How sorting works"' "$(last guide-back-inbox)" || { log "WALKTHROUGH: Try it on Clarify did not return to the Inbox"; exit 1; }
grep -q 'content-desc="Card [0-9]* of 10"' "$(last guide-back-inbox)" && { log "WALKTHROUGH: the guide stayed on screen after Try it from the Inbox"; exit 1; }
log "GUIDE WALK DONE: $N steps"
log "-- Black hole (black-hole brief): More → Completed → Clear done; the rows leave at once, pagel.uz gets the removes after"
log "   their 5 s hold; Undo after that sends restore + done and the rows come back"
# CI 41070909739: right after the Namoz restart the tab bar was not up yet; Back then left PAGEL and a swipe pulled the
# notification shade. Wait for the bar instead (no Back here), and swipe only inside the 640 px screen.
$T adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
front hole-start
for k in $(seq 1 20); do onscreen More && break; sleep 1; done
shot hole-start 0.5
tap More || { log "WALKTHROUGH: no More tab before the black hole step"; exit 1; }
sleep 1.5
for k in 1 2 3; do tap Completed && break; $T adb shell input swipe 160 200 160 500 400; sleep 1; done
shot hole-completed 2.5
grep -qE '(text|content-desc)="Clear done"' "$(last hole-completed)" || shot hole-completed 3
grep -qE '(text|content-desc)="Clear done"' "$(last hole-completed)" || { log "WALKTHROUGH: Completed shows no Clear done (no done rows?)"; exit 1; }
tap "Clear done" || { log "WALKTHROUGH: Completed has no Clear done"; exit 1; }
shot hole-cleared 1.5
grep -qE '(text|content-desc)="Clear done"|text="Write the IT Park report"' "$(last hole-cleared)" && { log "WALKTHROUGH: the done rows are still on screen after Clear done"; exit 1; }
grep -qE 'text="Cleared [0-9]+ done tasks?"' "$(last hole-cleared)" || { log "WALKTHROUGH: Clear done shows no Cleared toast"; exit 1; }
# Checker 2026-10-30 #2: the empty list says All clear and its count is the list's (it said "4 tasks done").
grep -q 'text="All clear"' "$(last hole-cleared)" || { log "WALKTHROUGH: the cleared list does not say All clear"; exit 1; }
grep -q 'text="0 tasks done"' "$(last hole-cleared)" || { log "WALKTHROUGH: the cleared list's count is not 0 ($(grep -oE '[0-9]+ tasks? done' "$(last hole-cleared)" | head -1))"; exit 1; }
log "   $(grep -oE 'Cleared [0-9]+ done tasks?' "$(last hole-cleared)" | head -1), the list says All clear · 0 tasks done at once"
wait_log 'PATCH /api/v1/tasks/t_c2 {"remove":true} auth=True' 25 || { log "WALKTHROUGH: the remove never reached pagel.uz"; grep -E 'PATCH /api/v1/tasks/t_c' "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
log "   pagel.uz got the removes ($(grep -c '{"remove":true}' "$FAKE_LOG") in all)"
tap Undo || { log "WALKTHROUGH: the Clear done toast lost its Undo before the removes left"; exit 1; }
wait_log 'PATCH /api/v1/tasks/t_c2 {"restore":true,"done":true} auth=True' 30 || { log "WALKTHROUGH: the Undo never reached pagel.uz as restore + done"; grep -E 'PATCH /api/v1/tasks/t_c' "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
shot hole-undone 2.5
grep -qE '(text|content-desc)="Clear done"' "$(last hole-undone)" || shot hole-undone 3
grep -qE '(text|content-desc)="Clear done"' "$(last hole-undone)" || { log "WALKTHROUGH: Undo did not bring the done rows back"; exit 1; }
$T adb logcat -d > "$OUT/logcat-hole.txt"
grep -q "Process: $PKG, PID" "$OUT/logcat-hole.txt" && { log "WALKTHROUGH: CRASH in the black hole (see logcat-hole.txt)"; exit 1; }
log "BLACK HOLE WALK DONE: $N steps"
