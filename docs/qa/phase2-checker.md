# Phase 2 checker · Inbox sorting (CI 40604324105, da2a087) · score 7.5/10

**Why 7.5:** the sorting loop works. All six choices, swipes, Undo, the ring and offline sorting through the outbox are
proven on the emulator. But at 1.5× font the AI-pick button is off the screen. Every toast covers that button for up to
5 s. Chips sit under the tab bar on a 640 dp screen. The green CI run passed partly because of the clock.

Evidence: `C:\Users\Murodjon\AppData\Local\Temp\claude\D--10-projects-pagel\22fc5724-653d-478c-adf2-75e5e8a3f532\scratchpad\checker-p2\`
(`shots\build\outputs\roborazzi\*.png` = 1080×2340 = 360×780 dp; `smoke\walk\*.png` = the 640 dp emulator).
Skills applied: apple-design, emil-design-eng. Path to 9 = fixes 1-6.

## Top 10 fixes (screenshot → what is wrong → fix at file:line → rule)
1. **inbox-card-360-font-1.5.png**: at 1.5× the AI-pick button starts at ~774 of 780 dp, so only its purple top edge shows.
   On a phone it sits under the tab bar. The picture budget ignores the font scale: `InboxScreen.kt:460-462`
   `(screenH − 540).coerceIn(100, 220)`. Fix: subtract `(fontScale − 1) × ~320 dp`, let the picture go to 0 (hide it
   below 96 dp), and use 1 caption line at ≥ 1.3×. Better: measure the space left with BoxWithConstraints.
   Rule: works at 1.5× font; the main action never hides.
2. **walk/26, 28, 30, 32**: every toast ("Dropped · Undo", "Added to Humopedia", "Noted…") lands on the next card's
   AI-pick button. The host sits at `fabBottom + 56 + 10 dp` (`AppShell.kt:261-265`), even on the Inbox tab, which has no
   FAB. Drop's toast blocks the main button for 5 s. Fix: on the Inbox, put it at `fabBottom` (over the chips), or anchor
   it at the top under the header. Rule: respond-first; a toast never covers the primary action.
3. **The "1/20 after 4 sorts" in run 40593957979 was a day mismatch, not the offline leak alone.** `fake-pagel.py:44`
   sends `day = time.strftime(...)`, which is the runner's UTC day. The phone's "today" is Asia/Tashkent
   (`InboxRepository.kt:43`). That run started at 22:19 UTC (03:19 the next day in Tashkent). The leaked network let
   `refresh()` (`InboxDao.kt:146-158`) write yesterday's `day` back after every sort (deck < LOW = 8). Then `choose()`
   (`InboxDao.kt:94-95`) saw `s.day != today` and reset the count to 0 + 1 → 1/20. Run 40604324105 passed only because the
   walk ran at 00:00-00:03 UTC. Any run between 19:00 and 24:00 UTC will fail again.
   Not a production bug: the server uses the Tashkent day (`inbox-api.ts:14`).
   Fix: the fake uses `datetime.now(ZoneInfo("Asia/Tashkent"))`, and the walkthrough checks that the header goes N → N+1.
   **A small real race also exists:** `refresh` (`InboxDao.kt:158`) saves counts from a GET that left before a POST
   landed. If `sent()` already removed that row, the ring steps back by 1 until the next reply. Fix: stamp the GET's start
   time; when a `sent()` came after it, keep `max(current, server + waiting)`.
4. **walk/30-inbox-action-sent.png**: card ib5 has a picture (`fake-pagel.py` pic=True) but shows none. `fake-pagel.log`
   holds only 2 picture GETs (ib1, ib3). `InboxViewModel.kt:131-134` stores `null` for a failed (offline) fetch, which means
   "no picture" for the life of the screen, and it pre-fetches only 3 cards. Fix: `InboxPictures.load` returns
   NONE vs UNAVAILABLE; never cache UNAVAILABLE; retry when the link comes back; pre-fetch ~10 while online (it is offline
   for "a subway ride"). Also `InboxSheets.kt:149` says "With the picture" when no picture is shown (walk/31).
5. **walk/25, 27, 33**: on 640 dp only "Action · Note" are above the glass bar. "Do now / Later / Drop" sit under it, so
   Boss must scroll. Fix (`InboxScreen.kt:567-571`): one row of 5 icon buttons, 48 dp each, label under the icon or as an
   a11y label only. One icon beats many words.
6. **inbox-card-360.png, walk/25**: the caption ends in "…" but no "Read all" shows. The link shows only when
   `card.text.length > 160` (`InboxScreen.kt:501`), but 2-3 lines hold only ~110-140 characters. Fix:
   `onTextLayout { overflow = it.hasVisualOverflow }` → show "Read all". Rule: nothing cut without a way to read it.
7. **inbox-card-360.png, inbox-keeps-full.png, inbox-ring-20.png**: the header line wraps to "· 2/5 / learned" and
   "5 learned / ✓" (a lone ✓). Streak, help and the 58 dp ring squeeze it (`InboxScreen.kt:226-236`). Fix: use NBSP in
   "2/5 learned" and "learned ✓", and give the counts line the full width under the title row.
8. **inbox-empty.png**: "214 left" above "Inbox zero" says two opposite things. `left = max(total − leaving, deck.size)`
   (`InboxViewModel.kt:250`) keeps the server total when the local deck is empty. A real phone shows exactly this until
   dashboard ff7de79 is deployed, and also offline after the local deck runs out. Fix: deck empty + total > 0 → a
   cloud-off icon + "214 more when online"; hide "left"; no spinner.
9. **walk/26 toast**: the Undo pill is ~34 dp tall (`Toasts.kt` Undo Row: 8 dp padding + 18 dp icon, no `heightIn`), so it
   is under the 48 dp target. Fix: `heightIn(min = TouchMin)` on the clickable.
   Motion in the same family: a swiped card leaves on a fixed `tween(380, CubicBezier(.5,0,.7,.4))` (`InboxScreen.kt:428`).
   That is an ease-in curve that drops the finger's speed. Fix: hand the release velocity from `SwipeCard` onEnd to
   `LeavingCard` and use `animateDecay` or a spring with `initialVelocity` (apple-design §5 velocity handoff; emil: no ease-in).
10. **Words and contrast** (walk/32, inbox-keeps-full.png):
    - The big button's line "AI's pick · in your words, then recall" wraps to 2 lines on 320-360 dp.
    - The keeps-full line "5/5 learned today, so a note · one line to remember" also takes 2 lines.
    - White at .92 on #7C4DFF (the gradient's light end) measures **4.36:1**, under 4.5. On #D1453B it is ~4.1:1.
    - Fix (`InboxScreen.kt:585, 599`): a small sparkle icon for "AI's pick", the hint alone, full white. Full case:
      "5/5 learned · one line".
    - Also `InboxSheets.kt:150` "P1, this week" repeats the segment. Drop it.
    - The selected segment's 1 dp mint border (`InboxSheets.kt:188`) is the outlined-field look Phase 1 #3 removed. Use
      white + a soft shadow, no border.

## Parity gaps vs the web (what /learn does that the app does not; not bugs)
- **The AI's read of unread saves.** The web starts a triage of the next 25 after 8 s ("The AI is reading N saves",
  learn-client.tsx:156-169). The phone never does. With ~950 unread saves, phone-only sorting meets "The AI has not read
  this one yet" and the big button disappears. This is the most important gap: the AI pick is the core of this phase.
  It needs either a server cron or `POST {action:"triage"}` from the app.
- **Research of kept saves** (`Deepen`, learn-client.tsx:456) runs only while the web page is open. Keeps made on the
  phone wait until Boss opens /learn on the laptop.
- Shuffle / "New order" button (the phone shuffles once per app run).
- Filters by platform and field (facets with counts).
- Paste box: a link, a thought, or a picture as a reference note.
- Drop or paste a picture onto a card to attach it to a Note.
- Recall questions ("N questions to recall") and "% remembered".
- "clear N older than 14 days".
- A tappable "Saved notes" link in the Note form (the app shows plain text).
- **Matches the web:** all six choices with the web's words and colours; the AI's pick as the big button; the unread
  message; the Learn→Note swap at 5/5; Learn and Later disabled correctly; 6-word minimum ("3 more words"); prefilled
  Action and Note lines; This week / Someday; Do now "Open it"; Drop Undo 5 s; the cheers at 5/10/25/50/100; the goal
  toast; streak; per-choice sound. The web's More/Less is the "Read all" sheet here.
- **Server:** dashboard ff7de79 was **not deployed** when the builder wrote START HERE v9. Until it is, the real phone shows
  inbox-empty.png's state.

## Counters and offline (A)
- Respond-first ✅: walk/25 → 26 → 27 → 28 → 30 → 32 read 12 → 13 → 14 → 15 → 16 → 17 /20 at once, offline. The ring
  moves with the count.
- Offline order ✅: steps.txt:45-47 "5 sorted offline, none sent yet" → "pagel.uz got all 5 in order after ~3 s:
  drop:ib1 later:ib2 drop:ib3 act:ib4 note:ib5". The Drop's 5 s hold did not reorder anything.
- Swipe stamps and tilt fit (inbox-mid-swipe.png LEARN, inbox-mid-swipe-drop.png DROP). The AI pick stays clear of the bar
  at 1× (walk/25, inbox-mid-swipe-drop.png) but not at 1.5× (fix 1).

## Phase 1 round 3 re-check (phase1-round2-checker.md)
| # | Item | Now | Proof |
|---|---|---|---|
| 1 | "Notebook" cut at 1.5× | ✅ full word | walk/40-font-1.5.png |
| 2 | Date sheet overflow | ✅ 5 week rows, "Add time" visible | walk/22-panel-date-sheet.png |
| 3 | Wheel band = outlined field | ✅ ink fill, no border | picker-time-wheel-360.png |
| 4 | Wheel narrow, ":" | ✅ 7 rows, no ":" | picker-time-wheel-360.png |
| 5 | Calendar vanishes, 09:00 written at once | ✅ "Thu 29" + "15:00" chips (save-on-first-turn seen only in code/test) | picker-date-time.png |
| 6 | Mono on words | ✅ dates and repeat in the body face; mono only on 2/3, +2, ring | today.png, picker-repeat.png, panel-wont-do.png |
| 7 | Weekdays outside a pane | ✅ in a pane | picker-repeat.png |
| 8 | Priority stacked chips | ✅ "⚑ P2 ›" nav row | walk/18-panel-open.png |
| 9 | "↻ week" vs "Weekly" | ✅ "Weekly" | today.png |
| 10 | Landscape bar / dark | ⚠️ compact bar ✅, but at scroll 0 it sits on row 3 and the FAB overlaps the card edge; dark = "light only" RULES.md:96 ✅ | walk/38-landscape.png |
| open | Pane foot near-white | ✅ mint light at the foot | today.png |
| open | Long won't-do words | ✅ one line + icon | panel-wont-do.png |
| open | Add-sheet chip row hard cut | ✅ right fade; ⚠️ the typed text is still hard-cut on the left ("ll Aziz…") | add-typed-font-1.5.png, walk/15-add-typed.png |
| open | Parsed time missing on the chip | ✅ "Tomorrow 15:00" | walk/15-add-typed.png |
| open | Past days 1.9:1 | ✅ visibly darker (~3.4:1 claimed) | walk/22-panel-date-sheet.png |

## Broken or clipped states
- inbox-card-360-font-1.5.png: AI-pick button off-screen (fix 1).
- inbox-card-360.png: the chip row is cut at the bottom edge even at 780 dp; Later and Drop are below it.
- walk/25, 27, 33: chips under the glass bar (fix 5).
- walk/26, 28, 30, 32: toast over the AI pick (fix 2).
- inbox-guide.png: the pane is cut flat at the sheet's 640 dp cap. The Drop row and the GTD line are hidden, with no fade
  to hint at the scroll (`InboxSheets.kt:206`). Add a bottom fade or let the sheet grow.
- inbox-mid-swipe.png: the card behind is an empty glass pane, a blank slab as the top card moves away
  (`InboxScreen.kt:309-313`). Draw the next card's site pill and claim faded, or no back card.
- inbox-empty.png: "214 left" + "Inbox zero" (fix 8).
- Header line wraps (fix 7). walk/15: typed text hard-cut at the left.
- walk/38-landscape.png: the bar sits on row 3 at scroll 0.

## Design audit (C)
- **Glass ✅.** Pane = white .32 + conic rim + Shadow .45 (`Surfaces.kt:101`). In walk/25 the card reads as glass and the
  clouds show through. Chips are white .72 (`InboxScreen.kt:621`): more card than glass, but one consistent chip family.
- **Green slabs ⚠️.** Action and Note use the same solid #1C7E69 (`InboxScreen.kt:116, 118`; the same as the web,
  globals.css:1462). So do "Add to plan" and "Save note". Under Boss's rule ("white, ink, or bright mint") this is the dull
  green he bans, and the two choices differ only by icon. Ask Boss: bright mint gradient for Action, ink for Note?
- **No spinners ✅.** The picture skeleton breathes only while an image loads (`InboxScreen.kt:510`). No "Syncing".
- **Toasts ✅.** Light glass (white .64 + mint rim) + check icon; red Ban icon when refused (`Toasts.kt`). Undo target ❌ (fix 9).
- **Contrast** (sRGB luminance):
  - White on Action #1C7E69: 4.97 ✅. On Learn #7C4DFF: 4.82 ✅. On Do now #B25E00: 4.67 ✅.
  - White on Drop #D1453B: 4.54 ✅ (just over 4.5). On the flame #C2410C: 5.18 ✅. On the Instagram pill #C13584: 5.08 ✅.
  - Muted #5B6275 on the pane: ~5.7 ✅.
  - The .92-white sub line on Learn: 4.36 ❌; on Drop: ~4.1 ❌ (fix 10).
- **Targets.** Chips 48 dp ✅ (`TouchMin`), links 48 dp ✅, Undo 34 dp ❌.
- **Type ✅.** Departure Mono only on the ring, the streak and step counts.
- **Words.** Mostly short. Too long: the guide (the web's text), the sub lines (fix 10), "Noted. It is in Notebook →
  Saved notes." (2 lines in walk/32).
- **Motion.**
  - ✅ The drag follows 1:1 with tilt and a stamp.
  - ✅ Threshold haptic tick; fling detection; spring-back (damping .62).
  - ✅ A haptic and a sound per choice; reduced motion skips the exit.
  - ❌ The exit ignores the fling's speed (fix 9).
  - Swipe up on an already put-off card does nothing (no stamp, no rubber band). Add a resisting rubber band
    (apple-design §9).
- **Frames.** walk/gfxinfo: 90 % janky, p50 150 ms on CI's software GPU. Not a phone number: measure on the A05s before
  calling it fast.
