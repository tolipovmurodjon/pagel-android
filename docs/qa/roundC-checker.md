# Round C checker · app-v0.11.0 (56b1044) · CI 40751262920 (green)

Fresh read-only checker, 2026-10-29. Opened: every calendar shot (List/Day/3 days/Week/Month, 360x800 + 1.5x, next week),
notify-settings (+1.5x), today-day-closed(-peek), today-progress(+1.5x), today-day-cleared-1.5x, whats-new, sheet-full-panel,
sheet-half-date, edge-fade-top; walk 53, 61, 63, 64, 65, 70, 98; fake-pagel.log, alarm dumps, steps.txt. Skills applied:
apple-design, emil-design-eng. Contrast computed (WCAG formula) from the tokens in ui/glass/Glass.kt.

## Score: 7/10
The calendar and notifications are real (glass top over its own layer, 5 views, paging, drag, channels, exact alarms,
offline Done, 60 lines). It loses points at 1.5x (Month shows "2" for 28, the view switch runs together, the
Notifications page clips), on the permission asked at launch, and on copy that repeats on day 7.

## Top 10 fixes (ranked)
| # | Shot | What | Fix (file:line) | Rule |
|---|---|---|---|---|
| 1 | calendar-month-360x800-font-1.5 | Two-digit days break: today "28" shows as "2" (also 20, 22, 23, 25, 26, 29, 30) | CalendarViews.kt:424-432: the day number sits in `size(26.dp)` with no `maxLines`; use `sizeIn(minWidth = 28.dp, minHeight = 28.dp)` + `maxLines = 1, softWrap = false` (or `BasicText` autoSize, min 10.sp). Add a 1.5x assert: the text node of today reads "28" | 1.5x font, no clipping |
| 2 | calendar-*-font-1.5, walk 70 | View switch runs together: "3 daysWeekMonth", "Month" touches the pill edges | CalendarViews.kt:151-161: add `.padding(horizontal = Space.xs)` inside each segment Box so the autoSize text has a gap to shrink into; raise the segment to `heightIn(min = 44.dp)` (36 dp now, tap area = 36) | 1.5x, 48 dp targets |
| 3 | notify-settings-360x800-font-1.5 | Known clip: "No mercy" cut at the right; "Notificatio…" ellipsized | RitualKit.kt:117-131 `Segmented` hugs its words: give it `fillMaxWidth()` + `weight(1f)` per part + autoSize, as `ViewSwitch` does. NotifyScreen.kt:98 → HabitsScreen.kt:639 `FixRow` label: `maxLines = 2` (or label "Off" beside the bell) | 1.5x, 360 dp |
| 4 | MainActivity.kt:55 | `ensureNotifPermission()` in `onCreate`: the permission is asked at LAUNCH, not at first use. START HERE v17 says "never at launch" | Delete the call at MainActivity.kt:55 (and the launcher at :48); NotifyScreen.kt:66, HabitsScreen.kt:142 and the panel toggle already ask at first use | RULES.md Security |
| 5 | calendar-week-360x800, walk 64 | Known clip: week blocks read "Me…", "Rec…", "Str…", "Lo…"; at 1.5x "M…", and the "15:0" label is covered by a block | CalendarViews.kt:351 + 384-385: in 3-day/Week let the title wrap by character with `overflow = TextOverflow.Clip`, `maxLines` = what the height holds, 11 sp, `Hyphens.Auto`; min block height 40 dp in Week. Fold TIMED daily habits into the All-day "N ⟳" count in Week/3 days (they fill every column at 08:00/13:00 in walk 64). GUTTER (CalendarViews.kt:251, 44 dp) measured from the label at the current font scale, or labels "05" not "05:00" | Clean, 1.5x |
| 6 | calendar-week/3days-360x800, walk 64 | Strip days are not aligned with the timeline columns (Sat in the strip ≈ 15 dp left of the Sat column); 3 days has no day names on its columns; Day view's header still says "28 Oct - 3 Nov", not the day shown (walk 63 shows Fri 30) | UpcomingScreen.kt:327 / CalendarViews.kt:304: in Day/3 days/Week add a column-header row (EEE d) inside the glass top, padded `start = GUTTER, end = Space.sm` like the grid; Day's title = the focused day | Wayfinding (apple-design 16) |
| 7 | notify_copy.xml:56-79 | "Never repeats in 7 days" cannot hold: morning, evening and overdue have 6 lines each and fire once a day, so day 7 repeats; tasks have 14 per tone, so > 2 timed tasks a day repeat inside the week. "%1$d things today" gives "1 things today", "1 stars still glowing". "the gym won't lift itself" (line ~45) rings for "Pay the internet bill" | ≥ 10 lines per daily kind, ≥ 30 task lines per tone (or one shared pool); `<plurals>` for every `%1$d`; drop topic-specific lines from the generic pool. NotifyCopyTest: simulate 7 mornings + 21 task fires | Brief item, copy |
| 8 | walk 98 + fake-pagel.log:95-99 | After a Close the day after midnight (ritual day 2026-10-28, kept 2 + picked 1), the closed card shows NO "Tomorrow · 3" peek: the stars are dated ritualDay+1 = calendar today, the peek looks for calendar tomorrow | TodayViewModel.kt:90: use `Rituals.ritualDay(now) + 1` (the same day RitualsRepository.kt:196 wrote). Test: close at 00:30 → peek lists 3 | Data shown = data written |
| 9 | calendar-day-before-drag / code | Drop jumps: `onDragEnd` zeroes dx/dy at once (CalendarViews.kt:372) while the new due reaches the layout a frame+ later through Room, so the block flashes back to its old slot, then teleports; during the drag it follows the finger freely, not the 15-min grid it will land on | Keep dx/dy until `b.start`/column changes (`LaunchedEffect(b.start, i) { dx = 0f; dy = 0f }`), show the snapped slot while dragging (round dy to a quarter), settle with a spring (damping 1, stiffness ~600) from the live offset | apple-design §3 (animate from the presentation value) |
| 10 | calendar-day/week-360x800 | Hour labels "02", "03", "04" peek, cut, in the 16 dp strip left of the glass top; at 1.5x only "0" shows | CalendarViews.kt:302-303: fade the timeline under the glass with `Modifier.edgeFade(top = headH)` (the rule-4 mask) or skip labels whose y < scroll + headH | RULES.md Layout 4, scroll edge effect |

Next (11-16): **kept stars are not capped** (RitualsRepository.kt:192: 4 kept → 4 stars tomorrow; review.ts `shutdown` the same)
· **web shutdown overwrites a due with a bare date** (review.ts ad76178: "…15:00" loses its time, a later due is pulled back to
tomorrow; the phone keeps both) · What's new: aapt strips the `"` (whats_new.xml:10,13 → screen shows `Today to come back`,
`Also 10 min before`; use `\"` or “ ”) and the list misses this round's two Boss notes (empty Today after Close the day,
the status-bar fix) · What's new empty tick = ink 10 % = **1.23:1** (WhatsNew.kt:101-102): a mark needs 3:1 → 1.5 dp ring
`#1C7E69` · a morning plan set before 07:00 is swallowed by quiet hours (TaskAlarmReceiver.morning → NotifyCopy.quiet) ·
Today header "3 left" + bar "2 done · 3 left" say the same number twice (today-progress-1.5x): let the header carry the
date line only, or the bar only "2 done".

## Round C items
| Item | Verdict | Evidence |
|---|---|---|
| Glass strip (list under it) | done | CalendarViews.kt:127-138 `drawBackdrop` (vibrancy, blur 8, lens) over UpcomingScreen.kt:284 `layerBackdrop`; calendar-list shows rows under it. Partly: labels peek beside it (#10) |
| Week paging + Today pill | done | WeekPager (STRIP_PAGES 79), walk 61 → 62; pill slot kept |
| 5 views, remembered | done / partly at 1.5x | calendar-* shots; #1, #2 |
| Drag a block | partly | `aBlockDraggedAnHourDownMovesItsTime` (Robolectric), Room + outbox + Undo (UpcomingViewModel.kt:84-89); no emulator drag; jump on drop (#9) |
| Colours = project tile | done | `projectColor` = TileColors first stop; blocks, month bars, strip dots |
| Every timed task rings | partly | TaskAlarms.plan: one-off timed tasks ✓, ranges at start ✓, 10 min before ✓; a repeating timed task that is not a habit with its reminder on gets nothing (`continue` on `every`) |
| Actions offline | done (offline not proven) | Done = TaskRepository.finish (Room + outbox); walk "the reminder's Done reached pagel.uz after ~2 s"; +15 min and Tomorrow keep the time/range |
| Channels | done | tasks / habits / rituals (notification-dump) |
| Morning plan 08:00 | done (alarm), content unproven | alarm-dump MORNING_FIRE 1 |
| Quiet hours 23-07 | done | tasks silent, morning/evening skipped (NotifyCopy.quiet) |
| 60 lines, no repeat in 7 days | partly | 60 lines ✓, `pick` ✓, but daily kinds have 6 lines (#7) |
| Permission at first use | **not** | MainActivity.kt:55 (#4) |
| Reschedule boot/update/time change | done | Manifest :114-121 → HabitAlarms.replan → TaskAlarms.plan |
| Close the day → empty Today | done / partly | walk 98 empty card ✓; peek missing after midnight (#8); kept uncapped |
| Server ad76178 + Undo | done | review.ts TOMORROW_STARS; phone "due" undo (RitualsRepository undo branch) |
| Status bar / sheets edge to edge | done | GlassSheet `SheetSystemBars`, scrim only for half sheets; sheet-full-panel runs to both edges; 3 pixel asserts (Robolectric draws no system bar: Boss's phone is the real proof) |
| "12 done · 1 left" | done | today-progress: "2 done · 3 left" |
| What's new | done / partly | whats-new-360x800, once per version (WhatsNew.kt:59); quotes + missing items |
| Spacing gate | done / partly | dp-gate.sh in build.yml:26; it never counts 2 or 6 dp (CalendarViews.kt:234 `vertical = 6.dp`) nor size/offset/height |

## Data safety
| Check | Verdict | Where |
|---|---|---|
| Calendar drag: Room first, outbox, Undo; habits not draggable; range keeps its length | ✅ | UpcomingRepository.moveTo, CalendarLogic.droppedDue:91-99, CalendarViews.kt:368 |
| Moved / done task never rings | ✅ | planned-set diff cancels; FIRE re-checks open + ±15 min of its time |
| Inexact fallback + SecurityException | ✅ | TaskAlarms.set, WINDOW 10 min < 15 min on-time check |
| Notification Done offline | ✅ code, ⚠️ unproven offline | TaskRepository.finish |
| Close the day writes | ✅ phone keeps time, Undo restores dues · ⚠️ kept uncapped · ⚠️ web drops time | RitualsRepository.kt:192-201, review.ts |
| Group summary | ⚠️ one "tasks-<day>" group across Tasks + Rituals channels; summary takes the last channel | TaskAlarmReceiver.post |
| Room | no schema change (v7); prefs in SharedPreferences, off Main | NotifyPrefs |

## Finish moment
Unchanged mechanics hold: media-stream sound (Celebrate.kt:62 `USAGE_MEDIA`), combo third note (:91), Day cleared from any
screen via ShellViewModel. It feels like a reward for in-app ticks. Gaps: a Done from the notification shade gives no
moment (fine), the confetti still draws under an open full sheet (known). The closed-day card is calm and right.

## Layout (RULES.md "Layout")
- Month 1.5x: numbers wrap inside a 26 dp circle (#1). Week/3 days: strip vs columns off by ~15 dp (#6).
- View switch 1.5x: zero gap between segments (#2); Notifications Tone switch overflows the card (#3).
- Timeline: hour labels cut beside the glass (#10); at 1.5x a block covers "15:00".
- No new jumps found: closed card, day-cleared card and the panel's slots are stable in their pairs.

## Design (apple-design + emil-design-eng)
| Before | After | Why |
|---|---|---|
| Drop resets offset to 0, block flashes back | settle by spring from the live offset to the snapped slot | Interrupt/settle from the presentation value |
| Ellipsis "Me…" in 40 dp columns | char-wrap + clip, height-driven lines | A word stub carries no meaning |
| Segments 36 dp tall | ≥ 44 dp drawn, 48 dp touch | Fitts + 48 dp rule |
| Empty tick ink 10 % (1.23:1) | ring #1C7E69 1.5 dp | Marks ≥ 3:1 |
| Hour labels Muted on bottom mint aurora 4.44:1 | Ink 70 % or a soft page scrim under the gutter | Text ≥ 4.5:1 |
| Out-of-month days Muted×0.6 = 2.57:1 | fine as inactive, but they are tappable: 0.75 alpha (≥ 3.5:1) | Legible if tappable |
Glass reads as glass (rim + shadow + lens on the strip). Muted on page 5.96:1, PrimaryInk on PrimarySoft 7.0:1, Risk 6.4:1,
ink on a 20 % coral block 15:1 ✅.

## Not checked
No device (no system bars in Robolectric), no audio/haptics felt, evening/overdue nudge content, offline Done, a real drag.
