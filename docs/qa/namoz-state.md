# Namoz page: state (builder, 2026-10-30)

Branch `feat/namoz` (pagel-android). Brief: `D:\10_projects\pagel\docs\state\briefs\namoz-page-brief.md`.
No local Gradle was run; CI is the only compiler.

## What each part does
- **Data (Room v11):** `namoz_marks` (how a prayer was prayed: On time / Later / In the mosque), `namoz_learning`
  (his own lines, ticked "learned"), `namoz_settings` (one row: place, method, Hanafi Asr, offsets, Qaza, Show on Today).
  The tick itself is the habit's tick (habit_ticks + outbox to pagel.uz).
- **Prayer marks stay on the phone for now:** pagel.uz has no field for them. The learning list and settings are phone-only too.
- **Times:** `data/habits/Namoz.kt`, Adhan with the Uzbekistan method by default (checked against the Tashkent table),
  Muslim World League / Karachi, Hanafi or Shafi'i Asr, per-prayer minute offsets, Tashkent or the phone's last place.
- **Page (`ui/namoz/`):** header = "Namoz" + date + Hijri (computed). Next prayer card with the countdown, updated once a
  minute (`namozMinutes()`). Five rows: empty ring, tap = prayed, long press = On time / Later / In the mosque / Clear.
  The current prayer gets a faint mint wash and hairline; a ticked row a soft mint fill. Before 1 November: times +
  "Tracking starts 1 November", rows disabled, no stats.
- **Stats (`NamozStatsPane.kt`):** "n/m full days this week", per-prayer bars for the last 30 days, a month grid of 5 dots
  per day (always 6 rows, swipe or arrows for other months), "n in the mosque this month".
- **Settings (⋮):** reminders per prayer (off by default; notice "Asr · 15:42", lock screen shows only "Reminder"),
  offsets, method, Asr school, location, Show on Today, Qaza counter (off by default, his own number, − / +).
- **Learning:** a list he fills himself, tick = learned, delete. The app writes no text into it.
- **Today:** a quiet line "Asr · 15:42 · in 1 h 03 m · 2/5" once tracking has started and Show on Today is on.

## Walls kept
His own ticks only · reminders off by default · no red, no "missed", no streak or judgement words (test
`noShamingWords`) · a missed prayer is an empty ring · no AI-written religious text · prayer names hidden on the lock screen.
