# Round D fix · state (resumed 2026-10-30, builder agent, branch `feat/roundD-fix`)

## Resumed 2026-10-30
- 8cf9113 = native-app 0.3.34 (projects + Namoz, Room v11) merged in. Rebased and resolved first, then recorded as a merge
  on bbc3ba9 (same tree), so no force push. Namoz: `NamozGroup` is the Namoz team's own version; my one-row Namoz is dropped,
  and the Namoz test follows theirs. `Chip` is internal + modifier. No Room change (v11 stays).
- The WIP compiled at once; only `HabitLook.Anchor` in a test needed its full name (ddf3223).
- 72a7fd3 note 5 pager: CI 40986443466 build ✅. The row-rhythm test judges only whole rows (the taller Namoz group pushed
  the last row under the screen's edge at 1.5x).
- 45f3007 notes 8 + 4: "+" New habit sheet (name, part chips, Add; Undo), drag-to-link (`LinkableRow`, `LinkDrag`,
  `vm.link`, `HabitLook.stackOrder` + test). The walk covers swipe → Afternoon, + → Floss at night → POST, Undo → remove.
- What's new lines live in the pagel.uz resource, not the app: the lead adds 0.3.35's "try this" lines.
- Long-press on a row is now the drag lift (a tap still opens the page).

## Swipe smoke + PNG findings (2026-10-30, after 0514fa8)
- Smoke 40988456416 "a swipe did not bring Afternoon": the swipe WORKED (shots 50/51 show Afternoon outlined). The pill had
  `clickable(role = Tab)` + `semantics(mergeDescendants = true)`: two merging nodes, "selected" on the outer, the label on the
  inner, so uiautomator (and TalkBack) never saw "Afternoon, selected". Fix: one `selectable(role = Tab)` node. Check unchanged.
- Finding 1+2: the row box summed sp before converting ((22·2+18).sp); non-linear font scaling made it too short at 1.5x, so
  the title fell to one cut line and the progress line dropped. Each line converts alone, and the box is a floor (heightIn).
  Header date: another day uses "Mon 26 Oct · looking back"; at a large font the line owns 2 lines (no jump).
- Finding 3: the step in + ↳ live inside the card (HabitCardRow depth); every check on one x (test in aStackedHabit…).
- Finding 4: Namoz card on the habit cards' insets (Space.sm + touch); "Start tracking" is a small tonal pill in its header row.
- Finding 5: the chain's bleed in/out fades to transparent at the edge.
- Note 9 (all-habits top chain, joined bar, never red) was already in WeekStrip; namaz row weight = finding 4.

## CI so far
- f301151 code fixes (checker 1-5, 7-10): CI 40864085315 **build ✅** (unit + Roborazzi), no smoke.
- 6331656 icon v2 + START HERE v18.2: CI 40866240431 build ✅, **smoke ❌** at "the habit's page has no Reminder row".
  The cause: the page then had the actions below the month chain, off screen for uiautomator. The WIP walk no longer
  looks for that row: it opens ⋮ "Habit settings" and scrolls to "Remind me".

## Done (compiled and green in CI)
- Checker 1: a habit counts from its start (`startsOf`/`dueOn`). 2: only a whole day holds; a future day is never frozen.
  3: clocks tick their own day and are saved (`HabitClocks`). 4: titles wrap to 2 lines in one fixed row height.
  5: Namoz is one collapsed row. 7: chain #109A78, today a soft disc, ice outline. 9: the strip follows the finger,
  the bar bleeds into the next/last week. 10: Badges tab, count-dots ring, 🔥 toast. All have tests.
- Icon v2: board `docs/qa/roundD-icon-board.png`; glyph/tile contrast 1.95 at 512, 2.92 at 48 (START HERE v18.2).

## Half done (in the WIP commit, never compiled)
- **What's new**: version groups, newest first, the current one open with its own counter, older ones folded, tried
  lines sink, full-height scrolling sheet; tests at 360 dp and 1.5× scroll to line 23 (`TaskSystemScreenshotTest`).
- **Habit page** (`ui/habits/HabitPage.kt`): header (own colour tile + name + recipe line + chips with an honest
  🔔/🔕 bell chip) → `StatsCard` (3-number strip + 12-week bars, empty state, tap = week tooltip) → month calendar
  (❄ on frozen days). Old page code removed from `HabitDetail.kt`.
- **⋮ sheet** `HabitSettings`: Look (name, emoji search, 10 colours, note) · Schedule (part, days, reminder) ·
  Routine (`AnchorPicker`: None, moments, real habits only, no loops) · Actions (clock, done, freeze, take back, delete
  with Undo). VM: `setColour`, `setAnchor`, `rename`, `setPart`, `setDays`, `delete`.
- **Data** `data/habits/HabitLook.kt`: colour lives as "emoji/colour" in `habit_prefs.emoji` and a moment as
  "moment:☕ Coffee" in `habit_prefs.after` (no Room v10: the v9 schema JSON isn't in the repo and the CI artifact
  is 205 MB). `habitWeeks` gives the 12-week stats. Tests are in `HabitBoardTest` and `HabitsScreenshotTest`.
- First step on resume: push with `[smoke]`, fix compile errors, look at every new shot.

## Not started (Boss's notes still open)
- Swipe pager for Morning/Afternoon/Evening with a sliding capsule; an empty group gets a "+ Add" line.
- Habit stacking by drag in the list (link/unlink, the indented pair, ↳ line); the recipe line in the list and Today;
  a "next" nudge after the first habit is ticked.
- CRUD: new habit in ≤ 2 taps from Habits (+); Pause/Archive + Restore; reorder by long-press drag; drag between
  parts. Pause and reorder need server fields (`archived`, a habit `sort`), and colour, moments and prefs need a sync
  to pagel.uz (`PATCH /api/v1/habits/:id` exists for emoji/after/identity/minutes; the phone never sends it).
  These need vitest + DEPLOY NEEDED (the builder doesn't deploy).
- The fresh checker's SAME/BETTER/WORSE on icon v2.
- After this round is green and merged (0.3.33): the Namoz page on a fresh `feat/namoz-page`
  (`D:\10_projects\pagel\docs\state\briefs\namoz-page-brief.md`, read the Walls section first).
