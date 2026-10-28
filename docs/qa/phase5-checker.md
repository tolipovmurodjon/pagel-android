# Phase 5 checker · Rituals (CI 40660702239, 66c14c4, feat/phase5-rituals) · score 7.5/10

**Why 7.5:** the three rituals are real, offline-first and proven end to end on the emulator (3 lines → 3 tasks, later/drop/pick
→ PATCHes + the shutdown event, the review event, the evening alarm in `dumpsys alarm`). The glass, toasts and words are good.
But one data bug returns stale AI rows. A double tap can duplicate tasks and events. The pick cap goes against Boss's own web
decision. And the three pills crowd Today on the 320 dp phone.

Evidence: `C:\Users\Murodjon\AppData\Local\Temp\claude\D--10-projects-pagel\22fc5724-653d-478c-adf2-75e5e8a3f532\scratchpad\checker-p5\`
(`shots\build\outputs\roborazzi\*.png` = 360×780 dp, `smoke\walk\*.png` = the 320×640 emulator, `smoke\fake-pagel.log`,
`smoke\walk\steps.txt`). Server check (read-only): e21b07e **is deployed** (`merge-base --is-ancestor e21b07e HEAD` in
`~/work/pagel/dashboard` = yes). Skills applied: apple-design, emil-design-eng. Path to 9 = fixes 1-5.

## Top 10 fixes (screenshot → what → fix file:line → rule)
1. **"Edit text" → change the dump → "Turn into steps" returns the OLD rows.** `backToText` keeps the sweep id
   (`RitualsRepository.kt:220-224`). `saveDraft` keeps it too, because the state is draft again (`:200-206`). So `split` posts
   `{id: same, dump: new}`. pagel.uz answers from its 24 h cache by id (`ritual-api.ts apiSweep`: `gate.done.get(id)` → `again:true`).
   Case: Boss dumps "call mom", reads the rows, taps Edit text, adds "pay rent", and gets back only "Call mom".
   The fake pagel.uz has no cache, so CI cannot see it. Fix: a fresh id for each split. Either `split()` writes a new row id
   (`sw_` + new uuid, then delete the old draft), or the server keys `done` by `id + sha1(dump)`. Add a test: back → edit →
   split → the new rows. Rule: honest state.
2. **A double tap makes the work twice.** `MintButton` fires on every tap (`RitualKit.kt:72`). `SweepViewModel.add`
   (`RitualsViewModels.kt:134`), `ShutdownViewModel.close` (`:218`) and `ReviewViewModel.finish` (`:297`) have no in-flight
   guard. `repo.add` reads READY, then writes ADDED only after N `today.add` calls (`RitualsRepository.kt:238-247`), so a
   second coroutine also reads READY.
   Result: two taps 80 ms apart → 6 `POST /api/v1/tasks` instead of 3. A double "Close the day" → 2 shutdown events
   (each has a new `sd_` id, so the server keeps both) plus a second set of drops. "Finish review" → duplicate next-step tasks.
   Fix: a `busy` flag or `Mutex.tryLock()` in each VM. Also make `add` one compare-and-set
   (`UPDATE sweeps SET state='adding' WHERE id=? AND state='ready'` → 0 rows = return). Add a test that calls `add()` twice
   at once and expects 3 POSTs. Rule: a retry or repeat never duplicates tasks or events.
3. **The pick cap goes against Boss's own web rule.** The web says: "No cap here: Today deals the stars 3 at a time (Boss
   2026-10-27: 'Pick not working… keep my current ones')" (`shutdown-client.tsx:8-9`, `review.ts shutdown` "No cap").
   The phone has `PICK_CAP = 3` (`RitualLogic.kt:33`), and `PickRow` turns off at 3 (`ShutdownScreen.kt:243`, the star at
   25 % ink = 1.7:1). A 4th tap does nothing, which is exactly the "Pick not working" Boss reported.
   **shutdown-picks-360.png / walk/75:** "Pick up to 3". Fix: remove the cap and keep "Today shows them 3 at a time".
   The phase brief asked for a cap, so the lead must choose, or ask Boss. Rule: parity with Boss's decisions.
4. **The pills crowd Today.**
   - walk/64, 69, 78 (320 dp): the 3 pills stack into 3 rows (~150 dp). The list starts at y≈275, and the FAB sits on the
     3rd star and "+1".
   - walk/61 (1.5×): the 3rd star is under the bar.
   - walk/59 (landscape): only 1.5 stars show above the bar.
   - today-pills-360.png: 2 rows (~110 dp) even at 360 dp.
   The web's Today has none of this. It shows only a "Weekly review is due" note (`today/page.tsx:41`), and "Close the day" in
   the all-clear card (`today/focus.tsx:126`). "Close the day" at 09:20 (walk/64) has no purpose.
   Fix (`RitualPills.kt:208-214`, `TodayScreen.kt:143`): one row that never wraps. Show the review pill only when it is due.
   Show "Close the day" from 18:00 Tashkent, and in the AllDone card. Move "Empty your head" to a 48 dp sparkle icon beside
   the title (More keeps all three). Also the review pill pops in after `GET /api/v1/rituals` and pushes the list down
   (the walk had to look twice). Reserve its slot, or fade it in without moving the stars. Rule: Apple §16 purpose and
   simplicity; spatial consistency.
5. **The evening notification is bundled with the anchor notification.** walk/70 shows the "PAGEL 2" group: "Close the day 4
   stars still open. Fi…". A tap there opened the anchor's intent (CI 40657424080). The walk now sends the intent itself
   (walkthrough.sh, p5 block), so **CI no longer proves the tap**.
   The cause: the channel is IMPORTANCE_DEFAULT (`ShutdownAlarmReceiver.kt:71`). The habit channel is HIGH
   (`HabitAlarmReceiver.kt:84`), and walk/40 shows the habit notification standing alone. Samsung bundles these the same way.
   Fix: IMPORTANCE_HIGH + `setGroup("rituals")` + `BigTextStyle`. Then put back a real tap on the notification row in the walk.
6. **Words that wrap, shrink or touch an edge (1.5× and 320 dp):**
   - shutdown-picks-font-1.5.png: "Close the day" wraps to 2 lines and touches the button's top edge. walk/75 shows the same
     wrap at 1.0×.
   - walk/68: "Empty again" and "Pick today's 3" each wrap to 2 lines at 1.0×.
   - shutdown-star-*.png, walk/72: "Tomorrow" auto-shrinks smaller than "Later"/"Drop", even at 1.0×.
   - review-inbox-font-1.5.png: the step names shrink to 8 sp (`RitualKit.kt:162`), so "Next steps" shows at ~1× next to
     1.5× words. That ignores Boss's font setting.
   Fix: shorter words. Use "Close" + moon, "Again", "Today's 3". Give the three moves one shared size (or "Keep · Later ·
   Drop"). Name the steps "Inbox · Head · Steps · Someday" with `minFontSize` ≥ 11 sp. Rule: short words; 1.5× without
   clipping.
7. **A picked star fails contrast.** #EB8909 on the pane = **2.44:1** (marks need 3:1), and it is the only on/off mark of a pick
   (`ShutdownScreen.kt:251`). Fix: #C2410C (the streak orange, ~5:1), or keep an ink outline on the filled star.
8. **The evening reminder: its time, and silence when notifications are off.**
   - The default is 21:30 (`RitualLogic.kt:42`, `Entities.kt RitualStateEntity.eveningAt`). That is the same minute as the
     21:30 habit reminders (walk/34). Boss's brain index (me/body-tracking) says "day close 20:45, wind down 21:30".
     → Ask Boss; 20:45 looks like his time.
   - `EveningRow` (`ShutdownScreen.kt:258-290`) has no "Notifications off · Turn on" row. The receiver also returns silently
     (`ShutdownAlarmReceiver.kt:69`). This is the same dead end Phase 3 #6 fixed for Habits: reuse `FixRow`.
   - The switch writes, and re-plans the alarm, on every wheel tick (`ShutdownScreen.kt:101`, `setEveningAt`). Save when the
     wheel settles.
9. **Wayfinding and one main button per view.**
   - review-inbox-font-1.5.png, walk/79: "Open the Inbox" and "Next" are both mint slabs (`ReviewScreen.kt:129`, `:108`).
     Make "Open the Inbox" a QuietButton.
   - shutdown-done.png, walk/77: no way back to Today, and "nothing needs you tonight" twice (head `:128`, card `:134`).
     When done, hide the head line and add "Go to Today", as sweep and review do.
   - Review step 1 leaves the review with no way back (known).
10. **Smaller data and parity edges:**
   - A 404 is `Refused` (`PagelApi.kt:178`). On an older or rolled-back pagel.uz, a dump goes FAILED "pagel.uz refused (404)"
     and a ritual is marked refused (`RitualsRepository.kt:363, 382`). START HERE v13 says "the dump waits".
     → Treat 404 on these two calls as TRY_LATER.
   - The review's next-step field has no 20-word check (`ReviewScreen.kt:172`). The sweep has one. The server refuses a long
     step after Finish (red toast).
   - The Close the day head uses `LocalDate.now` (`ShutdownScreen.kt:123`). At 01:30 it shows Thursday while it closes
     Wednesday: use the ritual day.
   - Today's pills have no 05:00 tick (`RitualsViewModels.kt:62`), so "Day closed" stays until something else emits.
   - `undoNewTask` finds a sent task by project + words (`RitualsDao.adoptedId`). A twin task with the same words can be
     deleted instead.

## A) Rituals check
- **Parity ✅ mostly:** the same trigger list and "Next prompt", Do/Someday/Drop with P1/P3 (= saveRows), shutdown
  keep/push/drop = `star:false` / remove, picks = `star:true`, candidates = the web's `next` (NOW phase, not starred, P<3,
  each project's next action first, 12). The review's 4 steps and texts match. The phone also has a project picker per row,
  ✕, and the 20-word guard.
- **Close the day choices ✅:** one card per star, Back, Tomorrow is bright. walk/72-77 + fake log: `t_ci3 {star:false}`,
  `t_ci5 {remove:true}`, `t_u2 {star:true}`, then `{"action":"shutdown",…"kept":2,"pushed":1,"dropped":1,"picked":1}`.
  ❌ the cap (#3).
- **Evening alarm ✅:** 21:30 Tashkent (`Rituals.nextEvening`). It rides on `HabitAlarms.plan`, so boot, update and grant
  re-plan it. The receiver asks pagel.uz first (5 s inside a 9 s goAsync) and skips when the day is closed. It re-plans in
  `finally`. The walk shows "evening alarms on AlarmManager: 1". ❌ #5, #8.
- **Sweep offline + AI wait ✅:** no spinner. sweep-waiting-offline.png: the dump, a skeleton row per line, one short line,
  "Edit text". The dump goes after the ticks (`OutboxSender`), and a 503 = LATER never holds a tick. A refused dump is never
  retried by itself. ❌ #1.
- **Undo ✅:** Add N → a waiting op is taken back, a sent task is deleted. Close/Review undo puts back stars, drops (undoDelete)
  and adds, and takes the event back while it waits. Once sent, the event stays (documented).
- **Retry safety:** the ritual id is stable in the outbox body, and the server answers a repeat with `already` ✅. The sweep id
  is stable ✅, which also causes #1. ⚠️ `POST /api/v1/tasks` has no client id (`TodayRepository.sendAdd`): a lost reply
  plus a retry = a duplicate task. This is older code, but "Add N" now sends N at once on the subway. Add an idempotency id.
- **Today's pills:** they crowd Today (#4).

## B) What will fight the "Boss's notes" round (feat/native-app 6a29931, a713747, b02893d, and the next round)
- `git merge-tree origin/feat/native-app 66c14c4`: the **only text conflict is docs/native-progress.md**, because both
  branches add "START HERE v13". Rename Phase 5's block to v14.
- **TodayScreen.kt:** Phase 5 adds the pills item right after `item("head")` and `RitualPillsViewModel` to `TodayRoute`.
  The next round edits Today's header, so these are the same lines. Decide #4 first, then merge.
- **GlassStage cold start (b02893d):** the pills are 3 more GlassChips in Today's first frame. `RitualPillsViewModel.init`
  also starts a `GET /api/v1/rituals` while the cold sync runs. b02893d fails the smoke on a render frame ≥ 4 s, and the two
  were never measured together. Gate the pills on `GlassStage.allows(PANES)`, or start the refresh after the first frame.
- **AppShell.kt** auto-merges. Check by hand: `goToday()` and the `OpenRequests` LaunchedEffect now sit inside the
  `AnimatedContent(kind)` Tabs. The pending StateFlow waits, so a cold start from the notification should work: prove it in
  the walk (#5). The ritual screens get native-app's toast at bottom 16 dp (`BottomActions`, onTop = false).
- **More:** Phase 5 adds 3 rows + `verticalScroll` (`AppShell.kt More`). If the round reworks More, keep one scroll. Weekly
  review and Upcoming share the orange calendar tile (#D9620B), so they look alike: give the review rose (the web's tone).
- **walkthrough.sh** auto-merges. The p5 block runs after the new Inbox map and the relaunch. It assumes `t_ci3/t_ci5/t_u2`
  are the open stars and the "Weigh in" card exists. Hold = Do now sends nothing, so the counts should hold. Re-run once
  after the merge. **fake-pagel.py:** the p5 block sits before the last line. native-app did not change the file: no clash.

## C) Design audit
- **Glass ✅:** the panes are white glass with a mint foot and rim (sweep-rows-360.png, shutdown-star-360.png). The
  Quiet/Move buttons are white 72 % with a rim. MintButton is bright mint (Aurora2 → Aurora1) with ink text, not a dull slab.
  ⚠️ More's "Empty your head" tile is #1C7E69 (walk/42).
- **No spinners or "Syncing" ✅:** skeletons only while loading or waiting for the AI.
- **Toasts ✅:** light glass + icon ("3 added · Undo", "Day closed · Undo", "Review done · Undo": walk/68, 77, 82).
- **Contrast (sRGB):**
  | Pair | Ratio |
  |---|---|
  | Ink on Aurora1 (darkest end of the mint button) | 10.6 ✅ |
  | Muted on a pane | 5.7 ✅ |
  | Risk "Drop" | 6.4 ✅ |
  | 21:30 label (#0B5C45 on #E3F4EC) | 7.0 ✅ |
  | Moon #5B4BC4 | 6.5 ✅ |
  | Amber review icon | 3.5 ✅ |
  | Unpicked star | 3.9 ✅ |
  | **Picked star** | **2.44 ❌** (#7) |
  | Step dot rim | 1.5 (the number inside carries it) |
- **Targets ✅:** MintButton/QuietButton 52 dp, MoveButton 64 dp, PickRow 52 dp + a 48 dp star box, Segmented 48 dp, step
  dots 48 dp, the evening switch and time 48 dp.
- **Departure Mono ✅** only on numbers: the step dots, "21:30" and the wheel. "1 of 3" is now in the body face.
- **Motion:** star cards and review steps slide in the direction of travel (enter damping .9, exit 1.0). Segmented lift and
  switch at damping 1. Haptics: Confirm on main buttons, SegmentTick on choices, ToggleOn/Off on picks.
  ⚠️ The enter spring at .9 overshoots on a tap (not a fling): use 1.0, as the Phase 3 bar fix did.
  ⚠️ A removed sweep row (✕) disappears with no motion and no Undo.
- **1.5× / 360 / landscape:** see #6 and #4. No Phase 5 landscape shot exists: add `shutdown`/`sweep` in landscape.

## Parity gaps vs the web (not bugs)
- Pick cap (#3). Pills on Today (#4). The web's sweep says "Save N"; the phone says "Add N" (clearer, keep it).
- The web's shutdown shows every star at once with a segmented choice; the phone uses one card at a time (better on a phone,
  and Back works).
- Review step 1: the web links /learn and the browser's Back returns; the phone's tab switch does not come back (known).
- The rituals' state refreshes only when a ritual or Today opens; the web renders per request.
- The evening reminder is phone only. The web's /gtd loop page (Close the day ✓, review "Nd ago") has no phone screen.

## Phase 3 checker re-check (`docs/qa/phase3-checker.md`)
| # | Item | Now | Proof |
|---|---|---|---|
| 1 | Late habit stays late | ✅ `Repeat.nextAfter` on the tick path, server `nextAfter` too | `HabitsRepository.kt:68`, `repeat.ts:59`, `task-store.ts:101`, `plans.ts:151` |
| 2 | Offline tick after midnight | ✅ `{done, day, if_due}`; the server checks `if_due` | fake-pagel.log:38-39, `task-api.ts:65-71` |
| 3 | Rings / ticks twice when done | ✅ both check today's tick | `HabitsRepository.kt:66`, `HabitAlarmReceiver.kt:72-74` |
| 4 | AI-read ask never stops | ✅ only while the Inbox is visible, `ReadAsks.MAX` | `InboxViewModel.kt:172-186` |
| 5 | Half state on tick | ✅ one transaction; walk/35 says "1 of 3", no copy in Coming up | `PagelDao.kt:300 tickHabit`, walk/35-36 |
| 6 | Notifications denied = silent | ✅ red "Notifications off · Turn on" row | habits-reminder-notifications-off.png, `HabitsScreen.kt:394, 437` |
| 7 | Alarm unproven in CI | ✅ alarm-count "0 -> 1", asserted | walk/alarm-count.txt, steps.txt |
| 8 | 60-day streak + refresh race | ✅ `DAYS = 400`; the GET start is stamped | `HabitsRepository.kt:111, 135` |
| 9 | Green and glass family | ✅ no #058527 in the UI (only confetti); bright-mint switch; glass "AI is reading this one"; X logo. ⚠️ Action/Note #1C7E69 slabs (Boss's open question; walk/28, 30) | inbox-ai-reading.png, walk/39 |
| 10 | Motion and words | ✅ `animateItem` on panes, bar damping 1.0, lucide bell + BigText "Time for it", "Mondays", "Noted ✓"; icons scale at 1.5× | walk/40, walk/32, habits-360-font-1.5.png |

## Broken or clipped states
- shutdown-picks-font-1.5.png: "Close the day" wraps and touches the top edge of the button (#6).
- walk/72, shutdown-star-*.png: "Tomorrow" is smaller than its siblings (#6).
- review-inbox-font-1.5.png: the step names have mixed sizes, down to 8 sp (#6).
- walk/68, walk/75: two-line button labels at 1.0× on 320 dp (#6).
- walk/61: the 3rd star is under the bar at 1.5×. walk/59: the bar sits on row 2 in landscape. walk/64: the FAB covers "+1" (#4).
- walk/70: the notification is bundled, and its line is cut "…Fi…" (#5).
- walk/69: Today says "Offline" right after the sweep. The stream dropped during the 10-30 s `/sweep` call. Check that the
  slow call cannot starve the live stream on the shared OkHttp pool (`PagelApi.kt slowHttp`).
- walk/50 (Phase 4): the strip says "We" for Wed at 1.5×. walk/62 "dark" renders light (unchanged from earlier phases).
- sweep-rows-360.png: the PAGEL chip runs to within ~4 dp of the pane rim (FlowRow end padding 4 dp, `SweepScreen.kt:243`).
