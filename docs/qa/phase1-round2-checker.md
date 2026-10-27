# Phase 1 · round 2 checker (CI 40578039697, d382c90 = app-v0.5.1) — score 7.5/10

Evidence: C:\Users\Murodjon\AppData\Local\Temp\claude\D--10-projects-pagel\22fc5724-653d-478c-adf2-75e5e8a3f532\scratchpad\checker2\
(shots\build\outputs\roborazzi\*.png, smoke\walk\*.png). Path to 9 = fix 1-6.

## Fix list (screenshot → what → fix → rule)
1. walk/29-font-1.5.png: Notebook tab label cut "Noteboo'" at 1.5× (cap 1.2× at AppShell.kt:213 not enough on 320 dp).
   Cap label at 1.0× when the tab slot < 72 dp, or autoSize. Rule: 1.5× font, no clipped text.
2. walk/21-panel-date-sheet.png: date sheet overflows the real emulator; time row below the fold. Drop the 6th calendar row
   when the month needs 5; DayCell 40 dp (Pickers.kt:199); quick days as one chip row. Rule: fits 360 dp.
3. picker-time-wheel-360.png: selection band = outlined text field (white .72 + 1 dp mint rim, TimeWheel.kt:105-107).
   Make it an Ink .06 fill, radius 10, no border. Rule: Apple feel.
4. same: wheel narrow and short. Drums 96-110 dp wide, VISIBLE = 7, rows ~34 dp, cameraDistance = 8*density, remove the ":"
   (TimeWheel.kt:76-77,111,138,147). Rule: iOS UIDatePicker proportions.
5. picker-date-time.png: when the wheel opens the calendar vanishes, the only way back is the time chip; "Add time" writes 09:00
   at once (Pickers.kt:149-152). Show "Thu 29 · 15:30" chips (day chip returns to the calendar); commit a time only on the first
   wheel change. Rule: every tap does what it says.
6. Departure Mono (pixel face) on WORDS ("Tuesday 27 October", "this week", "someday", "Never", "Every Tue, Thu", "what the AI
   reads"): labelSmall is mono everywhere (Glass.kt:50). Web: Departure Mono is "for numbers" (dashboard layout.tsx:8).
   labelSmall → body face; add a `numbers` style only for dates/times/counts. Rule: Boss retired pixel fonts; likely the last "2005" feel.
7. picker-repeat.png: weekday toggles float outside a pane while the monthly grid sits in one. Same Pane for both.
8. panel.png / walk/22: Priority row stacks label over chips at 1× on 320 dp; selected chip is a red-ring pill unlike every
   other ✓/fill. Make Priority a nav row "P1 ›" opening the priority menu (like Date, Repeat). One selection mark, one row family.
9. today.png vs project-folds-open.png: "↻ week" on Today vs "Weekly" on project. Use Repeat.label() in Today meta.
10. walk/27-landscape.png: tab bar ~30 % of height, covers the 2nd task, FAB overlaps list → compact bar in landscape + list
    bottom padding = bar height. walk/30-dark.png: dark ignored → say "light only" in RULES.md (dark theme = Phase 9).

## Still partly open from round 1
- Panes still near-white at the bottom (PaneTint #EEF9F4 .55, Glass.kt:31) in today.png, project-folds-open.png.
- Words: "Shows on its date", "what the AI reads", "The real reason, saved in the brain", two-line red sentence after "too tired"
  (panel-wont-do.png) → one short line or an icon.
- Add-sheet chip row still hard-cuts the project chip at the edge (add-typed-font-1.5.png, walk/14); typed text cuts with no fade.
- Add task: parsed time missing on the date chip ("tomorrow 3pm" shows only "30 Oct"); the web chip shows the time.
- Past calendar days ink .28 ≈ 1.9:1 → ~3:1 like iOS.

## Parity notes
- Web repeat-picker.tsx has no monthly-days UI though lib/repeat.ts parses "month 1,15" → web gap (dashboard), fine to add there too.
- Server d8be98c (monthly days) deploy: done by the lead.
