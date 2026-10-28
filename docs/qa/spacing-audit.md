# Spacing + layout audit — native PAGEL app (checker, 2026-10-28)

Evidence: CI run **40698886063** (newest green on `feat/native-app`, commit 4f70908). Screenshots:
`scratchpad/checker-space/shots/build/outputs/roborazzi/*.png` (101) and `scratchpad/checker-space/smoke/walk/*.png` (87).
Code: `origin/feat/native-app` @ 3fc453a. That commit's CI run (40703656629) **failed**, so its fixes have no screenshots yet.
That includes the StarCard counter slot (`ShutdownScreen.kt:207-214`) and `glass/Space.kt`.
Lens: apple-design §7 (spatial consistency) + §16 Craft ("every spacing value is a deliberate choice"), emil "unseen details compound".

## Score: 5 / 10
The panes, gutters and 54 dp row rhythm are mostly consistent. But **11 places still move a button or a row under the
thumb when the state changes** (the same kind of bug as the StarCard).
`Space.kt` exists, but code uses it **1 time**, while about **36 % of spacing values are off the scale** (6/10/14/18/2/3 dp).

## Findings, ranked (P0 = something moves under the thumb)

| # | Screen / shot | What moves or crowds | Fix (file:line) | Value |
|---|---|---|---|---|
| 1 P0 | Weekly review · `walk/84-p5-review.png` vs `85-…next-steps.png` vs `86-…someday.png` | **Next jumps twice.** On step 1 it is full width at y 405. On step 3 it is the right half at y 427. On step 4 it is at y 337. Back exists only when `step > 0`. The AnimatedContent height changes per step, so the nav row moves too. | `rituals/ReviewScreen.kt:106-110` always draws Back (`enabled = step > 0`, alpha .45) so Next keeps its half. `:95` pins the nav: move ButtonRow out of the LazyColumn into a bottom dock, or give the step slot `heightIn(min = …)` | Back+Next always 2 × weight 1f, gap 8 |
| 2 P0 | Inbox dock · `inbox-ai-reading.png` (6 icons) vs `inbox-dock-360x800.png` (5 icons) | **Each icon changes place from card to card.** `if (c != guess)` removes the guessed choice. Drop is at x 812 on one card and x 778 on the next; Do now goes 532 → 450. The guess slot also changes height: AI-reading 48 dp vs Guess 64 dp, so the deck moves 16 dp. | `inbox/InboxScreen.kt:813` always draws all 6 in ORDER and marks the guessed one (mint ring/fill). `:899` AiReading `height(64.dp)`, the same as GuessButton `:834` | 6 fixed slots; guess slot 64 dp |
| 3 P0 | Today · code (no shot) | **The waiting chip pushes the whole list down ~60 dp** (48 chip + 8 + 4 gap) when a tick is queued, and pulls it back up after the sync. The next ring moves while the thumb is still near it. | `today/TodayScreen.kt:164-166` moves `WaitingChip` into `Header`'s Row next to the sparkle (`:221`), a fixed 48 dp slot. It never becomes a list item. | header row slot 48 |
| 4 P0 | Today · `today-progress-nudge-360x800.png` vs `today-progress-360x800.png` | **The tick nudge adds a 22 dp line** ("Next: … · 10 min?") under the bar. The task pane drops 22 dp right after a tick, then rises 8 s later. | `today/TodayScreen.kt:258-260` reserves the line: always lay out one `labelSmall` line (`minLines = 1`, text "" → alpha 0) instead of `AnimatedVisibility` | fixed 6 + 16 dp |
| 5 P0 | Pair · `pair-empty.png` vs `pair-error.png` | The block is centred. The Paste row (54 dp) leaves on the 6th digit (`code.length < 6`), and the error line enters. **PAGEL title +12 dp, Connect −12 dp**, at the moment he taps Connect. `AnimatedVisibility(fade)` still resizes. | `pair/PairScreen.kt:198` keeps the Paste slot (alpha 0, `enabled = false`). `:129` swaps the error and Paste in one fixed 48 dp slot | slot 48 dp |
| 6 P0 | Date sheet · `picker-date-360.png` | **The month arrows move 40 dp when paging.** A 5-week month and a 6-week month have different heights. The sheet is bottom-anchored, so the header (and the < > you are tapping) goes up and down. | `glass/Pickers.kt:236` always lays out 6 weeks (iOS does); remove the `.filter { … }` and render out-of-month cells muted | grid = 6 × 40 dp |
| 7 P1 | Date sheet · `picker-date-360.png` vs `picker-date-time.png` | The calendar → wheel swap shrinks the pane ~50 dp (title and quick chips jump). The **time chip you just tapped slides right ~100 dp** (x 65 → 320) because the "Thu 29" back chip is put in front of it. | `glass/Pickers.kt:168-174` gives the content slot the grid's height (`heightIn(min = gridH)`), wheel centred. `:179` puts the date chip after the time chip, or always shows it (checked when in calendar mode) | slot = 6-week grid height |
| 8 P1 | Today / Project · `walk/66-font-1.5.png` | **The FAB covers the last row's title** ("Measure the drone's") even at the end of the scroll. List bottom padding = bar + 16, but the FAB sits from bar + 22 to bar + 78. | `shell/AppShell.kt:224`: when `showFab`, bottom padding = `fabBottom + 56.dp + 12.dp` | +68 dp clear |
| 9 P1 | Task panel · `panel-360.png`, `panel-pin-full-…-font-1.5.png` | Things appear above or inside the panes and push everything below: `Held` row above the title (`:194`). The steps bar (`:400`, 24 dp) appears with the first step. The "To tasks" chip (`:443`) disappears once made, and the Props pane jumps up 48 dp. The PinRow subtitle has no maxLines and the switch hides when a date is set (`:490-497`), so the row changes from 1 to 3 lines after the date sheet closes. | `task/TaskPanel.kt:194` puts the cloud-off mark in the Header row (icon slot). `:443` keeps the chip slot (disabled "Made ✓"). `:490` `maxLines = 2, minLines = 2` at 1.5×, and keeps a 46 dp trailing slot when the switch is hidden | Pin row min 60 + fixed 46 dp trailing |
| 10 P1 | Shutdown · `walk/77-p5-star-1.png`, `shutdown-star-font-1.5.png` | A sibling of the bug Boss found. The star title (`headlineSmall`, **no maxLines**) is 1-3 lines, so **Keep / Later / Drop move vertically from star to star**. The evening hint is 1 line when Off and 3 lines at 1.5× when On, so the pane changes size on toggle. | `rituals/ShutdownScreen.kt:223` `minLines = 2, maxLines = 3` (or anchor the choice row to a fixed card height). `:297` "Off" → "Off · tap to turn on" with `minLines = 2`, or put the hint under the switch at full width | title slot 2 lines |
| 11 P1 | Add sheet · `add-too-long.png` (warning wraps to 2 lines = ~52 dp at 360) | The too-long warning (`:213-216`) and NotSaved rows (`:212`) grow the sheet. It is bottom-anchored above the keyboard, so **the field and send button move up while he is typing**. | `add/AddTaskSheet.kt:213` drops the sentence and instead shows a "23/20" word counter in a fixed 1-line slot under the chips (always laid out, alpha 0 under the limit). The red field rim already says what is wrong. | 10 + 21 dp slot |
| 12 P2 | Sweep / Review rows · `sweep-rows-360-font-1.5.png` | A row's red "N words…" hint appears inline (`SweepScreen.kt:252`, `ReviewScreen.kt:151`), so every row below moves while he types. | Same slot rule: the counter is always there (muted "8/12" → red when over) | 1 line |
| 13 P2 | All big titles · `shutdown-star-font-1.5.png`, `sweep-rows-360-font-1.5.png` | **Word spaces collapse**: "Closethe day", "Emptyyour head". `headlineLarge` uses −0.96 sp tracking on an ExtraBold face. | `glass/Glass.kt:50` letterSpacing −0.5 sp (≈ −1.5 %); keep `headlineSmall` −0.4 | −0.5 sp |
| 14 P2 | Tab switch Today ↔ More · `walk/11-today-again.png` vs `47-more-phase4.png` | The big title jumps ~8 dp between tabs. More has `padding(20)` and no overline; Today/Habits/Inbox have top 12 + overline. The **More pane gutter is 20 dp**; every other screen uses 16. | `shell/AppShell.kt:486`: `padding(horizontal = 16)`, header `padding(start = 20, top = 12)` with an overline (e.g. "Lists, rituals, settings") | gutter 16 / head top 12 |
| 15 P2 | Shutdown · `walk/77-p5-star-1.png` | The left edges inside the panes do not line up on one screen: StarCard inset 20 (`:216`), RitualPane 18 (`RitualKit.kt:173`), EveningRow 16 (`:284`). The tile, bell and title start at 36 / 34 / 32 dp. | One `Space.paneInset = 16` for all three | 16 |
| 16 P2 | Task panel · `panel-360.png` | The header's Close circle ends **10 dp** from the sheet edge, but the panes end at 16 (`Header` end = 4 + a 36-in-48 button). The ring column starts at 8 (`TitleBlock :326`); every list uses 6. | `task/TaskPanel.kt:224` end = 10.dp (drawn edge at 16). `:326` start = 6.dp | 10 / 6 |
| 17 P2 | Toasts · `today-toast-under-add.png` | The toast is 12 dp from the screen edge, while the glass bar and panes are at 16. The two edges are 4 dp off on each side. | `shell/AppShell.kt:428-429`, `glass/GlassSheet.kt:76,82` horizontal 16 | 16 |
| 18 P2 | Sheets (Date vs Repeat/Priority) | `SheetTitle` row height is 48 with Done and 28 without, so the title-to-content gap differs by 20 dp between sheets. The end inset is 12 vs 20. | `glass/Pickers.kt:81` `heightIn(min = 48.dp)`, end = 20 always (Done pill pads itself) | min 48 |
| 19 P2 | Today · `walk/11-today-again.png` | Header-to-card gap changes by state: 16 with no bar, 12 under the bar, **8 under the ritual pills**. Today's item gap is 4 dp; every other screen uses 12. | `today/TodayScreen.kt:161` spacedBy 12, and remove the per-item bottom paddings `:165,169,173` | 12 |
| 20 P3 | Habits · `habits-360.png` | The 🔥 streak glyph sits ~3 dp below the meta text baseline. Done-today rows start at 170 px and part rows at 180; the "Coming up" text starts at the pane-title inset. An empty meta FlowRow still adds 4 dp top. | `habits/HabitsScreen.kt:358` only pad when non-empty; `Streak` `Alignment.CenterVertically` + `alignByBaseline` | — |
| 21 P3 | Upcoming · `upcoming-360.png` | The Overdue title row is 48 dp (Move-all button); day titles are 34 dp, so the title-to-first-row gap differs by 14 dp between panes. | `upcoming/UpcomingScreen.kt:363` `heightIn(min = 48.dp)` on every PaneTitle | 48 |
| 22 P3 | Today · progress text | "9 of 10 ✓" vs "1 of 6 ✓" changes width, so the bar's length jumps. | `today/TodayScreen.kt:256` `Glass.NumberText` (tabular) + `widthIn(min = 56.dp)` | 56 |

**Done right (copy this pattern):** `UpcomingScreen.kt:350` keeps the week-strip dot's 5 dp slot with a transparent colour.
`ShutdownScreen.kt:207-212` (new) keeps a 48 dp row and an empty Back slot. Those two are the rule, written in code.

Looked at and did not move: Completed (`walk/57-completed.png`), Projects (`projects.png`), Habits header bar (`habits-360.png`),
Inbox header (`inbox-dock-360x800.png`), the Upcoming strip (`upcoming-360.png`), and the glass bar (`glass-bar.png`).
Not checked: landscape shots except `walk/64-landscape.png`, and dark mode (`walk/67-dark.png`).

## Spacing scale today (padding / spacedBy / Spacer, ui/ only)
16 ×129 · 8 ×86 · 12 ×84 · **6 ×74** · 4 ×70 · 20 ×68 · **10 ×66** · **14 ×29** · **18 ×25** · **2 ×20** · **3 ×16** · 24 ×8 · **7, 5 ×7** · **22 ×4** · 32 ×2 · **13 ×2**.
The most off-scale files: TaskPanel 25, InboxScreen 24, HabitsScreen 19, UpcomingScreen 17, Pickers 17, TodayScreen 16.
In ButtonRow (`RitualKit.kt:105`) the gap is 10; in chip rows it is 8 (same role).

## Proposed tokens (`ui/glass/Space.kt`, extend the existing object)

| Role | Token | dp |
|---|---|---|
| Screen edge → pane / bar / toast / sheet content | `gutter` | 16 |
| Screen edge → header text and free hints | `textEdge` | 20 |
| Inside a pane, left/right | `paneInset` | 16 |
| Inside a pane, top/bottom | `paneInsetV` | 12 |
| Pane → pane | `paneGap` | 12 |
| Header top (below status bar) | `headerTop` | 12 |
| Header / counter row → first card | `headerToCard` | 12 |
| Overline → title → subtitle | `titleToSub` | 4 |
| Icon ↔ its text | `iconText` | 8 |
| Chip ↔ chip, button ↔ button | `chipGap` | 8 |
| Row leading column (ring) start / text start / divider start | `rowLead` / `rowText` | 6 / 54 |
| Touch row min / list row min | `touch` / `row` | 48 / 56 |
| Sheet title row min | `sheetTitle` | 48 |
| List bottom clear when the + shows | `fabClear` | fabBottom + 56 + 12 |
| Dock → glass bar | `dockGap` | 12 |
| A reserved message line (error/nudge/counter) | `messageSlot` | 22 (6 + 16) |

Micro values (2, 3, 6 dp inside a chip or pill) may stay, but only inside `glass/` components and never as layout between blocks.

## How CI can catch layout jumps

Add a Compose UI test helper, `assertStableBounds(tag, states: List<() -> Unit>)`. It sets each state on one
`ComposeContentTestRule` and reads `onNodeWithTag(tag).getBoundsInRoot()`. It then asserts `top` and `left` are equal within 0.5 dp.
Tag every thumb target (`review-next`, `inbox-choice-drop`, `today-ring-1`, `pair-connect`, `date-next-month`, `star-keep`).
Drive the states that matter: step 0/1/2/3, guess/no-guess, outbox 0/1, nudge on/off, 5-week/6-week month, 5/6 digits + error.
Next to each assert, record a Roborazzi pair (`…-state-a.png` / `…-state-b.png`) so a failure shows a picture.
The smoke can do the same on the emulator. `uiautomator dump` before and after each walk step that keeps the screen,
then compare the `bounds` of the same `content-desc` node (Next, Drop, Connect). A move > 4 px on a node he just tapped fails the run.
Last, a cheap static gate: a `grep` step in `build` that fails on any new `\b(1|3|5|7|9|10|13|14|15|18|22|26)\.dp` in `ui/**`
outside `ui/glass/`. Each new `if (…) SomeComposable(` with no `else` inside a `Row(`/header must carry a `// slot:` comment.
