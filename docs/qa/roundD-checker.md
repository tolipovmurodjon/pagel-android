# Round D checker · app-v0.13.0 = faafe7e (PAGEL 0.3.32) · CI 40855282823 green

Fresh read-only checker, 2026-10-29. Read: `docs/PHASE-BRIEF.md`, `docs/RULES.md`, START HERE v18.1, Boss's notes
`D:\10_projects\pagel\docs\state\briefs\roundD-next.md`, his 4 `D:\00_inbox\taste\habit-*__loved__pagel-habits.jpg`.
Opened: every habits / namoz / inbox / icon Roborazzi shot (`screenshots-and-reports`), the smoke walk 03-04, 32-43, 47-59, 64
(`smoke-evidence`), `docs/qa/roundD-icon-board.png`, `docs/icon/pagel-icon-512.png`, Humopedia `rebuild-wave/mint/wave-mint-512.png`.
Code read at faafe7e: `HabitBoard.kt`, `HabitsScreen.kt`, `HabitDetail.kt`, `HabitsViewModel.kt`, `HabitPrefsRepository.kt`,
`Namoz.kt`, `HabitsScreenshotTest.kt`, `HabitBoardTest.kt`, `docs/icon/make_svg.py`. Skills applied: apple-design, emil-design-eng.

## Score: 7 / 10

Why: most of Boss's 12:00-12:15 notes are met and proven (minimal ≤ 5-part rows, no bell on rows, one row rhythm, the
all-habits strip, the joined chain, the habit page with its month chain + 3 stats, Namoz A built and B written up). It loses
points for two data bugs that make the chain lie (a new habit breaks every past full day; a partial day with one freeze
links the chain), a timer that can tick the wrong day, titles cut even at 1x on the emulator (`48-habits-morning.png`
"Stretch for ten minut…"), a Namoz card that dominates the "no mental load" screen, and an icon that is frosted, not glass,
and weaker than the flat P at 48 px.

## Top 10 fixes (ranked)

| # | Evidence | What is wrong | Fix (file:line) | Rule |
|---|---|---|---|---|
| 1 | code; no test covers it | The all-habits chain counts a habit on days before it existed: add a daily habit today and every past ALL day becomes SOME (ring, chain gone). `TaskEntity` has no start date; `repeatsOn` is true for every past day of an `every=day` habit | `HabitBoard.kt:287-303` (`heldOf`, `clearOf`) + `repeatsOn` `:31`: a habit is due only from its start (`t.due` date, else its first tick, else the day the phone first saw it, stored in `habit_prefs`). Test: "adding a habit today keeps last week's full days full" through `habitBoard`, not hand marks | Boss: chain = "when I do it all days without breaking" |
| 2 | code | A partial day where any one habit is frozen draws as FROZEN, and FROZEN holds, so the bar links over a day that was not complete | `HabitsScreen.kt:324`: `Clear.SOME -> Mark.PARTIAL` always; only `ALL` holds (ice tint when `frozen`). `aFiveDayStreakAndAGap` (`HabitsScreenshotTest.kt:214-233`) feeds hand-made marks: add one that builds marks from ticks + freezes via `habitBoard` | data safety |
| 3 | code | Timer end calls `tick(k)` which uses `selectedDay()`: start Stretch today, tap Monday in the strip, the clock ends, Monday gets ticked. Timers live only in memory (lost on process death), no notice if it ends in the background | `HabitsViewModel.kt:223` → a `tickOn(id, today)`; `:169` stays for taps. Keep the start time in `SavedStateHandle`/Room; an exact alarm at the end | respond first, never lose a write |
| 4 | `habits-360x800-font-1.5.png`, `habits-rows-font-1.5.png`, smoke `48-habits-morning.png` (1x!) | "Stretch for te…", "Go through fi…", "Log today's food …" | `HabitsScreen.kt:581-584`: `maxLines = 2`; give the text column a fixed height = 2 × title lineHeight + `Space.xs` + the 18 sp progress box (in sp → dp, so it scales), content centred. Every row stays the same height (Boss's ref: "Audio kitob eshitish" 2 lines, same card height as "Tahorat olish"). Add an assert: no ellipsis on "Stretch for ten minutes" at 1.5x | Layout 1, Boss's ref |
| 5 | `habits-360x800.png`, smoke `57-namoz-group.png` | Namoz takes ~300 px before 1 Nov ("Start tracking") and 5 rows after, on every part pill; in 57 the habits fall under the tab bar | `HabitsScreen.kt:205-207`, `672-723`: one row like a habit (mosque · "Namoz" · "Asr 15:40" next prayer · 3/5 ring) that expands on tap; before 1 Nov a single quiet row with a small Start pill. Or show only the prayers of the chosen part | Boss 12:10 "main screen = main task" |
| 6 | board, `app-icon.png`, `pagel-icon-512.png` | Icon reads as frosted resin, not liquid glass; glyph/tile 1.40-1.80:1 | see "Icon verdict" below; `docs/icon/make_svg.py:19-21, 30-35, 49-62` | brain `rules/icon-rebuild-1to1` rule 12 |
| 7 | `habits-chain-streak-and-gap.png`, `habits-360x800.png` | Chain mint #66DDA8 is 1.68:1 on white (marks need ≥ 3:1); partial 26/27 rings and today's 28 ring look alike (both rings, only arc length differs) | `HabitsScreen.kt:380` CHAIN → #1C7E69 fill with white bold numerals (4.96:1) or a deeper mint ≥ 3:1; `:403-410` today = PrimarySoft filled disc + bold (the ref's grey 28), partial = arc on a 1.23:1 track only | marks ≥ 3:1 |
| 8 | `habit-detail.png`, smoke `59-habit-page.png` | Chip "🔔 09:00" next to "Reminder · off" (says the opposite); actions pane sits above stats and calendar, so the chain is below the fold (ref: chips → stats → calendar); "Freeze today"/"Take back today's tick" act on the strip's selected day | `HabitDetail.kt:229` clock chip "09:00" (bell only when on); move `StatCards`+`MonthChain` above `TodayActions` (`:241-242`); `:292-309` + `HabitsViewModel.kt:231-241` act on today or name the day; refuse a freeze on a future day | Boss's ref, honest labels |
| 9 | code `HabitsScreen.kt:294-299` | Week swipe has no 1:1 tracking and no velocity: the strip sits still, then jumps a week after 80 px | offset the chain row with the drag in `graphicsLayer`, spring back (damping 1, ~0.35 s), commit on 80 px or a flick > ~0.11 px/ms; when last Sunday held, draw the bar off the left edge (the ref's 26 bleeds left) | apple-design §2, §5, §6 |
| 10 | smoke `58-habits-history.png`; `habits-rows-font-1.5.png`; smoke `55-habit-3x-3.png` | History tab "Achieve" clipped at 1x; a 0/3 count habit's dashed ring reads as a spinner; toast "6 in a row — on fire" while the row says 🔥1 | `HabitDetail.kt:396-398` segmented tabs with `weight(1f)` or "Badges"; `HabitsScreen.kt:648-654` 3 dots or bigger gaps + the count; `HabitsViewModel.kt:182-187` say "6 ticks today", keep "in a row" for streaks | no spinners; one word one meaning |

Also: `HabitCard.week` (`HabitBoard.kt:181-182, 256`) computes 7 marks per habit per frame and nothing draws them now: delete.
Inbox (smoke 32 vs 43): the card's top moves 163 → 207 px between two states (Layout rule 1, not round D's work).

## Boss's notes: met or not

| Note (roundD-next.md) | Verdict | Evidence |
|---|---|---|
| Chain = one connected shape, gap on a break, today ring, future faint; same in the month; Roborazzi 5-day streak + gap | ✅ drawn, ⚠️ proof is hand-made marks | `habits-chain-streak-and-gap.png` (20-24 joined, 25 gap, 26 partial, 27 alone, 28 ring); fixes 1, 2, 7 |
| Default view = the day: week strip (swipe) + today's habits as cards, no 7-dot rows | ✅ (swipe not 1:1, fix 9) | `habits-360x800.png`, smoke 47 |
| Tap a habit → its page, own month chain + 3 stat cards | ✅ (order, fix 8) | `habit-detail.png`, smoke 59/64 |
| No bell on rows; one spacing rhythm, equal rows + gaps assert | ✅ rows; ⚠️ bell chip on the page | `rowsHaveOneHeightAndOneGap(+1.5x)` green; `habit-detail.png` |
| Row = tick · emoji · title · progress · 🔥, ≤ 5, nothing else | ✅ 5 parts, ≤ 2 targets; ❌ titles cut | `atMostFiveThingsPerRow`; fix 4 |
| Reminder, days, colour, emoji, stacking, freeze, identity, stats, history live on the page | ✅ | `HabitDetail.kt:241-262`, smoke 59 |
| Top bar = ONE all-habits chain; partial = thin progress ring; full days join | ✅ logic; ❌ two data holes | `theTopBarIsTheAllHabitsChain` ("4 of 5 done"); fixes 1, 2 |
| Namoz from 1 Nov, both options in START HERE | ✅ A built, B = one line | v18.1; `Namoz.counts` `Namoz.kt:70`, `markOf` BEFORE `HabitBoard.kt:47` |
| Minimal main screen, no mental load | ⚠️ rows yes, Namoz no | fix 5 |
| Icon: real liquid glass, layers, monochrome, round, notification, splash, board at 512 + 48, checker before Boss | ⚠️ layers ✅ (`mipmap-anydpi/ic_launcher.xml` fg/bg/monochrome), splash + silhouette unchanged ✅; finish ❌; board shows 240 px, not 512 | below |

## Data safety

| Area | Verdict |
|---|---|
| All-habits chain math | ❌ fixes 1 + 2. A deleted/archived habit also leaves the list, so past partial days can turn full: compute from the habits due *that* day |
| Namaz from 1 Nov | ✅ `Namoz.counts` gates mark, streak, best, completion, alarms (`nextStart`). ⚠️ `isNamoz` falls back to the title (`HabitBoard.kt:41`): any daily habit named "Asr"/"Shom" joins the group |
| Timer | ❌ fix 3 |
| Freeze | ⚠️ 2 a month across all habits ✅ (`HabitPrefsRepository.kt:27-32`); a future day can be frozen (strip lets you select it) and then counts as held in `markOf` (`:50` before the FUTURE check) |
| Take back | ✅ one tap off the selected day with a toast (`HabitsViewModel.kt:198-201`); label says "today" (fix 8) |

## Design pass (apple-design + emil-design-eng)

| Before | After | Why |
|---|---|---|
| Check pop `spring(dampingRatio = 0.5f)` (`HabitsScreen.kt:617`) | damping ~0.8, or 1.0 | bounce only after a momentum gesture; a tap is not a flick |
| Strip jumps a week after an 80 px drag | 1:1 drag + spring + flick velocity | continuous feedback, velocity hand-off |
| Partial ring = today ring | today a filled soft disc | one look, one meaning |
| Done habits stay in place, greyed | keep (Boss's ref does the same) | spatial consistency ✅ |
| Glass panes on lists | ✅ ink on near-white glass, text contrast holds | RULES "glass only on chrome" mostly met |

## Contrast (WCAG, computed from the hex in code / pixels)

| Pair | Ratio | Bar |
|---|---|---|
| Muted #5B6275 on white (progress, subtitles) | 6.09 | ✅ 4.5 |
| Ink on CHAIN #66DDA8 (full-day numerals) | 10.59 | ✅ |
| CHAIN #66DDA8 fill/bar on white · on PrimarySoft | 1.68 · 1.48 | ❌ marks 3.0 |
| Primary #1C7E69 arc on white | 4.96 | ✅ |
| Future/off numerals (Muted @ 60 %) | 2.58 | ⚠️ informational, not disabled |
| Streak #C2410C on white | 5.18 | ✅ |
| Amber never-miss dot #F59E0B on white | 2.15 | ❌ 3.0 (ring it in #B45309 or darken) |
| Unticked ring Ink @ 55 % | 3.99 | ✅ · disabled ring Ink @ 18 % 1.47 (ok: disabled) |
| ICE #CDE8FB frozen fill on white | 1.27 | ❌ add an ice icon/outline ≥ 3:1 |
| PrimaryInk on PrimarySoft ("Start tracking") | 7.00 | ✅ |
| Icon glyph vs tile: glass P 512 / 48 px | 1.46-1.80 / 1.40 | flat P 2.11; Humo body/tile 2.56 / 1.9 |

## Icon verdict

- **vs the Humopedia icon finish (Standing-wave mint, `wave-mint-512.png`): WORSE.** Humo: crisp edges, a lit tile rim,
  body/tile 2.56:1 (1.9 at 48). PAGEL: a soft blurred bevel that reads as frosted resin/clay, 92 → 62 % white so nothing shows
  through (not glass), and a dark inner shade (`make_svg.py:49-62`, #03463F @ .38) that brain card `icon-rebuild-1to1` rule 12
  forbids ("light on edges, never a dark outline or inner shade"). The tile's brightest pool (`aur1` cx .22 cy .16, `:19`)
  sits right behind the stem top: 1.50:1 there.
- **vs round D's flat P at 512: BETTER as an object** (depth, lift shadow, the glossy bead is the best part), **WORSE in
  clarity** (2.11 → 1.46:1).
- **At 48 px: WORSE.** 1.40:1 vs 2.11:1; the P becomes a pale smudge on the light top-left, the bead is what you read.
- The board shows 240 + 48 px, not the 512 + 48 the brief asked.

Fixes, in order: (1) move the light pool to the tile's corner rim (`aur1` cx .08, cy .06, r .35) and keep the tile under the
glyph at mid teal (OKLCH L ≈ 0.60); (2) make the body clear, not white: stops .92/.74/.62 → ~.50/.38/.30, and refract for
real: a 1.06× copy of the tile clipped to the P plus a 2-4 px `feDisplacementMap` at its edges; (3) crisp edge light instead
of the blurred bevel: a 2 px inner rim white .95 top-left → .25 bottom-right, a bright mint caustic low-right, delete the
#03463F/#0E8F78 inner shades; (4) a separate 48 px master (body .9, 1 px rim, no blur, stem +8 %, bead +10 %); (5) centre the
glyph + bead group optically (it sits high-left in the squircle); (6) run `check_icon.py`, rebuild the board at 512 + 48,
then a checker, then Boss.
