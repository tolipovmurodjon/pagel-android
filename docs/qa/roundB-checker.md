# Round B checker (app-v0.10.0 = 01d9f96, CI 40712619361 build ✅ smoke ✅, 481 unit tests, 0 failed)

Fresh read-only checker, 2026-10-28. Skills applied: apple-design, emil-design-eng. Shots read from
`checker-rB/shots/build/outputs/roborazzi/*` and `checker-rB/smoke/walk/*` (scratchpad). Contrast measured on pixels (WCAG).

## Score: 7/10
B, C, D, E, G, H and J landed and the finish moment works (walk: `PagelFinish: confetti 0..3`, panel strike + Reopen + toast).
It is not a 9 for four reasons. F and I were not built, and A is only partly done. The server mis-dates a past habit dot.
A count-habit tap is not idempotent. And Boss's own rule "titles keep their word spaces at 1.5×" breaks on the Date sheet ("Dat / e").

## Top 10 fixes (ranked)
| # | Shot | What | Fix (file:line) | Rule |
|---|---|---|---|---|
| 1 | (data, no shot) | A dot ≥ 2 days back on a habit whose `due` is older than that day falls into the toggle branch. That is the normal case for a missed habit (e.g. due 25 Oct, tap 26 Oct on 28 Oct). The server logs `habit_done` for **today** and moves the date. The phone shows the 26th done, so phone and server disagree | dashboard `app/src/lib/task-api.ts` apiPatch done branch: send any `b.day` inside 0..7 and `!== today` to `habit_day` (whatever `t.due` is). vitest: due D-3, PATCH {done, day:D-2} → event day D-2, due unchanged | data |
| 2 | (data) `fake-pagel.log` 3× identical `PATCH t_h4 {"done":true,"day":…,"if_due":"…13:00"}` | A count-habit tap carries no idempotency. If the outbox retries after a lost reply (Boss's 18 s hangs), the server counts a second tap, because `if_due` does not change before the Nth tap | Phone `HabitsRepository.kt` tick body: add `"n": taps+1`. Server `task-store.ts` toggle: set the count to n (max with the current count), not +1 | data, Rules "Data and sync" |
| 3 | `picker-time-range-360x800-font-1.5.png` | The sheet title breaks mid-word: "Dat / e". The summary has no weight, so it starves the title | `ui/glass/Pickers.kt:82-83`: title `maxLines=1, softWrap=false` (no weight). Trailing `Modifier.weight(1f, fill=false)`, `TextOverflow.Ellipsis`, `textAlign=End` | Boss: titles keep word spaces, 1.5× no clip |
| 4 | `habits-compact-360x800*.png`, walk `47/49` | The 7 dots plus the bell take ~100 dp of each row. Titles get crushed: "Read 10 / pages" and "Stretch for / ten minutes" wrap at 1×, "…then s…" is cut, and at 1.5× "five-minut…" breaks mid-word. Brief C asked for compact one-line titles | `ui/habits/HabitsScreen.kt:365`: move `WeekDots` into the meta `FlowRow` (line 349, after the streak) and keep the bell at the end. The title then gets the full width | brief C, Layout |
| 5 | `panel-pin-360x800.png` vs `panel-done-360x800.png` / walk `29` | Header jump: "Won't do" disappears when the task is done, so Copy slides 48 dp right | `ui/task/TaskPanel.kt:245`: keep the slot (an invisible 48 dp box when `!canStop`) | Layout 1 |
| 6 | (data) range | Turning the start wheel past the end silently deletes the end. `setTime` → `DatePick.range(t, end)` returns only the start when `end <= start`, and that value is saved | `ui/glass/Pickers.kt:150`: keep the duration (move the end by the same delta, clamp 23:55), or keep the end and refuse the start | data |
| 7 | `panel-pin-360x800.png`, `panel-pin-full-…-1.5.png` | The off switch is invisible: track (167,210,195) on pane (205,249,228) = **1.44:1**, thumb 1.17:1. The "3 already pinned" reason is in alarm red for a normal state | `GlassSwitch` `TaskPanel.kt:529-545`, reason text `:491`: off track Ink 0.20 + 1 dp Ink 0.35 rim (≥3:1). The reason in `Glass.Muted` | marks ≥ 3:1 |
| 8 | `habits-compact-360x800.png` | Missed-day hollow dot **2.61:1**. Count-ring empty arcs (flag @0.35) ≈ **1.6:1**: you cannot see that 1 of 3 is left | `HabitsScreen.kt:421` alpha 0.40 → 0.55; `:390` alpha 0.35 → 0.6 (or Ink 0.30) | marks ≥ 3:1 |
| 9 | (H) day cleared | The "big moment" is 5 small 34-bit bursts at raw px y=140/230, so on a 3× phone they sit ~47-77 dp from the top, under the header, for 1.1 s. That is not full-screen confetti. It fires only from a Today row: finishing the last task from the panel or Upcoming gives no chord and no card. Undo does not unmark `ClearedDays`, so the streak inflates | `ui/today/TodayScreen.kt:151`: origins in dp across the height, ~2 s life and 60+ bits for the clear. Move the cleared check into `TodayViewModel` (any tick source). `ClearedDays.unmark` on Undo | brief H |
| 10 | `today-waiting-chip-360x800.png` | Header shows "3 today · **Syncing**" (PHASE-BRIEF: no "Syncing…"). The "↑ 2 waiting" chip sits 4 dp from the right edge while the title sits 20 dp from the left | `TodayScreen.kt` Header: drop the Syncing word (the chip already says it). WaitingChip `padding(end = Space.lg)` (~line 222) | look rules, Layout 2 |

## Items A-J
| Item | State | Evidence |
|---|---|---|
| A sync | **partly** | ✅ connect 20 s / read 45 s / write 20 s (`PagelHttp.kt`), "Offline" after 2 misses (`SyncEngine.statusAfter`), network-back retry, "↑ N waiting" chip + tap = send (`today-waiting-chip-360x800.png`). ❌ failure class (DNS/connect/TLS/timeout/HTTP) in diagnostics, ❌ red "Not sent: why" chip, no unit test of the chip tap |
| B time ≠ undo | **done** | Server faf0df3 `task-store.ts` `ev(t.every && e.undo === true ? "habit_undone" : "due")`. Phone undo sends `undo:true` (`HabitsRepository.undo`). The phone path (reminder wheel `HabitsViewModel.kt:145-150`) still keeps the missed day; harmless now. Where "04:55" came from is not explained |
| C habits | **mostly** | Compact row, ring tick, "2/3", 🔥, 7 dots, week sheet with Best (`habits-week-360x800.png`), 3× walk 47-49 (fake log: 3 PATCHes), no red missed day, status-bar fade (walk 49: the title fades under the clock, acceptable). Gaps: titles crushed (#4), a done row is `enabled=false` so its long-press undo is dead (`HabitsScreen.kt:335`), best streak only in the sheet |
| D ranges | **done** | Server 2f3c172 `isDue` (24 h, end > start) + vitest. Walk 27-28: `PATCH {"due":"2026-10-30 09:00-10:00"}`, panel row "30 Oct 09:00-10:00". Web shows a raw hyphen (stated). Data gap #6 |
| E confirm + close | **done** | Mint "Done" in the header (`Pickers.kt:84-99`, 4.96:1 white on #1C7E69). Walk `16-add-saved.png`: sheet closed, toast "Added · Upcoming". `add-after-add.png` is the old keep-open test, not this behaviour |
| F action plan | **not done** | No "Plan it" chip, no AI plan endpoint, no drag reorder, no "Next:" step on rows (stated in START HERE v16) |
| G panel tick | **done** | walk `29-panel-ticked.png`: strike, Reopen, confetti, "2 in a row 🔥 · Undo"; fake log `PATCH t_ci2 {"done":true}` then `{"done":false}`. Pin switch + helper + 3-cap reason, note hidden when equal to the title, long-press labels, red outlined Delete |
| H dopamine | **mostly** | Media-stream ding at 0.7 (`Celebrate.kt` Ding.build), More → Sounds, confetti never skipped (only folds use `reducedMotion`), progress bar, combo, nudge (`today-progress-nudge-360x800.png`), Day cleared card + chord. Gaps #9 |
| I speed | **not done** | No cold-start/jank/tap numbers sent, no Speed row, no baseline profile, no before/after |
| J small | **done** | `RitualStateEntity.eveningAt = "20:45"` (saved rows keep theirs), `shutdown-star-1-360x800.png` shows 20:45. `client_id` sent (fake log `"client_id":"local_71a6e5897539"`), server 505bcd8 + vitest |

## Data safety
| Check | State | Note |
|---|---|---|
| habit_undone only on `undo:true` | ✅ | faf0df3. ⚠️ the undo event logs `day: today` (server time), not the tick's day: an Undo that sits in the outbox past midnight takes a tap off the new day (`task-store.ts` due op) |
| 3× counts per day | ✅ / ❌ | `tapCounts` clamps at `times`, phone `HabitTickEntity.n` (Room). ❌ no retry idempotency (#2) |
| past-day dots, last 7 days only | ⚠️ | Both sides allow 0..7 = 8 days while the UI shows 7. ❌ server dates the tick wrong for missed habits (#1) |
| client_id idempotency | ✅ | Lookup before insert, `client_adds`. Low risk: two overlapping retries can both miss (insert after `editTask`). WorkManager unique work makes that rare |
| ranges validated | ✅ server / ⚠️ phone | `isDue` rejects 24:00, end ≤ start. Phone drops the end silently (#6) |
| Room v7 migration | ✅ | AutoMigration 6→7, defaults `times=1`, `n=1`. `MigrationTest` asserts version 7. Schema `7.json` exported |
| DEPLOY | ⚠️ | faf0df3, d615e15, 505bcd8, 2f3c172 are on `feat/gtd-core` only. Until deployed, a range PATCH is a 400 + rollback, and a 3× habit moves at its first tap on live pagel.uz |

## The finish moment: does it feel like a game reward?
Half-way. Every tick lines up feel, sound and picture in one frame: Confirm haptic, ding on the media stream (so the silent A05s now hears it),
34-bit confetti from the ring, toast combo "3 in a row — on fire 🔥", and the bar springs (damping 0.8).
That is the Duolingo/Things beat, and walk 29 shows it working inside the panel.
What is missing is the escalation a game uses. The combo only changes toast text: no sound pitch step, no bigger burst at 3+, no flash on the bar.
The level-end ("Day cleared") is the same 1.1 s burst five times near the top edge.
It also does not fire when the last task is closed from the panel or Upcoming (#9).
⚠️ The brief disagrees with itself. G said "respect the ringer", H said default "always". The builder chose H.
Boss should confirm, because with silent on in a meeting the ding still plays at media volume.

## Layout findings (RULES.md "Layout")
- **Jump** `TaskPanel.kt:245`: the Won't-do slot vanishes when the task is done (#5).
- **Jump (first load)** `TodayScreen.kt:166`: the progress item is added only after `loading`, so the list drops ~30 dp once on a cold start. Reserve it as the skeleton does.
- **Crowding** `HabitsScreen.kt:347/365`: dots + bell squeeze the title (#4). At 1.5× the meta FlowRow puts 🔥 on its own line, so rows have uneven heights (`habits-compact-360x800-font-1.5.png`).
- **Crowding** `Pickers.kt:82-83`: Date title vs summary vs Done (#3). The "– 11:00 ✓" chip wraps to a second row at 360 dp, and its leading "–" reads as minus. Use "to 11:00" or "10:00 → 11:00" (`Pickers.kt:201`).
- **Off-scale / off-token** in new code: `TodayScreen.kt:167` (start/end 20 while panes use 16, so bar and card edges miss by 4 dp, visible in `today-progress-360x800.png`), `:254` 10.dp, `:265` 6.dp; `WaitingChip` 12/6/4; `TaskPanel.kt:224` start 16 / end 4 (close button ~11 dp from the edge vs 17 dp left gutter); `Pickers.kt:94-96` 14/8/5. Rule 2 says `Space` tokens only.
- **48 dp**: the week-sheet day cells are (328-8)/7 ≈ **45.7 dp** wide at 360 dp (`HabitsScreen.kt:497`). Header icon buttons are 36 dp glyphs inside 48 dp boxes ✅.
- **Stable** ✅: Close the day card top is equal on star 1/2 (`shutdown-star-1/2-360x800.png`, y≈450). Inbox dock keeps six fixed slots (`inbox-dock-360x800.png`). Pair error slot is stable (`pair-error-stable.png`). The nudge line is always laid out.

## Design notes (apple-design / emil-design-eng)
- Glass reads as glass on every changed pane (rim + light gradient, `today-day-cleared-360x800.png`, `habits-week-360x800.png`). No flat mint slabs.
- Text contrast passes: muted #5B6275 on pane 5.2-5.8:1, "2 of 5 ✓" #0B5C45 7.2:1, streak orange 5.0:1, "2/3" 7.9:1, Done 4.96:1.
  Fails (marks): switch off 1.44, hollow dot 2.61, empty arcs ~1.6, progress track 1.15 (decoration, but the light start of the fill gradient carries the meaning at 1 of 5).
- Motion: the ring pop spring (damping 0.5) is right for a reward. The progress spring is 0.8 with no gesture momentum, so use 1.0 there (apple-design §4).
- Review: the "Next" button puts its › chevron before the word (`review-next-step-3-360x800.png`). A forward chevron should trail.
- The Day cleared copy sends him to "pagel.uz" while the + is on the same screen (`TodayScreen.kt` DayClearedCard). Say "Add the next one with +".
