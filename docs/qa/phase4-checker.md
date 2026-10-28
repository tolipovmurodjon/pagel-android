# Phase 4 checker · Upcoming · Completed · Overdue (CI 40628663644, 6914ecb, app-v0.8.0) · score 8.5/10

**Why 8.5:** Upcoming and Completed match the web and go past it: the week strip, a working drag, Move all, and Completed by
Tashkent day with untick. All 10 Phase 3 fixes landed, and each has a test or a shot. One real data bug is left: a *habit*
row can be dragged, and a drop rewrites the habit's whole series. Two layout risks are not covered by CI: a pinned header
in landscape, and the drag ghost going off the screen.

Evidence: `C:\Users\Murodjon\AppData\Local\Temp\claude\D--10-projects-pagel\22fc5724-653d-478c-adf2-75e5e8a3f532\scratchpad\checker-p4\`
(`shots\build\outputs\roborazzi\*.png` = 360×780 dp; `smoke\walk\*.png` = the 320×640 dp emulator; `smoke\fake-pagel.log`,
`smoke\walk\steps.txt`, `alarm-count.txt`). Unit tests: 365, 0 failures. Skills applied: apple-design, emil-design-eng.
Path to 9.5 = fixes 1-5.

## Top 10 fixes (screenshot → what is wrong → fix at file:line → rule)
1. **A dragged habit rewrites its series** (code, no shot). Every row gets the long-press drag (`UpcomingScreen.kt:376-383`),
   habits too. Case: "Gym" (tue,thu,sun, due Thu 18:00) is dragged from its Sunday row onto Monday. `movedTo` sets due =
   Mon 18:00, and `Repeat.datesBetween` (`Repeat.kt:130`) counts `due` itself. So Gym now shows on **Monday**, which is
   not one of its days, and **Thursday's gym is gone**. A second case: a habit ticked today (due tomorrow) is dropped on
   Today. pagel.uz logs `habit_undone` (`task-store.ts:114`, due ≤ today), so today's tick is erased on the web, while the
   phone still shows it in "Done today" until the next refresh.
   Fix: no drag on a habit row (`if (t.every.isNullOrBlank())` around the `pointerInput`), and `UpcomingRepository.reschedule`
   (`UpcomingRepository.kt:117`) returns null for `every != null`. Add a unit test. Rule: honest state; Habits owns habits.
2. **walk/45-upcoming-dragging.png:** the ghost is off the screen's left edge ("Review the lesson draft" is cut), and it
   covers the target day. Behind it, the ink "30" cell cannot be read.
   - The ghost is a title-only card placed at the row's origin minus the grab point (`UpcomingScreen.kt:169,175`). The
     title jumps ~48 dp left of where the row drew it (apple-design §2: content stays under the finger).
   - Fix (`Ghost`, `UpcomingScreen.kt:430-452`): keep x inside [8 dp, width − w − 8 dp]. Over the strip, scale the card to
     ~0.6 and lift it ~56 dp above the finger (iOS drag preview), so the ink cell shows. Draw the ring + title as the row does.
   - Rule: apple §2 (direct manipulation), §8 (show where it is going).
3. **The header + strip are pinned outside the list** (`UpcomingScreen.kt:204-209`). Together they take ~180 dp. In landscape
   (320 dp tall, walk/56 is Today only) that leaves about 70 dp of list above the bar. At 1.5× they take 27 % of the
   portrait screen (upcoming-360-font-1.5.png). There is no Upcoming or Completed shot in landscape or at 320 dp.
   Fix: make `Header` the first LazyColumn item and keep only the strip pinned. Add walk shots of Upcoming in landscape
   and at 1.5×. Rule: 360 dp, 1.5×, landscape.
4. **"21 planned" counts habit days, not tasks** (walk/43; walk/47 says "23 planned"). Boss has 4 dated tasks. Gym and
   the 3 dailies count once per day (`UpcomingScreen.kt:246`). The web shows no count. Fix: count non-habit rows only:
   "4 tasks · 2 late". Rule: never a made-up-looking number; short words.
5. **walk/49 → walk/50: an untick teleports.** "Renew the domain" and its whole "Today" pane vanish in one frame. A done
   in Upcoming folds for 1.25 s (`UpcomingViewModel.kt:88-97`). Completed has no `reopening` state
   (`UpcomingViewModel.kt:147-152`), and its rows inside a pane do not animate (`CompletedScreen.kt:111-114`).
   Fix: a `reopening` map like `finishing`: the disc empties (spring) and the row folds ~600 ms, then leaves. Rule:
   apple §7 (spatial consistency); emil: no jarring changes.
6. **A starred task moved to a later day stays on Today** (walk/52: "Call the bank · Friday 30 Oct" is still on Today).
   `placeOnToday` keeps any star (`TaskRepository.kt:203`). The web hides a star with a later date until its day
   (`views.ts:33`). In Phase 4 this is hit by every drag of a starred task onto a later day. Fix:
   `t.star && !(t.due != null && t.due.take(10) > today)`. Rule: parity.
7. **upcoming-360-font-1.5.png:** "Tomorrow · Thursday 29 / Oct" leaves "Oct" alone on line 2 (`UpcomingScreen.kt:343`,
   `DAY_TITLE` `:99`). Fix: use "EEE d MMM" ("Tomorrow · Thu 29 Oct") for i ≤ 1, or when fontScale > 1.3.
   Rule: 1.5× without broken lines.
8. **walk/51-completed-panel.png (320 dp):** "Done yesterday 07:00" wraps to 2 lines, and the Reopen pill touches the pane's
   right rim (`TaskPanel.kt:509-525`, end padding 8 dp). The check icon already says "done". Fix: write "Yesterday 07:00",
   set end = 12 dp, and let the pill wrap under it at large font.
   **panel-done-read-only.png:** the open step "Pay" has an empty ring, which looks tappable in a read-only panel
   (`TaskPanel.kt:533`). Use a 4 dp dot. Rule: short words; what looks tappable is tappable.
9. **The Completed count and its rows drift from the web.** The phone counts only Room's rows (/sync's 14 days + one 200
   page), and it drops done tasks with no `done_at` (`PagelDao.kt:239`). The web counts all of them (`completed/page.tsx:12`).
   A full sync deletes the older rows (`PagelDao.kt:222` `deleteSyncedTasks`) until Completed opens again.
   Fix: `completedPage` returns `total` (`sync.ts:84`, + vitest, DEPLOY NEEDED), and the header shows it. Rule: honest numbers.
10. **Motion and target polish:**
    - The strip cell's hover spring has damping .6 (`UpcomingScreen.kt:266`), and the switch knob .7
      (`HabitsScreen.kt:471`). Both bounce on input that has no momentum; use 1.0 (apple §4).
    - On cancel, `drag.id = null` comes before the spring home (`UpcomingScreen.kt:194`). The row un-dims while the
      ghost still flies back, so for ~300 ms there are two copies. Clear it after `animateTo`.
    - Strip cells are 45.7 dp wide at 360 dp and ~40 dp at 320 dp (`UpcomingScreen.kt:269`, height ≥ 48 ✅). Acceptable
      for a 7-day strip, but a tap shows no "you are here": mark the scrolled-to day with a thin ink ring.
    - walk/40: the notification's collapsed title is still cut ("Stretch for ten min…"). BigTextStyle helps only when it
      is expanded. Put the habit in the text line and "07:30" in `setSubText` (`HabitAlarmReceiver.kt:98-101`).

## A) Upcoming · Completed · Overdue: what was checked
| Item | Now | Proof |
|---|---|---|
| Week strip, dot per day, today mint + ink | ✅ | upcoming-360.png (Fri, Mon have no dot = no tasks; correct) |
| Overdue first, red title, oldest first | ✅ | upcoming-360.png; `UpcomingView.kt:26`; log order t_u2 (−3 d) then t_u1 (−1 d) |
| Move all to today, one toast + Undo | ✅ | walk/47 "2 moved to today · Undo"; fake-pagel.log:42-43 |
| Habits on their days, repeat mark, no ring | ✅ parity (the web does the same, `views.ts:53`) | walk/44 ❌ #1 (draggable), #4 (count) |
| Drag: long-press lift, haptic, tick per day, spring drop | ✅ in code (`:170,153,184-191`); ❌ #2 ghost | walk/45, walk/46 "Moved to Fri 30 Oct · Undo" |
| Room first + one PATCH {due}, time kept | ✅ | fake-pagel.log:41 `{"due":"2026-10-30"}` ~1 s; `aDropKeepsTheTime` (the walk task had no time) |
| Let go elsewhere = home | ✅ code `:193-197`; ⚠️ #10 dup | no shot |
| Offline moves reach the server in order | ✅ unit only | `movesMadeOfflineReachPagelUzInTheOrderMade`; the walk moved online |
| Day grouping at 23:59 / 00:01 Tashkent | ✅ | `completedGroupsByTheTashkentDayWithAnExplicitOffset`; `doneDayOf` `UpcomingView.kt:51`; sync.ts vitest +05:00 |
| Completed by day, time, project, untick = restore | ✅; ❌ #5 motion | walk/49-50; fake-pagel.log:45 `{"done":false}`; walk/49 "8 October" came from /api/v1/completed (log:44) |
| Read-only done panel + Reopen | ✅; ⚠️ #8 | panel-done-read-only.png, walk/51 |
| Midnight while open | ✅ re-reads today each minute | `UpcomingViewModel.kt:35` |

## Parity gaps vs the web (not bugs unless marked)
- **Better than the web:** the strip, drag, Move all, Completed by day with times, untick from Completed (the web's ring is
  `aria-hidden`, `completed/page.tsx:20`), the read-only panel.
- **The web has a "Next 7 days" subtitle only.** The phone's "N planned" count is new, and wrong (#4).
- The Completed count (#9). A star on a later day stays on Today (#6).
- Late habits show twice, in Overdue and on Today's pane. Same as the web (`datesBetween` from today), so it is kept.
- "Move all to today" also moves late habits. On pagel.uz each sends `PATCH {due: today}` → a `habit_undone` event with
  nothing to undo (harmless noise in `events`). Leaving habits out (Habits owns them) would be cleaner.
- Deferred by the builder (fine): drop on a day *section*, a TalkBack move action, paging past 200, ticking habits from
  Upcoming.
- Phase 3 gaps still open: the Done list comes only from open habits; Undo of a habit whose old due was null logs "due",
  not `habit_undone` (`task-store.ts:114`); `undo()` after a send uses today, not the tick's day (`HabitsRepository.kt:98`).

## B) Phase 3 checker top-10 re-check
| # | Item | Now | Proof |
|---|---|---|---|
| 1 | Late habit stays late (missed Tue, ticked Wed → Thu) | ✅ both sides | `Repeat.kt:120-125` nextAfter; `HabitsLogicTest.kt:37-42`; `HabitsRepository.kt:68`; `task-store.ts:101`; test `aLateHabitTickedMovesPastToday` |
| 2 | Offline tick after midnight | ✅ | `HabitsRepository.kt:70` sends `{done, day, if_due}`; `task-api.ts:67-71` (today/yesterday only, `if_due` mismatch moves nothing); `anOfflineTickSentAfterMidnightCountsForItsOwnDay` |
| 3 | Reminder for a done habit / double Done | ✅ | `HabitsRepository.kt:66` guard; `HabitAlarmReceiver.kt:72-74` `today !in days`; `aHabitTickedTodayElsewhereIsNotTickedAgain` |
| 4 | AI read loop | ✅ bounded on the phone | `InboxScreen.kt:167-169` LifecycleStartEffect; `InboxViewModel.kt:179-189` max 3 taken asks per unread set, ≥ 60 s apart (`:226`); `threeAsksThenItStops`; server e759107: 24 h skip + 24 a Tashkent day. Note: each return to the Inbox starts a fresh 3; the server cap holds it. |
| 5 | Half state while the ring fills | ✅ | walk/35: "1 of 3", and no "Coming up" copy; `HabitsView.kt:76-81`; `tickHabit` transaction (`HabitsRepository.kt:74`) |
| 6 | Notifications denied = silent | ✅ | habits-reminder-notifications-off.png ("Notifications off · Turn on", 48 dp); habits-reminder-font-1.5.png ("Exact time · Allow"); `HabitsScreen.kt:148,151,394` |
| 7 | CI proves an alarm | ✅ | alarm-count.txt `0 -> 1`; `walkthrough.sh:185` fails on no alarm; `:176` fails if "Exact time · Allow" stays; steps.txt RTC_WAKEUP 02:30 UTC = 07:30 Tashkent; walk/38 has no red line |
| 8 | 60-day streak cap + refresh race | ✅ | `HabitsRepository.kt:135` DAYS = 400; `:118` keeps touched ticks; `aRefreshThatLeftBeforeATickKeepsTheTick` |
| 9 | Greens, AI box, X pill | ✅ (⚠️ slabs) | walk/36 + habits-all-done.png bright-mint discs; inbox-ai-reading.png glass pill + sparkle; X logo (walk/26). The switch track is a mint → teal gradient (habits-reminder.png), no longer solid #1C7E69. The Action/Note/"Add to plan" #1C7E69 slabs remain (walk/28, 30; inbox-form-action.png): Boss's open question |
| 10 | Motion and words | ✅ mostly | `animateItem` `HabitsScreen.kt:228-231`; bar damping 1.0 `:256`; `ic_stat_bell` + BigTextStyle `HabitAlarmReceiver.kt:96,101`; "Mondays" (habits-360.png); "Noted ✓" (walk/32). ⚠️ walk/40 title still cut (#10); knob damping .7 |

## Broken or clipped states
- walk/45: the drag ghost is off the screen's left edge and hides the target cell (#2).
- upcoming-360-font-1.5.png: "Oct" alone on a line (#7). The strip's weekdays at 1.5× have ~2 dp between "Sun Mon Tue".
  At 320 dp and 1.5× this may clip (`maxLines = 1`, no shot). Add one.
- walk/51: the Reopen pill touches the pane's rim; "Done yesterday 07:00" wraps (#8).
- walk/46, 47, 50: the toast sits low over the list (`AppShell.kt:279-281`, on purpose for pushed screens). In walk/46 it
  covers the Friday row where the task just landed. Pulse the strip's target cell for 400 ms, so the result shows.
- walk/30: the Inbox chip labels sit 2-3 dp above the glass bar at 640 dp. Not cut.
- Still open from Phase 3: inbox-guide.png is cut flat at the sheet cap, with no fade and the Drop row hidden
  (`InboxSheets.kt:206`). walk/58: the FAB covers the "+1" chip on Today at 1.5×. walk/56: the landscape bar overlaps
  row 3 (the list scrolls under it).
- walk/59-dark.png is the same as light. The app is light only (not in scope).

## C) Design audit
- **Glass ✅** Upcoming/Completed panes and the strip are white glass with a rim (upcoming-360.png, completed-360.png).
  "Move all to today" is a PrimarySoft pill, not a slab. No dull green surfaces on the new screens.
- **No spinners ✅** `SkeletonRows` only while the first sync is empty (`UpcomingScreen.kt:216`, `CompletedScreen.kt:85`).
  No "Syncing". The older Completed rows load silently (`UpcomingViewModel.kt:141-144`).
- **Toasts ✅** Light glass + check icon + Undo (walk/46, 47, 50).
- **Contrast (sRGB, computed):**
  - ✅ Text: PrimaryInk #0B5C45 on PrimarySoft #E3F4EC = 7.0:1. Ink on the Aurora1 today circle = 10.5:1.
    Risk #B3261E ≈ 6.5:1. Muted #5B6275 ≈ 5.7:1.
  - ✅ Marks: the strip dot #1C7E69 on white glass = 4.7:1 (≥ 3). The hovered ink cell with white text is ≥ 15:1.
- **Targets ✅** Rows, rings, the Move-all pill and the untick disc are 48 dp (`CompletedScreen.kt:128`). Strip cells are
  48 dp tall, but narrower than 48 (#10).
- **Type ✅** Departure Mono only on numbers: Completed's day counts (`Glass.NumberText`, `CompletedScreen.kt:109`) and the
  habit 🔥 numbers. Words are short ("Move all to today", "Back on your list", "Nothing planned"), except #4 and #8.
- **Motion:**
  - ✅ The drop spring has damping .8 after a drag (momentum: right per apple §4). Lift has a LongPress haptic. There is
    one SegmentTick per day crossed, and a Confirm haptic on the drop, on the same beat as the visual.
  - ❌ The untick teleports (#5). The hover/knob springs bounce (#10). There is no velocity hand-off on the drop. That is
    acceptable, because the target is a fixed cell.
- **Frames:** gfxinfo shows 93.6 % janky, p50 150 ms. That is CI's software GPU, not a phone number. Measure the drag on
  the A05s.
