# Phase 3 checker · Habits + reminders (CI 40611711266, 2bd1539, app-v0.7.0) · score 7.5/10

**Why 7.5:** the screen matches the web, and so do the glass, the 1.5× layout and the tick's Room-first speed. All 10 Phase 2
fixes landed. But the tick logic has three real day bugs: a late habit stays late forever, an offline tick after midnight
counts for the wrong day, and a reminder rings for a habit that is already done. The AI-read ask also loops with no end.

Evidence: `C:\Users\Murodjon\AppData\Local\Temp\claude\D--10-projects-pagel\22fc5724-653d-478c-adf2-75e5e8a3f532\scratchpad\checker-p3\`
(`shots\build\outputs\roborazzi\*.png` = 360×780 dp; `smoke\walk\*.png` = the 320×640 dp emulator; `smoke\fake-pagel.log`,
`smoke\walk\steps.txt`, `alarm-count.txt`, `gfxinfo.txt`). Skills applied: apple-design, emil-design-eng. Path to 9 = fixes 1-6.

## Top 10 fixes (screenshot → what is wrong → fix at file:line → rule)
1. **A missed habit stays "late" every day after one miss** (shared with the web). `nextDue` steps
   `while (d < today)` (`Repeat.kt:109`, `repeat.ts:52`), so a tick of a late habit lands on **today**, not the next day.
   The test even asserts it: `HabitsLogicTest.kt:18` `nextDue("2026-10-25","day","2026-10-28") == "2026-10-28"`.
   Case: "Drink water" (daily) is missed on Tuesday and ticked on Wednesday at 08:00. It is now due Wednesday. On Thursday it
   shows "Wednesday 28 Oct" in red, and every day after that, until it is ticked twice in one day.
   Side effect: a bell time change on it sends `PATCH {due: today …}`. The server logs that as `habit_undone`
   (`task-store.ts:113`), so today's tick is erased on pagel.uz.
   Fix: the tick paths (`HabitsRepository.kt:60`, `task-store.ts:101`, `plans.ts:151`) use a `nextAfter` that steps while
   `d <= today`. That matches the code comment "from today if it was late". Keep `datesBetween` on the old rule.
   Change both sides in one go (vitest + DEPLOY NEEDED). Rule: parity, and honest state.
2. **An offline tick that is sent after midnight counts for the wrong day.** The server takes *its own* today at receipt
   (`task-api.ts:61` → `task-store.ts:101 {day: today}`), and also uses it in `nextDue`.
   Case: Boss ticks at 23:50 on Tuesday on the subway; the tick is sent at 00:10 on Wednesday. `habit_done` is logged for
   **Wednesday**, so after the next refresh the phone shows Wednesday as "Done today" before he has done it. Tuesday is
   missing, so the streak drops to 1. A second case: the web ticked first while the phone was offline, so the server moves
   the habit twice.
   Fix: the phone sends `{"done":true,"day":"<tick day>","if_due":"<old due>"}` (`HabitsRepository.kt:64`). The server
   accepts `day` ∈ {today, yesterday} as both the event day and nextDue's today, and skips the move when `due != if_due`
   (`task-api.ts:33,61`). Rule: offline through the outbox, with order and meaning kept.
3. **A reminder rings for a habit that is already ticked, and Done ticks it twice.** `HabitAlarmReceiver.kt:161` checks
   only `due ≤ today`, not today's tick. `HabitsRepository.kt:58` has no "already ticked today" guard, even though its own
   doc (`:53`) and START HERE v10 claim one.
   Case (with #1): a late habit ticked at 10:00 is due today at 20:00. At 20:00 Boss gets "Read 10 pages · 20:00" for a
   done habit. A tap on Done sends a 2nd `PATCH done`, and pagel.uz moves the habit again.
   Fix: both places check `dao.habitTicksOf(id).any { it.day == today }`, plus a unit test. Rule: honest state.
4. **The AI-read ask never stops.** `InboxViewModel.kt:161-168` runs `while (true) { askToRead(); delay(15 s) }` for as long
   as one of the next 5 cards has no pick. It posts at most once a minute, but the ViewModel stays alive in the background
   (AnchorService keeps the process). A save the AI cannot read never becomes `guessed`.
   Result: up to **1,440 Claude haiku runs a day** on the box that lexion.uz shares (`inbox-api.ts:70` lets one through
   each minute).
   Fix: ask only while the Inbox is on screen (`repeatOnLifecycle(STARTED)`). Stop after 3 asks that bring no new pick.
   On the server, skip files that were tried in the last 10 min. Rule: never load the shared server on a timer.
5. **walk/35-habit-ticked.png:** while its ring fills, "Drink water" also shows under "Coming up · Thu 29 Oct", and the header
   still says "0 of 3". The tick is two writes with no transaction (`HabitsRepository.kt:61-62`: `upsertTasks`, then
   `addHabitTick`), so Room emits the half state. Also, `coming` does not leave out `finishing` (`HabitsView.kt:197`).
   Fix: one `@Transaction` DAO method for date + tick (+ outbox), and `it.id !in finishing` in `coming`.
   Rule: a tap answers right, in one frame.
6. **Notifications denied = silent reminders.** The permission callback calls `askExact()` whatever the answer
   (`HabitsScreen.kt:128`), and `post()` returns silently (`HabitAlarmReceiver.kt:169`). The sheet still says "At 07:30 on
   the days it repeats, with a Done button" (`HabitsScreen.kt:394`).
   **habits-reminder-font-1.5.png:** the red exact-alarm line takes 3 lines and Boss cannot act on it.
   Fix: check `NotificationManagerCompat.areNotificationsEnabled()` and the channel importance. When off, show a red row
   with an icon, "Notifications off · Turn on", that opens `ACTION_APP_NOTIFICATION_SETTINGS`. Make the exact line a
   48 dp row, "Exact time · Allow", that opens `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. Rule: no dead ends; short words.
7. **CI does not prove that an alarm is set.** `walk/alarm-count.txt` = **0** (steps.txt "alarms set for t_h1: 0"), and the
   walk does not assert it (`walkthrough.sh:173`). The notification was forced by an adb broadcast (`walkthrough.sh:176`),
   so on-device scheduling is untested. The grep looks for the data URI, which `dumpsys alarm` may not print.
   **walk/38-reminder-on.png** also says "Alarms & reminders is off" after `appops … allow` (`walkthrough.sh:168`): `exact`
   is read only on resume (`HabitsScreen.kt:117-121`).
   Fix: assert `dumpsys alarm | grep -c "org.pagel.ai"` ≥ 1 with the trigger time, and read `canExact` again when the sheet
   opens. Also: `setExactAndAllowWhileIdle` can throw SecurityException after a revoke, and the one `runCatching` around
   `plan()` (`HabitAlarms.kt:60`) then drops every later habit. Wrap each alarm and fall back to `setWindow`.
8. **Streaks are capped at about 60 days on the phone** (the web counts back 400 days). `DAYS = 60`
   (`HabitsRepository.kt:115`), and `replaceHabitTicks` deletes everything older (`PagelDao.kt:260-268`). A 75-day streak
   reads about 60 on the phone and 75 on the web, from late December for Boss's October habits.
   Fix: `days=400` (the rows are tiny), or use the server's `streak` (`habits-api.ts:44`; the phone ignores it).
   **Same race as Inbox #3:** `refresh()` runs only at ViewModel start (`HabitsViewModel.kt:80`). A GET that left before a
   PATCH landed drops the tick (the outbox row is gone), and the habit sits in "Coming up" until the app restarts.
   Fix: stamp the GET's start time and keep the local ticks made after it.
9. **Green and glass family:**
   - The Done-today discs use #058527 (`HabitsScreen.kt:325`), a 4th green outside the palette. The switch track is solid
     #1C7E69 (`HabitsScreen.kt:406`, habits-reminder.png). Use bright mint (Aurora1 → Primary) or ink, per Boss's
     "white, ink or bright mint".
   - **inbox-ai-reading.png:** "The AI is reading it now…" is a grey dashed box that reads as a disabled empty slot
     (`InboxScreen.kt:~615-620`). Make it a glass pill with a sparkle that breathes: "AI is reading this one".
   - The X source pill "✕ X" (inbox-ai-reading.png, walk/26) reads as a close button. Use the X logo.
   - Action/Note #1C7E69 slabs (walk/28, walk/30, inbox-form-action.png): this is still Boss's open question.
10. **Motion and words:**
    - A ticked habit jumps from its part pane to "Done today". Each pane is one LazyColumn item and nothing animates
      between them (`HabitsScreen.kt:203-206`). apple-design §7 (spatial consistency): collapse the row's height with a
      fade (`AnimatedVisibility`), or key per row with `animateItem`.
    - The day bar spring has damping .8 (`HabitsScreen.kt:231`), so it overshoots on a tap. Not a fling (apple §4) → 1.0.
    - The notification uses the system `ic_popup_reminder` icon (`HabitAlarmReceiver.kt:183`) and its title is cut
      ("Stretch for ten min…", walk/40). Use a monochrome lucide bell and `BigTextStyle`.
    - "Every monday" is lowercase (`Repeat.kt:46`, `repeat.ts` repeatLabel). Write "Mondays".
    - The toast "Noted. It is in Notebook → Saved notes." still takes 2 lines (walk/32). Write "Noted ✓".

## Parity gaps vs the web /habits (not bugs)
- **Matches:** parts by time (before 12 / before 17 / later / none) with the web's icons; time order then priority; 🔥 streak
  by the Duolingo rule (`HabitsView.kt:170` = `habit-log.ts streakOf`); "N of M done today" + the bar; "Done today ✓" with
  🔥 > 1; "Coming up" with "EEE d MMM HH:mm"; the AllDone card's words, once a day per device; tap → task panel.
  The repeat label is better than the web's raw "↻ tue,thu,sun".
- After a late tick (#1), the web shows the habit both in its part and in Done today. The phone hides it in its part.
- The web's Done list comes from the log (it keeps habits renamed or removed today). The phone lists only open habits.
- Streaks > 60 days (#8).
- Midnight: the web renders per request. The phone keeps yesterday's day until a flow emits (`HabitsViewModel.kt:67`).
  Add a midnight tick (Tashkent).
- Reminders, Done-from-notification, offline: phone only (the web has none). An Anytime habit has no bell (it has no time),
  and the sheet cannot give it one.
- Deferred by the builder: snooze, reminder offsets, a history calendar. The Inbox gaps from Phase 2 are unchanged: filters,
  Shuffle, paste, recall, clear old, research of keeps.

## The tick (A)
- Room first ✅ `finishing` is set before the coroutine runs (`HabitsViewModel.kt:106`), with ring, ding and haptic at once
  (`HabitsScreen.kt:176-181`). Only the half state of #5 is wrong.
- Same repeat rule ✅ `Repeat.nextDue` is a step-for-step port of `repeat.ts nextDue` (month clamp, `month 1,last`,
  day sets, `N weeks`). The inputs can differ, though: (a) today at tick time vs at send time (#2); (b) the phone's due vs
  the server's (the web ticked first, or the time was edited on the web → a double move).
- Undo ✅ while waiting it is taken back (`HabitsRepository.kt:76`); after sending it is `PATCH {due: old}` → `habit_undone`.
  Edge: when the old due was null, the server logs "due", not `habit_undone` (`task-store.ts:113` needs `e.due`), and
  the tick stays on pagel.uz.
- Outbox and order ✅ fake-pagel.log:36,40 `PATCH t_h3 {done:true}`, then `t_h1 {done:true}`; `set` only goes out on the
  wire (`sentOf`). Offline ticks are proven only by unit tests (`undoWhileWaiting…`, `aRefusedTickRollsBack…`). The walk
  ticked online.

## Reminders (A)
- ✅ SCHEDULE_EXACT_ALARM, not USE_EXACT_ALARM (`AndroidManifest.xml:26`). When denied: `setWindow` 10 min
  (`HabitAlarms.kt:73-74`) and a red line. The grant broadcast replans (`AndroidManifest.xml:118` → `BootReceiver.kt:45`).
- ✅ Asked at the first "on" only: POST_NOTIFICATIONS, then exact once ever (`HabitsScreen.kt:127-135`,
  `HabitsViewModel.kt:135`). Never at launch.
- ✅ The receiver is not exported (`AndroidManifest.xml:124`). PendingIntents are explicit, FLAG_IMMUTABLE, with a unique
  data URI per habit; `goAsync` with a 9 s cap.
- ✅ Replanned after a tick, a switch, a time change, boot and an update. LOCKED_BOOT fails on CE storage, but the error is
  caught and BOOT follows. Time zone: Tashkent everywhere (`HabitsRepository.kt:114`, `ReminderMath`).
- ❌ #3 (rings when done), #6 (silent when denied), #7 (unproven in CI).
- ✅ **Salah:** 0 hits for salah|namoz|prayer|fajr|bomdod in `app/src`. The web's /habits has no salah link
  (`/api/salah` is a separate web page, untouched).

## Phase 2 checker top-10 re-check
| # | Item | Now | Proof |
|---|---|---|---|
| 1 | AI pick off-screen at 1.5× | ✅ the pick ends ~525 of 780 dp; picture hidden; caption 1 line | inbox-card-360-font-1.5.png |
| 2 | Toast over the AI pick | ✅ toast under the status bar; the pick is free (it covers the ring for 5 s) | walk/26, 28, 30, 32 |
| 3 | UTC day in the fake / ring race | ✅ ring 12→13→14→15→16→17, "the ring went 12 -> 13" | walk/25-32, steps.txt |
| 4 | Picture never retried | ⚠️ ib5 picture now fetched (3 GETs, was 2); but walk/31 says "With the picture" while walk/30's card shows none (the height budget hid it at 640 dp) | fake-pagel.log, walk/30-31 |
| 5 | Chips under the bar | ✅ one row of 5 icons + words above the bar | walk/25, 33 |
| 6 | No "Read all" when cut | ✅ shown whenever the caption ends in "…" | inbox-card-360.png, walk/25 |
| 7 | Header wraps | ✅ "5 learned ✓" and "2/5 learned" on one line, also at 1.5× | inbox-keeps-full.png, inbox-card-360-font-1.5.png |
| 8 | "214 left" + "Inbox zero" | ✅ cloud-off "214 more when online", no "left" | inbox-more-when-online.png, inbox-empty.png |
| 9 | Undo 34 dp; exit drops speed | ✅ `heightIn(TouchMin)` (`Toasts.kt:144`); critically damped spring with `initialVelocity` (`InboxScreen.kt:490`); motion seen in code only | walk/26 |
| 10 | Words / sub contrast / segment | ✅ sparkle + hint in full white, "5/5 learned · one line", chosen segment white + shadow, no "P1, this week" | inbox-keeps-full.png, inbox-form-action.png |
| gap | AI read of unread saves | ✅ works: `POST {action:"triage",files:["inbox/ib8.md"]}` ~15 s after the link came back; the server runs the web's `triage()` one at a time (`learn.ts:295`), at most 1 start a minute (`inbox-api.ts:70`). ❌ it never stops (#4) | fake-pagel.log:34, inbox-ai-reading.png |

## Broken or clipped states
- walk/35: the habit shows twice (filling row + Coming up), and the header says "0 of 3" (#5).
- walk/38: "Alarms & reminders is off" although the walk granted it (#7).
- walk/40: notification title cut, system icon (#10).
- walk/31: "With the picture" with no picture on the card (#4 above).
- inbox-guide.png: the pane is still cut flat at the sheet cap, with the Drop row hidden and no fade (`InboxSheets.kt:206`).
- habits-360-font-1.5.png: the clock/calendar icons stay 13 dp next to 1.5× text (`HabitsScreen.kt:296`). Scale them with
  the font. The meta wraps cleanly, with no clipping.
- walk/46-landscape.png: the bar sits on row 3 and the FAB overlaps the pane. Habits has no landscape shot at all.
- walk/48-font-1.5.png: the FAB covers the pane's corner and the "+1" chip (Today).

## Design audit (C)
- **Glass ✅** The habit panes are white glass with a rim and a mint foot (walk/34, habits-360.png), the same Pane as Today.
  Clouds show through.
- **Green ⚠️** See #9 (#058527 discs, solid switch, Action/Note slabs).
- **No spinners ✅** A skeleton only while the first sync is empty (`HabitsScreen.kt:188`). No "Syncing". The AI-reading box
  is static (#9).
- **Toasts ✅** Light glass + icon, at the top under the status bar (walk/26).
- **Contrast (sRGB):**
  - ✅ Text: Risk #B3261E on the pane ~6.5:1. Muted #5B6275 ~5.7:1. Streak #C2410C 5.2:1.
    White ✓ on #058527 4.8:1.
  - ⚠️ The switch's off track (Ink .16 on white) is ~1.4:1. The knob carries the shape, as on iOS.
  - ✅ The day bar's dark end passes 3:1.
- **Targets ✅** Bell 48 dp (`GlassChip.kt:111`), switch row 56 dp, Undo 48 dp. The Done and Coming rows are not tappable
  (the same as the web).
- **Type ✅** Departure Mono only on 🔥 numbers, the ring, the sheet's "07:30" and the wheel digits.
  Words are short, except #6's red line and #10's.
- **1.5× / 360 dp ✅** habits-360-font-1.5.png and habits-reminder-font-1.5.png: they wrap, with no clipping.
- **Motion:** ✅ tick ring + ding + confetti + haptic on the same beat; switch with a ToggleOn/Off haptic; wheel
  SegmentTick on settle. ❌ The row teleports to Done, and the bar overshoots (#10).
- **Frames:** gfxinfo shows 85.8 % janky, p50 150 ms. That is CI's software GPU, not a phone number.
  Measure on the A05s.
