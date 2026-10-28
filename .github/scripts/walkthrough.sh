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

log "-- the task panel (Phase 1): tap a row → its panel; tick step 1, set P1, pick a day 2 days ahead in the calendar"
DAY=$(TZ=Asia/Tashkent date -d '+2 days' +%Y-%m-%d)
SPOKEN=$(LC_ALL=C TZ=Asia/Tashkent date -d '+2 days' '+%-d %B %Y')
tap "Call the bank about the report" && shot panel-open 2.5
grep -q 'Find the account number' "$OUT/$(printf '%02d' $N)-panel-open.xml" || { log "WALKTHROUGH: the task panel did not open with its steps"; exit 1; }
tap "Done: Find the account number" && shot panel-step-ticked 1.5
# Priority is a row like Date and Repeat (checker 2026-10-28 #8): it opens its sheet, P1 picks and closes it.
tap "Priority: P[1-4]" && shot panel-priority-sheet 2
tap "P1" && shot panel-p1 1.5
tap "Date: None" && shot panel-date-sheet 2.5
grep -q "content-desc=\"$SPOKEN\"" "$OUT/$(printf '%02d' $N)-panel-date-sheet.xml" || { log "WALKTHROUGH: the calendar has no day \"$SPOKEN\""; exit 1; }
tap "$SPOKEN" && shot panel-day-picked 2.5
for want in '{"steps":["Find the account number","Call at 10"],"ticked":[0]}' '{"priority":0}' "{\"due\":\"$DAY\"}"; do
  for i in $(seq 1 30); do grep -qF "PATCH /api/v1/tasks/t_ci2 $want auth=True" "$FAKE_LOG" && break; sleep 1; done
  grep -qF "PATCH /api/v1/tasks/t_ci2 $want auth=True" "$FAKE_LOG" || { log "WALKTHROUGH: pagel.uz never got $want"; grep "PATCH" "$FAKE_LOG" | tee -a "$OUT/steps.txt"; exit 1; }
  log "   pagel.uz got $want"
done
$T adb shell input keyevent 4; shot panel-closed 1.5

log "-- the Inbox (Phase 2): sort 5 saves with no network, come back online, pagel.uz gets all 5 in the order made"
tap Inbox && shot inbox 3
grep -q 'text="10 AI tools lists are mostly ads"' "$OUT/$(printf '%02d' $N)-inbox.xml" || { log "WALKTHROUGH: the Inbox shows no card from pagel.uz"; exit 1; }
# Offline for real: the app's only road to the fake pagel.uz is adb reverse, so removing it refuses every call.
$T adb reverse --remove tcp:8799
log "   offline (adb reverse removed)"
BEFORE=$(grep -c "POST /api/v1/inbox" "$FAKE_LOG")
# The centre of the first node whose text is $1, as "x y".
centre() {
  $T adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b; b=$(timeout 20 adb exec-out cat /sdcard/ui.xml < /dev/null | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [ -n "$b" ] || { log "   (no \"$1\" on screen)"; return 1; }
  set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
RING=$(grep -oE 'content-desc="[0-9]+ of 20 sorted today"' "$OUT/$(printf '%02d' $N)-inbox.xml" | grep -oE '[0-9]+' | head -1)
[ -n "$RING" ] || { log "WALKTHROUGH: the Inbox ring says nothing"; exit 1; }
P=$(centre "10 AI tools lists are mostly ads") || exit 1; set -- $P
$T adb shell input swipe "$1" "$2" $(( $1 - 700 )) "$2" 250; shot inbox-swiped-left-drop 1.5
grep -q "content-desc=\"$((RING + 1)) of 20 sorted today\"" "$OUT/$(printf '%02d' $N)-inbox-swiped-left-drop.xml" \
  || { log "WALKTHROUGH: the ring did not go $RING -> $((RING + 1)) after one sort"; grep -oE 'content-desc="[0-9]+ of 20 sorted today"' "$OUT/$(printf '%02d' $N)-inbox-swiped-left-drop.xml" | tee -a "$OUT/steps.txt"; exit 1; }
log "   the ring went $RING -> $((RING + 1))"
P=$(centre "Quizzes beat rereading: recall builds memory") || exit 1; set -- $P
# Inside the 320x640 screen (an 800-px swipe ended at y < 0 and was lost once, CI 40635928160); one retry if the
# card is still there (a lost swipe sorts nothing, so it cannot sort twice).
UP=$(( $2 - 400 )); [ "$UP" -lt 12 ] && UP=12
$T adb shell input swipe "$1" "$2" "$1" "$UP" 200; sleep 1.5
if P=$(centre "Quizzes beat rereading: recall builds memory"); then
  log "   the swipe up was lost; once more"; set -- $P; UP=$(( $2 - 400 )); [ "$UP" -lt 12 ] && UP=12
  $T adb shell input swipe "$1" "$2" "$1" "$UP" 200
fi
shot inbox-swiped-up-later 1.5
P=$(centre "A meme about Mondays") || exit 1; set -- $P
$T adb shell input swipe "$1" "$2" $(( $1 + 700 )) "$2" 250; shot inbox-swiped-right-ai-drop 1.5
tap "AI pick: Action" && shot inbox-action-form 2
tap "Add to plan" && shot inbox-action-sent 1.5
tap "AI pick: Note" && shot inbox-note-form 2
tap "Save note" && shot inbox-note-sent 1.5
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

log "-- Habits (Phase 3): by part of the day; a tick reaches pagel.uz; a reminder; the notification's Done ticks another"
tap Habits && shot habits 2.5
for want in "Stretch for ten minutes" "Read 10 pages" "Drink water"; do
  grep -q "text=\"$want\"" "$OUT/$(printf '%02d' $N)-habits.xml" || { log "WALKTHROUGH: Habits has no \"$want\""; exit 1; }
done
tap "Done: Drink water" && shot habit-ticked 0.4
shot habit-done-today 2.5
grep -q 'text="Done today ✓"' "$OUT/$(printf '%02d' $N)-habit-done-today.xml" || { log "WALKTHROUGH: the ticked habit did not move to Done today"; exit 1; }
TODAY=$(TZ=Asia/Tashkent date +%Y-%m-%d)
H3="PATCH /api/v1/tasks/t_h3 {\"done\":true,\"day\":\"$TODAY\",\"if_due\":\"$TODAY\"} auth=True"
for i in $(seq 1 30); do grep -qF "$H3" "$FAKE_LOG" && break; sleep 1; done
grep -qF "$H3" "$FAKE_LOG" || { log "WALKTHROUGH: the habit tick never reached pagel.uz as $H3"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
log "   habit tick reached pagel.uz after ~${i} s"
# Exact alarms are a special grant Boss gives once in Settings; the emulator is given it here so the sheet stays open.
$T adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow
ALARMS_BEFORE=$($T adb shell dumpsys alarm | grep -c "org.pagel.ai.HABIT_FIRE")
tap "Reminder: Stretch for ten minutes" && shot reminder-sheet 2.5
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
for want in 'text="Overdue"' 'text="Send the grant form"' 'text="Pay the internet bill"' 'content-desc="Move all to today"'; do
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
tap "Move all to today" && shot upcoming-moved-all 1.5
for id in t_u2 t_u1; do
  WANT="PATCH /api/v1/tasks/$id {\"due\":\"$TODAY\"} auth=True"
  for i in $(seq 1 30); do grep -qF "$WANT" "$FAKE_LOG" && break; sleep 1; done
  grep -qF "$WANT" "$FAKE_LOG" || { log "WALKTHROUGH: Move all to today never reached pagel.uz as $WANT"; grep PATCH "$FAKE_LOG" | tail -5 | tee -a "$OUT/steps.txt"; exit 1; }
done
log "   pagel.uz got both overdue tasks moved to $TODAY"
grep -q 'text="Overdue"' "$OUT/$(printf '%02d' $N)-upcoming-moved-all.xml" && { log "WALKTHROUGH: Overdue still shows after Move all to today"; exit 1; }
log "-- Upcoming in landscape and at 1.5x on the 320 dp screen (checker P4 #3: only the strip is pinned now)"
$T adb shell settings put system accelerometer_rotation 0
$T adb shell settings put system user_rotation 1; sleep 3; front upcoming-landscape; shot upcoming-landscape 1
grep -qF "content-desc=\"$TODAY_SPOKEN" "$OUT/$(printf '%02d' $N)-upcoming-landscape.xml" || { log "WALKTHROUGH: Upcoming's strip is gone in landscape"; exit 1; }
$T adb shell input swipe 320 240 320 90 300; shot upcoming-landscape-scrolled 1
$T adb shell settings put system user_rotation 0; sleep 3; front upcoming-portrait
$T adb shell settings put system font_scale 1.5; sleep 3; front upcoming-font-1.5; shot upcoming-font-1.5 1
grep -qF "content-desc=\"$TODAY_SPOKEN" "$OUT/$(printf '%02d' $N)-upcoming-font-1.5.xml" || { log "WALKTHROUGH: Upcoming's strip is gone at 1.5x"; exit 1; }
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
# Back closes the panel, then leaves Completed for More (its bar), where the walk goes on.
$T adb shell input keyevent 4; sleep 1; $T adb shell input keyevent 4; sleep 1
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
log "WALKTHROUGH DONE: $N steps"

# ── Phase 5 · Rituals ─────────────────────────────────────────────────────────────────────────────────────────────
log "== Phase 5: Empty your head (3 lines -> 3 tasks on pagel.uz), the evening reminder opens Close the day, keep/later/drop + a pick -> the shutdown, the weekly review"
# Tap $1; if it is not on screen, scroll the list up a little and try again (twice).
tap_scroll() {
  for k in 1 2 3; do
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
for want in 'content-desc="Empty your head"' 'content-desc="Close the day"' 'content-desc="Weekly review is due"'; do
  grep -qF "$want" "$OUT/$(printf '%02d' $N)-p5-today.xml" || { log "WALKTHROUGH: Today has no ritual pill $want"; exit 1; }
done

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
for want in '{"project":"humopedia","text":"Call mom about sunday","priority":1}' '{"project":"me","text":"Fix the login bug","priority":1}' '{"project":"ababil","text":"Learn the guitar","priority":3}'; do
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
if ! tap "Close the day"; then
  log "   (not tappable in the shade; opening it the way its intent does)"
  $T adb shell cmd statusbar collapse
  $T adb shell am start -n "$PKG/.MainActivity" --es pagel.open shutdown > /dev/null
fi
front p5-shutdown
shot p5-shutdown 2.5
grep -q 'content-desc="Star 1 of' "$OUT/$(printf '%02d' $N)-p5-shutdown.xml" || { log "WALKTHROUGH: the notification did not open Close the day on its first star"; exit 1; }

log "-- Close the day: Tomorrow, Tomorrow, Later, Drop; pick one more; close"
tap "Tomorrow: Call the bank about the report" && shot p5-star-1 1.2 || exit 1
tap 'Tomorrow: Measure the drone[^"]*' && shot p5-star-2 1.2 || exit 1
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
$T adb shell input keyevent 4; front p5-today-closed; shot p5-today-closed 2
grep -q 'content-desc="Close the day: closed tonight"' "$OUT/$(printf '%02d' $N)-p5-today-closed.xml" || { log "WALKTHROUGH: Today does not show the day as closed"; exit 1; }

log "-- the weekly review: 4 steps, Finish -> the review event"
tap "Weekly review is due" && shot p5-review 2.5 || exit 1
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
