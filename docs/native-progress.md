# PAGEL native app: progress

> Plan: `docs/NATIVE-PLAN.md` · Rules: `docs/RULES.md` · Branch `feat/native-app` (never main, never an `app-v*` tag).
> The judge is CI: `gh run list -R tolipovmurodjon/pagel-android --branch feat/native-app`.

## 2026-10-27 · server session (pagel-server, Boss's laptop battery dead)

### Phase 0 · Stop the crashes — built, CI-proven; Boss's 10 opens still to do

| Plan item | What changed | Proof |
|---|---|---|
| Release only from main / `app-v*` | `.github/workflows/release.yml`: triggers `main` + tags `app-v*`; a manual run elsewhere is skipped by an `if:`; versions 0.3.x | workflow file |
| Updater asks first | `update/UpdateWorker.kt` downloads + verifies, then notifies "PAGEL x ready · Tap to install"; the tap opens PAGEL (`MainActivity.handle`), which hands the APK to Android's confirm screen (`USER_ACTION_REQUIRED`). Never installs by itself | `UpdateLogicTest` (verified download, manifest round trip) |
| Crash capture | `crash/CrashRecorder.kt` writes every uncaught crash to `files/crashes/` (newest 20); `crash/CrashUploader.kt` posts them to `POST /api/v1/app/crash` with the device token on the next start | `CrashLogTest`, `CrashUploaderTest`; emulator: forced crash → file written → sent after pairing |
| SavedSignIn / WebView / runBlocking off Main | `SavedSignIn.open()` on IO (wiped + rebuilt once if the Keystore refuses); WebView built after the first frame inside `runCatching` with a Try again screen; `GatewayModule` no longer reads DataStore with `runBlocking`; `AnchorService` starts the gateway with the saved URL **and token** on IO (it used to pass no token) | emulator smoke |
| First frame | window background is the page colour `#FBFDFC` (was black: one of the "black screen" reports) | `res/values/themes.xml` |
| CI emulator smoke | `build.yml` job `smoke` (ReactiveCircus/android-emulator-runner v2, ★1.3k, Apache-2.0, vetted), script `.github/scripts/emulator-smoke.sh`, evidence artifact `smoke-evidence` | see CI table below |

Phase 0 done-when: emulator job green ✅ (CI) · forced test crash reaches the crash endpoint ✅ (emulator → fake endpoint;
live pagel.uz endpoint proven with curl) · **Boss opens 0.3.0 ten times, zero closes: not done, needs a release from
main, which is Boss's call.**

### Phase 1 · Foundation

| Step | What changed | Proof |
|---|---|---|
| 1 Skills + rules | `docs/RULES.md` (laptop session, 390e1d2 + 31a3c3a). Read on the server from `github.com/android/skills` (45fd173): agp-9-upgrade, navigation-3 sources | — |
| 2 Toolchain | AGP 8.13.2 → **9.3.3** (built-in Kotlin, no kotlin-android plugin), Kotlin 2.0.21 → **2.4.21** (root buildscript classpath), Gradle 8.13 → **9.5.0** (AGP 9.3's own minimum), compileSdk 36 → **37**, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00 (Compose 1.12.1), + Navigation 3 1.2.0, Room 2.8.5, backdrop 2.0.1, Robolectric 4.17, Roborazzi 1.77.0. Every version read from Google Maven / Maven Central that day | CI run 40485580870 `build` ✅: all old unit tests green on the new toolchain |
| 3 Server | dashboard `feat/gtd-core` a8e79b0: `devices` + `pair_codes` tables, `POST /api/v1/devices/pair`, device tokens in `apiAuthed`, `POST /api/v1/app/crash`, `GET /api/v1/stream` (bearer, `id:` + `?since=`), `GET /api/v1/sync?since=`, web page **Phones** (More → Phones: Connect phone code, last seen, Revoke). **Deployed** to pagel.uz with Boss's yes (DB backups `~/pagel-data/backups/pagel-pre-devices-20261027.db`, `pagel-pre-resources-20261027.db`); Resources import applied (127, re-run adds 0) | vitest 239/240 (the 1 = `skills.test.ts` gstack, fails before and after on this server); live check below |
| 4 Data layer | `data/`: `PagelApi`, `SseParser`, `DataError`/`Outcome`, `Account` in an EncryptedFile (IO only), Room (`projects`, `tasks`, `steps`, `sync_state`), `SyncEngine` (cursor pull → live stream → delta pull; backoff 1-60 s with jitter; revoked token signs out), `Session` (sync only while PAGEL is on screen; uploads saved crashes) | `SyncEngineTest` (web tick → Room row < 3 s), `PagelDaoTest`, `SseParserTest` |
| 5-6 Glass + design system | `ui/glass/`: catalog `LiquidBottomTabs` + `DampedDragAnimation` + `InteractiveHighlight` (Apache-2.0, credited) in mint; tokens from the web; bundled Inter Tight, Schibsted Grotesk, Departure Mono (OFL); aurora page; OKLCH tile gradients = the web's table; tab icons from the web's SVG paths | `TileColorsTest`; Roborazzi `GlassScreenshotTest` (bar, pairing, projects, 1.5× font) → artifact `screenshots-and-reports` |
| 7 Shell | `ui/shell/`: Navigation 3, one back stack per tab, back exits through Today, re-tap scrolls to top; Today = project list from Room; Inbox / Habits / Notebook open their pagel.uz page until their native feature lands; pairing screen | emulator: pairing reaches the server (pair → sync → stream in the fake log) ✅; project list on screen ❌ blocked by the freeze below |

Live check on pagel.uz (through Cloudflare + nginx, 2026-10-27 10:28 UTC; a test device, revoked after):
pair 201 → full sync 200 (16 projects, 599 tasks, 359 KB, 0.12 s) → a new event reached `/api/v1/stream` in **0.63 s**
→ delta sync (full false) → revoke → its token 401. Walls: sync/stream/crash without a token 401; Phones page and the
code maker behind the nginx password (401). lexion.uz 200, 4 `law-*` units running. A test event "E2E.check, not a
crash" sits in the events table (kind app_crash, actor e2e-check).

Phase 1 done-when: pair with a code from pagel.uz and see the project list on a glass shell: **server side proven live;
app side proven on the CI emulator against a fake pagel.uz; on Boss's phone not yet (needs an install)** · screenshot
tests pass in CI · tick on the web → phone Room row in ≤ 3 s: JVM test against a fake stream + live stream 0.63 s.

## START HERE v11 · PHASE 4 OF 10 = UPCOMING · COMPLETED · OVERDUE, + THE PHASE 3 CHECKER'S FIXES (2026-10-28, builder agent)
The web's `/upcoming` and `/completed` on the phone (`ui/upcoming/`, `data/upcoming/`), offline-first, plus the Phase 3
checker's top 10 (`docs/qa/phase3-checker.md`).
- **Where it lives: More → Upcoming, More → Completed.** The web's phone bar keeps exactly Today · Inbox · Habits · Notebook
  in the bar and Upcoming/Completed under More (dashboard `nav.tsx` `PHONE`), so the phone does the same. A segmented switch
  on Today was the other option; it would make Today two screens and break "Today = 3 at a time". Overdue is the top section
  of Upcoming, as on the web (`upcoming/page.tsx`).
- **Server** (dashboard `feat/gtd-core`): **e759107 (URGENT)** the AI read is bounded: `apiTriage` skips a file asked for in
  the last 24 h and starts at most 24 reads a Tashkent day (app-v0.7.0 asked every minute with no end, up to 1,440 Claude
  runs a day on the box lexion.uz shares). **34c6704** `nextAfter` (lib/repeat.ts): a tick moves a habit to its first date
  after today, in task-store.ts and plans.ts; `PATCH {done, day, if_due}`: an offline tick counts for the Tashkent day it was
  made (today or yesterday; older or bad = today) and does not move a habit whose date already moved (the web ticked first);
  `repeatLabel("monday")` = "Mondays"; `GET /api/v1/completed?before=&limit=` (sync.ts `completedPage`): finished tasks newest
  first, each with its Tashkent `day`, paged by `before` = the last done_at. vitest: repeat 3 new, task-api 4 new (late habit
  Tue→Thu, 23:50 Tue sent 00:10 Wed counts Tue, if_due mismatch moves nothing, an old day counts today), sync 2 new (an
  explicit +05:00 boundary: 23:59 and 00:01 Tashkent), inbox-api 2 new (24 h skip, day cap reset at Tashkent midnight); the
  touched files' tests all green; `tsc --noEmit` clean. The full run's 13 failures are git tests at the 5 s timeout on the laptop;
  the same files pass serially with a 60 s timeout (77/77). **DEPLOY: see the report; until then** the old pagel.uz ignores
  `day`/`if_due` (it still names `done` in `applied`, so nothing is held or rolled back) and answers 404 for /completed
  (Completed shows Room's 14 days from /sync, silently).
- **Upcoming** (`UpcomingScreen.kt`): "Next 7 days" · Upcoming · "N planned · M overdue"; a glass **week strip** pinned under
  the title (weekday, the date in a circle, a mint dot under a day with tasks; today = bright mint with an ink number; a tap
  scrolls to that day), then Overdue (red title, **Move all to today** pill, oldest first) and the 7 days ("Today ·",
  "Tomorrow ·", "Friday 30 Oct"; "Nothing planned"). A habit shows on every day it falls on (`Repeat.datesBetween`, a port of
  the web's) with its repeat mark instead of a ring (it is ticked on Habits). Rows: ring (done from here: `PATCH {done:true}`
  through the panel's outbox, so its Undo never puts it on Today), title, time or late date, repeat, project tile.
- **Drag to reschedule**: long-press a row (haptic LongPress, it lifts to a white card at 1.04×, the row dims to 35 %), drag
  onto the strip; the day under the finger fills ink and grows (spring), one SegmentTick per day crossed
  (`stripDayAt`, with 28 dp of slop above and below); let go on a day = Confirm haptic, the card springs into the cell and
  shrinks away, Room moves the task at once (`movedTo` keeps its time), one outbox `PATCH {due}`, toast "Moved to Fri 30 Oct ·
  Undo". Let go elsewhere = it springs home. "Move all to today": each overdue gets today (time kept), one toast "2 moved to
  today · Undo"; Undo takes back what still waits on the phone, newest first, else sends the old date.
- **Completed** (`CompletedScreen.kt`): "N tasks done", panes by the Tashkent day it was finished (Today, Yesterday, weekday
  names for the last week, then "2 October", another year with its year), newest first, the time done (14:32, Tashkent),
  the project tile; the filled check (bright mint, ink tick) = untick: `PATCH {done:false}` (pagel.uz's undone), Room first,
  "Back on your list · Undo". Tap = the task panel, **read only for a done task** (`DoneContent`: title, finish line, "Done
  today 09:12", **Reopen**, steps and note as text; no Won't do). Older than /sync's 14 days come from
  `GET /api/v1/completed` (200) when Completed opens; `applyCompleted` never overwrites a task with a change waiting.
- **Offline**: all three read Room only (`PagelDao.datedOpen`, `completed`); every change is a TaskRepository edit (Room first
  + outbox), so moves made offline reach pagel.uz in the order made (`UpcomingRepositoryTest`). No Room migration: no new
  table or column.
- **Phase 3 checker fixes**: #1 `Repeat.nextAfter` (late habit ticked → next date after today) both sides; #2 the tick's
  PATCH carries `day` + `if_due`; #3 no tick and no ring for a habit already ticked today (`HabitsRepository.tick`,
  `HabitAlarmReceiver.fire`); #4 the AI read only while the Inbox is started (`LifecycleStartEffect` → `onScreen`) and at
  most 3 taken asks per unread set (`ReadAsks`), + the server bound above; #5 tick = one `@Transaction` (`tickHabit`), a
  finishing habit counts as done in the header and is not "Coming up"; #6 permission denied → the sheet shows a 48 dp red row
  "Notifications off · Turn on" (→ app notification settings), no exact grant → "Exact time · Allow" (→ the exact-alarm
  page), the exact ask only after a yes; #7 grants read again when the sheet opens, each alarm in its own try with a
  `setWindow` fallback on SecurityException, CI asserts `dumpsys alarm` gains a `HABIT_FIRE` alarm; #8 400 days of ticks
  (`DAYS`), a refresh keeps the ticks of a habit touched after its GET left; #9 bright-mint done discs and switch (no
  #058527, no solid #1C7E69 track), the AI pill (white glass, mint rim, breathing sparkle, "AI is reading this one"), X's own
  logo; #10 panes slide (`animateItem` + `animateContentSize`), day bar damping 1.0, lucide bell small icon + BigTextStyle,
  "Mondays", "Noted ✓". Also: the meta icons grow with the font (13 sp).
- **Not in this phase**: dropping a row onto a day *section* in the list (only the strip is a target); a TalkBack action
  for the move (the panel's date does it); paging Completed past 200 (one page; the web shows 200 too); Upcoming's habit
  rows are not tickable (Habits owns the tick); a midnight ticker on Habits (Upcoming/Completed re-read today each
  minute); snooze/offsets/history calendar for habits; the Inbox gaps from v9; the Action/Note #1C7E69 slab (Boss's open
  question, unchanged).
- **CI**: run 40626531451 (9f71886): build ✅ (unit + Roborazzi; new shots upcoming-360, upcoming-360-font-1.5,
  upcoming-no-overdue, completed-360, completed-360-font-1.5, panel-done-read-only, habits-reminder-notifications-off, all
  looked at); smoke ❌ only at "Move all to today" (the walk's scroll-to-top swipe used 1080-px coordinates on the 320-px
  emulator); before that it proved the drag (`PATCH t_u3 {"due":"<+2 days>"}` in ~1 s, walk 45 dragging / 46 "Moved to Fri
  30 Oct · Undo"), the habit tick with `day`/`if_due`, and `dumpsys alarm` 0 → 1 `HABIT_FIRE` (RTC_WAKEUP, exact). Fixed in
  the next commit: Today on the strip scrolls to the top (Overdue included); also the done ring in the panel is bright
  mint (was #058527), the X pill is the logo alone, a pane title wraps its action under it at 1.5x ("Overd/ue"). The run on
  the commit after this block is the judge (`gh run list -R tolipovmurodjon/pagel-android --branch feat/native-app`).

## START HERE v10 · PHASE 3 OF 10 = HABITS + REMINDERS, + THE PHASE 2 CHECKER'S FIXES (2026-10-28, builder agent)
The web's `/habits` on the phone, behind the Habits tab (`ui/habits/`), offline-first, plus reminders with a Done button.
- **Server** (dashboard `feat/gtd-core` **99b420a, DEPLOY NEEDED**): `GET /api/v1/habits?days=60` (`src/lib/habits-api.ts`:
  open habits with part of day + streak + done today, and the ticks by task id from the events history, `habit_done` adds
  the day, `habit_undone` takes it out, filtered by the event's own Tashkent day) and `POST /api/v1/inbox {action:"triage",
  files}` (`apiTriage` in `inbox-api.ts`: the web's own `triage()` from learn.ts, which already runs one Claude read at a
  time and lets a second caller join; at most one start a minute; 202 at once, the reads come with the next deck). A habit
  tick by id was already there: `PATCH /api/v1/tasks/<id> {done:true}` moves the date and logs habit_done; Undo =
  `{due: <old date>}` (logs habit_undone), as the web. vitest `habits-api.test.ts` 4/4 + 2 triage tests in
  `inbox-api.test.ts` (13/13 in both files); `tsc --noEmit` clean. Until it is deployed, pagel.uz answers 404 for habits →
  the phone still shows every habit (they come with /sync) and counts its own ticks; streaks from before the phone wait.
- **Room v5** (AutoMigration 4→5, `MigrationTest.fourToFiveKeepsTheInboxAndAddsEmptyHabits`; `4.json` is now in git, it was
  missing): `habit_ticks(day, taskId)`, `habit_reminders(taskId, enabled)`, `inbox_state.sentAt`.
- **How a tick works** (`data/habits/HabitsRepository.kt`): Room first: the habit moves to its next date by the web's repeat
  rule (`Repeat.nextDue`, a port of lib/repeat.ts with every repeat kind) and today is counted; then one outbox
  `PATCH {"set":{"done":true},"was":{"due":<old>,"untick":<day>}}`. Undo inside the outbox = taken back (pagel.uz never sees
  it); after = `PATCH {due: old}` with `retick` to roll back. A refusal puts the date and the day back (`applyWas`).
  `replaceHabitTicks` never overwrites a habit with a change still waiting. Ticked already today → no second tick.
- **Screen** (`HabitsScreen.kt`): "Every day, the same few things" · Habits · "N of M done today" + the day's bar; panes
  🌅 Morning / ☀️ Afternoon / 🌙 Evening / 🔁 Anytime (before 12 / before 17 / later / no time, as views.ts), each row the
  Today tick ring (ding, confetti, haptic), the words, time or "late" date, the repeat, 🔥 streak (`streakOf`, the web's
  Duolingo rule), and a bell when it has a time; tap = the Phase-1 task panel. The ticked one keeps its row 1.25 s, then
  moves to "Done today ✓"; "Coming up" lists later dates. The last one: the all-done card (🔥, "N habits ticked. Longest
  streak: X in a row.") with ding + confetti once a day on this phone. Salah: the web's /habits has no salah link, so none.
- **Reminders** (`reminders/`): the bell opens a sheet: "Remind me" switch + Apple time wheel (a new time = PATCH due).
  `HabitAlarms` plans one AlarmManager alarm per habit at `ReminderMath.nextFire` (its time today if still ahead, else the
  next day it repeats on, Tashkent): `setExactAndAllowWhileIdle` with SCHEDULE_EXACT_ALARM (Android 14+ denies it by
  default; asked once, at the first reminder turned on, via Settings; USE_EXACT_ALARM not used: Play keeps it for alarm and
  calendar apps), else `setWindow` 10 min and the sheet says so in red. POST_NOTIFICATIONS is asked at the same first use,
  never at launch. `HabitAlarmReceiver` (not exported, explicit + FLAG_IMMUTABLE PendingIntents, goAsync, 9 s cap): FIRE
  posts "<habit> · 07:30 · 🔥 3" with a **Done** action unless it is off or already ticked; DONE ticks through the same
  outbox (offline works) and clears the notification. Plans again after a tick, a switch, a new time, boot, an app
  update and an exact-alarm grant (`BootReceiver`).
- **Phase 2 checker fixes** (`docs/qa/phase2-checker.md` top 10): #1 the picture gives up ~320 dp per font step and hides
  under 96 dp, caption 1 line at ≥ 1.3× (test: the AI pick ends above the tab bar at 1.5×); #2 on the Inbox the toast comes
  in under the status bar, never over the AI pick; #3 the fake's day is Tashkent's, `choose` resets the ring only when
  pagel.uz has not spoken since midnight, `refresh` keeps `max` when a choice landed after its GET left (`InboxDayTest`),
  the walkthrough checks the ring goes N → N+1; #4 pictures: Shown / None / Unavailable, an unavailable one is never cached
  and is fetched again when the link is back, 10 pre-fetched, "With the picture" only when one shows; #5 the five other
  choices are one row of icons with the word under it; #6 "Read all" whenever the caption is cut (text overflow); #7 the
  counts line has the full width and no-break spaces; #8 empty deck with saves on pagel.uz = cloud-off "214 more when
  online", no "left"; #9 Undo is a 48 dp target, a swiped card leaves with the finger's speed (critically damped spring with
  its velocity); #10 the AI pick: sparkle + the hint alone in full white, "5/5 learned · one line", no "P1, this week", the
  chosen segment is white + shadow. Also: the card behind shows the next save's site and claim (no blank slab).
- **The AI read of unread saves** (the checker's main gap): when one of the next 5 cards has no pick, the Inbox asks
  pagel.uz to read the next unread ones (at most once a minute; offline, again in 15 s), says "The AI is reading it now",
  and refreshes the deck at +40 s and +80 s so the picks show. Walkthrough: ib8 (unread) asks after the link is back.
- **Not in this phase**: per-habit reminder offsets (only at its time) and a snooze; the web's "Coming up" shows every
  repeat's next day, the phone only open habits with a later date (same as the web's list); a habit's history calendar;
  the Inbox's other gaps from v9 (filters, Shuffle, clear old, paste, recall, research progress); the checker's smaller
  notes not in its top 10 (guide sheet bottom fade, swipe-up rubber band on a put-off card, the landscape bar on row 3, the
  Add sheet's typed text cut on the left); the Action/Note colour #1C7E69 (a question for Boss, unchanged).
- **CI**: (filled below)

## START HERE v9 · PHASE 2 OF 10 = INBOX SORTING (2026-10-28, builder agent)
The web's `/learn` on the phone, behind the Inbox tab (`ui/inbox/`), offline-first:
- **Server** (dashboard `feat/gtd-core` **ff7de79, NOT DEPLOYED**): `GET /api/v1/inbox?limit=&seed=` (the deck in the web's
  shuffled order + sorted/goal 20/kept/cap/min words/total), `POST /api/v1/inbox {action: keep|act|note|later|drop|now,
  file, sentence?, project?, someday?}` (the web's own `learn.ts` functions; a repeat or a save already sorted on the web
  answers 200 `already`, never a refusal), `GET /api/v1/inbox/picture?file=`. vitest `src/lib/inbox-api.test.ts` 7/7.
  Until it is deployed, pagel.uz answers 404 → the phone's Inbox stays empty (skeleton → "Inbox zero") and nothing is lost.
- **Room v4** (AutoMigration 3→4, `MigrationTest.threeToFourKeepsTasksAndAddsAnEmptyInbox`): `inbox_items` (the deck,
  `position` = the phone's order, `sortedAs` = chosen here, waiting to send) + `inbox_state` (the day's counts).
  `data/db/InboxDao.kt`: `choose` / `undo` / `sent` / `refused` / `refresh` (the top card never moves; waiting choices stay).
- **Outbox**: each choice is an `op = "sort"` row in the shared outbox (`OutboxSender` → `InboxSender`), sent in the order
  made; a Drop waits out its 5 s Undo and holds the sorts after it; a refusal brings the card back on top and the shell's
  red toast says why. `Session` nudges the outbox when the live link comes back (`OutboxWorker.nudge`, REPLACE only a
  pass that is backing off), so "back online" sends in seconds, not after a grown backoff.
- **Screen** (`InboxScreen.kt`, `InboxSheets.kt`): header "N left · Inbox · 7/20 sorted today · 2/5 learned", streak
  flame, ? guide sheet, the **20-a-day ring** (closes, fills mint, ✓ + ding + confetti + "20 sorted today. Goal done ✓").
  The card: site pill (Instagram/X/LinkedIn/host), author, date, project tile; the AI's claim, why (mint), the picture
  220 dp (cached on disk, skeleton while loading), 3 lines of the post, "Read all" sheet, "Open the post ↗". Under it the
  **AI's pick** as the big coloured button ("AI pick: …"; Learn → Note when today's keeps are full) and the other five as
  chips (Learn off when full, Later off once put off). **Swipe** right = AI's pick (Learn if unread), left = Drop, up =
  Later, with a tilt, a stamp, a haptic tick at the line, fling detection, spring back. Action/Note/Learn/Do now open a
  sheet: prefilled line (web's), project chip, This week/Someday, word count ("3 more words"), coloured button.
  Each choice: its own exit motion (web's ib-act/note/keep/now/later/drop), its own sound (web's blip notes,
  `ChoiceSound`), its own haptic. Toasts as the web ("Dropped · Undo", "Added to Humopedia", keep cheers at 5/10/25/50/100).
- **Not in this phase (web has it)**: filters by site/field, Shuffle button (the phone shuffles once per app run), "clear N
  older than 14 days", paste a link/thought/picture (Phase 7 capture), recall questions + "% remembered", research
  progress for kept saves, the AI read of unread saves (the phone never starts a triage; the web does), a dropped picture
  on a Note.
- **CI**: (filled below)

## START HERE v8 · PHASE 1 ROUND 3 = THE CHECKER'S 7.5 → 9 LIST (2026-10-28, builder agent)
Fix list: `docs/qa/phase1-round2-checker.md` (app-v0.5.1 scored 7.5/10). Round 3 fixed all 10 + the "still open" list:
- **Wheel = iOS** (`TimeWheel.kt`): 7 rows of 34 dp on a cylinder (20° a row, `cameraDistance = 8·density`, alpha cos²),
  drums 104 dp, no ":", band = ink 6 % fill, radius 10, no border; numbers stop growing at 1.2× font.
- **Date sheet fits** (`Pickers.kt` DateContent): quick days = one chip row (fade at the end); the month shows only the
  weeks it needs (5 for Oct 2026); day cells 40 dp; past days ink .50 (~3.4:1, was 1.9:1).
- **Wheel flow**: "Add time" opens the wheel and saves nothing; the row shows "Thu 29" (back to the calendar) + the time;
  the first turn saves, or a tap on the shown "09:00" (description "Set 09:00"); ✕ = No time (icon); the row is a
  FlowRow so at 1.5× the icons wrap instead of cutting the time. Test `timeWheelSavesNothingUntilAsked`.
- **Type**: `labelSmall` = Schibsted Grotesk Medium 12.5 sp; Departure Mono only through `Glass.NumberText` (step count
  "2/3", "+2", project counts, phase counts, pairing code). Rule written in `docs/RULES.md`.
- **Panel**: Priority = a nav row "⚑ P1 ›" opening `PrioritySheet` (same PickRow family as Date/Repeat, one ✓); Today
  hint "By date" / "By repeat" / "From 30 Oct"; "Note" (no "what the AI reads"); won't-do has no subtitle and the refusal
  is one line with an icon (`NotDoing.kt` words shortened; rules unchanged, the web still has the long sentences).
- **Repeat**: weekday toggles sit in a pane like the monthly grid. **Today** meta: "⟳ Weekly" via `Repeat.label`.
- **Tabs**: words auto-size to the slot (`AppShell.kt` TabLabel, BasicText autoSize 8 dp min); landscape bar 48 dp, icon
  beside word, ≤ 440 dp, centred, + beside it (`LiquidBottomTabs(barHeight)`). Dark mode = light only until Phase 9 (RULES).
- **Panes**: a second soft mint light bottom-right (`Surfaces.kt`). **Add sheet**: the typed line fades at its end when it
  overflows (not while typing); chip-row fade 48 dp. The "30 Oct without 3pm" shot is a pick beating the typed line, as
  the web does (add-task.tsx `picked ?? q.due`); add-ready shows the typed time.
- **Walkthrough**: `tap "Priority: P[1-4]"` → sheet → `tap "P1"` (step `panel-priority-sheet`).
- **CI**: 40584580869 (84d7912) build ✅ smoke ✅ (32 steps; walk 22 date sheet fits with the time row, 28 landscape
  compact bar, 30 "Notebook" whole at 1.5×). Its picker-time-wheel-360-font-1.5 showed "15:3" (time chip squeezed by ✕ and
  keyboard) → next push: the time row is a FlowRow. Frames on CI's software GPU: 89 % janky, p50 150 ms (not a phone number).
- **NEXT**: fresh checker on the newest green run; Boss installs the CI build (no tag: lead's call); server `notes` +
  phase `goal` in `/api/v1/sync`; then Phase 2.

## START HERE v7 · PHASE 1 ROUND 2 = THE CHECKER'S TOP 10 (2026-10-28, builder agent)
A fresh checker scored the app 6.5/10 (evidence in the session scratchpad `checker1/`). Round 2 fixed all ten:
- **Labels never wrap or cut**: "Priority" keeps one line (its flags drop under it on a narrow phone / 1.5× font,
  `TaskPanel.kt` Props FlowRow); the project capsule owns the header's free width, chevron dropped at large font
  (`TaskPanel.kt` Header). Tests read the text layout: `oneWholeLine("Priority")`, `oneWholeLine("PAGEL")` at 360 dp + 1.5×.
- **Toast above the sheet**: a short sheet shows the toast in a popup above its top edge (`GlassSheet.kt` AboveTheSheet);
  a full sheet keeps it at its foot.
- **One chip family**: white glass; set = ink words + coloured icon + 1 dp #1C7E69 rim (`GlassChip.kt`); project chip shows
  its gradient tile. Send = white glass + muted arrow until ready, solid mint when ready (`AddTaskSheet.kt`).
- **Clear glass panes**: white .32 + the web's 315° conic rim at 1.25 dp (`Surfaces.kt` PaneRim).
- **48 dp touch boxes** everywhere (`TouchMin`, `GlassIconButton` min 48, chips 48 high, priority flags 48×48, weekday
  toggles 48 high). Tab label #0B5C45. P2 flag #C77400 (the web's task-panel.tsx FLAG[1] is still #eb8909).
- **Fewer words**: "To tasks" list-plus chip, won't-do hint one line (hidden under an error), repeat sentence gone, repeat
  presets with icons. Scrolling rows fade at the end (`fadeEnd`); won't-do kinds wrap (FlowRow).
- **Parity**: project page = NOW pane (+ Add task into the project, "N done" fold), "Later · N phases" and
  "Done · N phases" folded, "N now · N open in all", empty state (`ProjectScreen.kt`, `PagelDao.projectTasks`);
  "Added · Today, in line" when Today holds 3; a refused add shows "Not saved" and puts the words back.
- **Not done (server has no data/op)**: task notes and phase goal (`/api/v1/sync` sends neither), "Make now" (no v1 op).
- **Walkthrough**: relaunches PAGEL after step 26 (Back on Today leaves the app) and asserts PAGEL is in front before
  steps 27-31 (`front()` in `walkthrough.sh`).
- **Boss on 0.5.0 (00:36)**: time = Apple wheel (`TimeWheel.kt`: two snapping looped drums, hours 00-23, minutes by 5,
  fixed white-glass band with a #1C7E69 rim, fade/shrink/tilt by distance, a tick per notch; it takes the calendar's
  place, so the date sheet fits 360×800; the slot chips and typed box are gone, typed time stays behind a keyboard
  icon). Monthly repeat on chosen days: 1-31 + Last grid, grammar `month 1,15,last` = **dashboard d8be98c on
  feat/gtd-core, NOT DEPLOYED** (until it is, pagel.uz refuses such a repeat and the phone rolls it back with a red
  toast). Every select: one white-glass material, ✓ or filled mark, 48 dp, haptic.
- **CI**: 40565428308 build ❌ (3 one-line checks used hasVisualOverflow) → 40567219334 ❌ (paragraph width) →
  40568569881 build ✅ smoke ✅ (31 steps, 27-31 on PAGEL) → 40572766848 build ✅ smoke ✅ (wheel + monthly) → last
  push: wheel rows drawn from the scroll position (at 1.0× the rows off the band were invisible) + tab words stop
  growing at 1.2× ("Notebo" at 1.5×).
- **NEXT**: deploy dashboard d8be98c (Boss's yes; PAGEL-only procedure); Boss installs the CI build (no tag: his call);
  server: `notes` + phase `goal`/`state` in `/api/v1/sync`, then the panel's notes and the goal line; then Phase 2.

## START HERE v6 · PHASE 1 OF 10 = THE FULL TASK SYSTEM (2026-10-27 ~23:00 Tashkent, builder agent)
Plan: `C:\Users\Murodjon\.claude\plans\tranquil-wondering-melody.md` (10 phases) + detail
`tranquil-wondering-melody-agent-aceb5252743b532f2.md`. Phase 1 built end to end:
- **Server (deployed to pagel.uz)**: dashboard `feat/gtd-core` 0fdaefc: `PATCH /api/v1/tasks/<id>` also takes `title` (column
  + the web's card), `every`, `remove`, `restore`, `not_doing {kind, reason}` (excuse → 400 before any change); the reply
  carries `applied` (fields understood). Sync tasks carry `phase_sort`, `phase_now` and the card title. vitest
  `task-api.test.ts` 7 + `sync.test.ts` +1. Backup `~/pagel-data/backups/pagel-pre-app-api-v2-20261027-2128.db`; built in a
  worktree with the service's own env; live `.next` kept as `.next.old-20261027-2128` for rollback.
- **Phone data**: Room v3 (AutoMigration 2→3, `MigrationTest` builds a real v2 file from `2.json`), `data/tasks/TaskRepository.kt`
  (each edit: Room first, one outbox PATCH `{set, was}`; new fields in their own op; older server = waits, cloud-off mark;
  refused = exact rollback + red toast; delete holds 5 s for Undo; won't do checks the excuse on the phone),
  `OutboxSender` PATCH path, `takeBackWaiting` only takes back ticks.
- **Glass kit** `ui/glass/`: GlassSheet, GlassChip, GlassIconButton, InlineField, GlassMenu, ToastCenter/ToastHost,
  Pickers (date: quick days + Monday-first month + half-hour slots + typed time; priority flags; repeat presets + day
  toggles; project search), DatePick + Repeat (the web's rules, tested).
- **Screens**: task panel from any task row (Today, project pages): project capsule, copy prompt, won't do, delete+Undo,
  ring, title, finish line, steps + bar, Date/Repeat/Priority/Today rows, full note. Add sheet rebuilt (52 dp capsule +
  44 dp mint send; chips open the pickers; toast "Added · Today" / "Added · Upcoming, Fri 30 Oct"). More → Projects →
  a project: phases, NOW first; + adds into it. Toasts live in the shell; title capsule flips after 24 dp.
- **Checks**: Roborazzi `panel*.png`, `picker-*.png`, `add-*.png`, `project.png`; walkthrough opens a task, ticks a step,
  sets P1, picks today+2 in the calendar and asserts the 3 PATCH bodies in the fake log.
- **CI**: run 40546542662 build ✅ (282 tests) / smoke ❌ (panel sheet stuck at its placeholder height; a leftover fake
  server held the job 35 min) → fixed in f1be6c2 → run 40554876982 build ✅ + smoke ✅ (31 steps; the 3 PATCH bodies
  asserted). Then the "Moved to …" toast no longer shows for a starred task (it stays on Today).
- **Not in Phase 1 (web has it)**: move a task to another project, step reorder, read-only plan notes, focus mode.
- **NEXT**: checker agent on the CI screenshots; Boss's notes; then Phase 2 (Inbox sorting).

## START HERE v5 · WEB PARITY 1:1 (saved 2026-10-27 ~20:25 Tashkent, context 55 %)
Boss's last words (on 0.3.15, Add sheet screenshots): "it opened good, but that dialog: liquid, clean · select
priority with icons, select date with calendar view, and repeat, many more not working · when I click Add task, after
it's added it is not syncing [to the] laptop · audit, I want the whole thing working, we did only the simple part ·
make the widget white mint clear liquid glass kind, with aurora · build 1:1 fully [like the] web, faster, better".
Facts: the add DID reach pagel.uz (t_ydf7aj6tbw "Game Development'dan rocket o'yini tuzish", university, 15:20:02 UTC,
source pagel-android) → check the laptop page's live refresh (SSE /api/events/stream) shows phone adds without reload.
Running at save: CI for f437570 (panes without blur, after the c4eae19 launch ANR on CI's software GPU). Green → tag.
**Next, in order (one push per CI cycle, tag after each green run):**
1. Add sheet = the web's add-task.tsx 1:1 + glass: transparent ModalBottomSheet over AuroraBackground { Pane(28) },
   tappable chips that open pickers: priority menu with flag icons P1-P4 (web FLAG colours), date picker with a calendar
   month view + half-hour time slots (web pickers.tsx DatePicker), repeat picker (web repeat-picker.tsx: daily,
   weekdays, weekly, monthly, custom days), project picker with search; audit #4 (52 dp capsule field + 44 dp mint
   send). After Enter: a glass toast "Added · Today" (or "Upcoming, Fri 30 Oct").
2. Laptop live refresh check (web side) for phone adds; fix if the page needs a reload.
3. Widget "white mint clear glass with aurora": Glance cannot blur, so a drawable background with the aurora
   radial gradients (layer-list) + a white 70 % card with a white-to-mint rim; ticks mint.
4. Audit fixes 5-10 below (pair spinner, press on touch-down, top fade in the mask, toast in the shell, capsule
   hysteresis, animations).
5. Web parity list (each one a release): task panel (edit title, date+time, priority, steps, finish line, notes, won't
   do, delete+undo) on row tap · Upcoming · Habits + reminders · Inbox sorting · voice add + Share → PAGEL.

## START HERE v4 · DESIGN AUDIT FIXES (saved 2026-10-27 ~20:00 Tashkent)
Boss's words: "everything liquid glass with our colour theory mint" · "one icon beats many words" · "no Syncing, no
spinners, skeleton" · "tasks must be mine, fast" · web Habits cards = 8/10 reference · 0.3.14 was 1/10 (flat bar,
Undo broken, black toast). Standing rule: **tag `app-v*` after each green CI run** (never main). Replies short.
Shipped today: app-v0.4.0 (Today + widget, v0.3.14), app-v0.4.1 = 532b2e3 (Add task fast, clear glass bar + rim,
Undo restores the star, light toast, More icon tiles, widget white/ink, update check on open, silent sync + skeleton,
scroll-aware glass title). Pushed after it: 15775b3 glass panes (needs CI → tag).
Skills installed: emilkowalski/skills (15, MIT) — use apple-design, emil-design-eng, review-animations,
find-animation-opportunities for every UI change. dickwu/apple-design-skill skipped (no licence).
**Audit (Opus, read-only) — do in this order, one push per CI cycle:**
1. Projects in one glass Pane, 1 dp Ink@0.07 dividers from 70 dp, rows pressScale + open web, count in a mint capsule (`ProjectsScreen.kt:65`).
2. Today = Habits look: meta in Glass.Numbers 12.5 sp, Today/late in Primary + calendar 13 dp, dividers from 54 dp, date line as mono eyebrow (`TodayScreen.kt:190,245`).
3. Fewer words: "+2" capsule + layers icon instead of "2 more after these…"; header "5 today"; offline = cloud-off icon; notes ≤ 6 words (Today, AddTask, Projects, Pair, More).
4. Add sheet glass: transparent sheet, scrim Ink@0.18, AuroraBackground { Pane(28 dp) }, 52 dp capsule field, 44 dp mint arrow-up send inside it, icons not ⚑↻✓ characters.
5. Pair spinner → label at 0.55 alpha, then AnimatedContent to a check (no spinner left; drop unused imports).
6. Press on touch-down in lists: pressScale from pointerInput awaitFirstDown (clickable stays for the tap).
7. Delete TopFade; fade content top inside the existing offscreen mask (0 at status bar → 1 at +20 dp); header top 20 dp.
8. SnackbarHost moved to the shell so the toast bends the real list; enter slideIn from bottom 240 ms, exit same path 160 ms; swipe down to dismiss.
9. Title capsule: flip only after 24 dp in the new direction; fade 160 ms + scaleIn 0.94 from top; exit fade 120 ms.
10. Panes: drop blur (keep vibrancy + lens); keep blur on bar/capsules; measure gfxinfo janky % before/after on the phone.
Animations to add: animateItem on skeleton→list; "All done" pane spring(0.8, 380) + check draw; open count AnimatedContent; hold-to-disconnect 1 s fill. Calm: new-row enter → tween 260 EaseOutStrong, 10 dp, no overshoot.
Then: voice add + Share → PAGEL (Boss's own tasks fast), Habits + reminders, Inbox sorting.

## ✅ 2026-10-27 13:27 UTC · Today screen native, CI green (run 40509697013, c9df153)
- `ui/today/`: `TodayDeal.kt` (3 at a time; a ticked row keeps its slot until its fold ends; headline + due labels =
  the web's), `TodayViewModel.kt` (Room only, `finishing` set, tick/undo/acknowledge), `TodayScreen.kt` (Todoist flag
  rings, ring pop + tick draw, fold after 0.85 s, next task slides in, Undo snackbar 4 s, refused → shown once),
  `Celebrate.kt` (ding C6→G6 as PCM, 34-bit confetti, reduced-motion respected). Projects moved under More.
- Proof: build ✅ 235 tests (`TodayDealTest` 5) + Roborazzi `today*.png` (4); smoke ✅ walkthrough 19 steps: tick
  "Ship the native app" → 4th task dealt (`walk/11-after-fold.png`, "4 tasks today") → `PATCH /api/v1/tasks/t_ci1
  {"done":true}` reached the fake pagel.uz in ~1 s.
- First run 40508378443 failed 1 test: my expectation was wrong (the web keeps the full stop: "Call the bank."),
  checked against `lib/headline.ts` in node before changing the test.
- Not checked: ding, confetti, haptics and speed on the A05s (Boss's phone; needs a release = his "tag it").
- Brain: `D:\Brain\rules\native-app-playbook.md` (skills, rules, method, look, checks; Humopedia reuses).
- NEXT: Glance widget "Today's 3" (skills glance-canonical-layouts + glance-appwidget-updates-and-memory), then Add task fast.

## START HERE v3 · TODAY UI (saved 2026-10-27 ~17:50 Tashkent, laptop session, context 57 %)
Boss's last words: "I installed it fully, it's the best ever, I love that liquid glass, real iPhone vibe… native feels
better" · "keep that style, save a rule for the navbar… Humopedia needs it" · "liquid glass not working on More, it's
simple, audit and fix it".
State:
- Boss runs **0.3.13** (release run 40499990183 green, served by the server since 12:10 UTC). He LOVES the glass bar →
  brain card `D:Brain
ulesliquid-glass-navbar.md` (all numbers, code paths, Humopedia reuse).
- **Critic rig done** (456a52e): `.github/scripts/walkthrough.sh` runs in the smoke job after pairing: 15 steps (each tab,
  scroll, re-tap, back, rotate, font 1.5×, dark) → artifact `smoke-evidence/walk/` (png + ui xml per step, walk.mp4,
  gfxinfo, Davey lines). Run 40503012651 ✅ build + smoke.
- **The freeze, answered:** `stacks-after-pair.txt` shows the main thread parked in `ThreadedRenderer.syncAndDrawFrame`
  (waiting on the GPU), not in our code. CI's emulator draws in software (swiftshader): 18/18 frames janky, p50 400 ms,
  p99 2,400 ms. That measures CI, not the A05s. NEXT measure: app-side FrameMetrics on Boss's phone → POST to the
  server (or Boss's eyes). Do NOT optimise glass against swiftshader numbers.
- **Today data layer done** (94a1697, CI green, 7 tests): Room v2 (`today_order`, `outbox`, AutoMigration 1→2),
  `data/today/TodayRepository.kt` (tick = Room first, outbox, undo before send), `OutboxSender` + `OutboxWorker`
  (WorkManager, network, backoff 10 s; refused = roll back + `refused` flow for a toast), `SyncEngine.syncOnce` also
  stores `/api/v1/today`'s order, `applySync` never overwrites a pending tick.
- **Glass fix** (369e4c1, pushed, CI running): the "scroll edge" was a 92 % page-coloured strip painted UNDER the glass
  → the bar bent near-white on every screen (More/Inbox/Habits looked flat). Now the content fades as an alpha mask
  (DstIn, to 35 %) and two stronger bottom aurora clouds (mint 34 %, teal #2BB5A8 24 %) sit under the bar. Project
  tiles: Lucide icons generated from the web's lucide-react 1.49 (not letters); short names (ABABIL, not "ABABIL —
  first-aid…").
Next, in order:
1. Read run for 369e4c1: `walk/06-tab-More.png` must show colour bending in the bar; `walk/01-home.png` icons.
2. **Today screen** (`ui/today/`): `TodayViewModel` (UiState from `TodayRepository.today` + `refused`), shows 3 at a time
   (web: shown_at_once), rows = ring tick (TaskRow: priority ring, ding C6→G6 + confetti + fold ~1 s, haptic CONFIRM),
   Undo snackbar 4 s, refused → toast + acknowledge; route `TodayRoute` → Today (Projects moves under More). Fake
   server: add `/api/v1/today` + PATCH to `fake-pagel.py`; walkthrough: tick a task, screenshot, assert the PATCH.
3. Glance widget "Today's 3" (skills glance-*), then Add task fast.
Rule learned: **one push per CI cycle** on this branch (`cancel-in-progress: true` threw away run 40502499659).

## START HERE v2 · PHASE 2 (saved 2026-10-27 ~12:05 UTC)
Boss's last words: "Phase 2, use it yourself on server if possible like real critiq and improve ui ux."
State: Phases 0-1 green on feat/native-app (runs 40496280942, 40497908540; 56143b5). Boss has NOT said "tag it" yet,
so no release; never tag app-v* or merge main without his word.
The server cannot run an emulator (no /dev/kvm, 2 ARM cores, lexion lives there) → "use it myself" = the CI emulator.
Order:
1. **Critic rig first** (before features): `.github/scripts/walkthrough.sh` in the smoke job: pair against
   fake-pagel.py, then like a person: open each tab, scroll Today, tick a task, back, re-tap a tab, rotate, font 1.5×
   (`settings put system font_scale 1.5`), dark status check; `adb shell screenrecord --time-limit 60` + a screencap per
   step → artifact `walkthrough`. Read every frame against the web at 390 px (`D:\.tmp\shots` / pagel.uz) and the
   Apple-design audit above; write findings as a table in this file; fix; loop (max 3 rounds per feature).
2. **The freeze**: one run froze the main thread 12.9 s right after pairing. Catch it with
   `adb shell kill -3 <pid>` + `adb pull /data/anr/` (works without root) or a Perfetto trace; fix the cause.
3. **Phase 2 = Today + tick** (NATIVE-PLAN table row 2): server: the web's Today order (`lib/views.ts` todayView /
   dealOrder: 3 at a time, overdue, project next step) exposed via `/api/v1/today` (exists) → Room `today` order or
   computed on the phone; tick = Room first (< 50 ms), outbox + WorkManager push with backoff, 4xx rolls back with a
   toast; ding + confetti + fold like `src/app/today/celebrate.ts`; haptic Confirm; Glance widget "Today's 3" after.
   Deploys of server changes: worktree build → Boss's yes → live build + restart in one step (CLAUDE.md §4 rule, PR #5).

### ✅ 2026-10-27 11:40 UTC: `build` workflow green on feat/native-app (run 40496280942, 3aaef46)
build ✅ (219/219 tests + 5 screenshots) · smoke ✅: alive 30 s, forced crash written, relaunch, **paired with a 6-digit
code against the fake pagel.uz → "Projects · 148 open tasks · Live" on the glass shell** (`smoke-evidence/paired-projects.png`),
then the saved crash reached `POST /api/v1/app/crash` and left the phone. The 12.9 s freeze of run 40491073171 did not
fail this run (the smoke now waits on real signals); its cause is still unknown: `debuggerd -j` needs root on the
emulator, so next time use `adb shell am dumpheap`-free `kill -3 <pid>` + `/data/anr/traces.txt` or a Perfetto trace.
Next after that: unselected tab labels were near-invisible on the glass → ink 72 % (this commit).

### START HERE (next session, 2026-10-27 ~11:00 UTC)
1. Read the newest run on `feat/native-app-ci2` (8270c42): artifact `smoke-evidence` → `stacks-after-pair.txt` holds
   the main thread's stack during the freeze. Fix that cause, push to `feat/native-app-ci2`, repeat until build + smoke
   are green, then `git push origin feat/native-app-ci2:feat/native-app` (fast-forward) and confirm the run there.
2. **The freeze (real bug, found by the smoke):** right after pairing, the first frame of the tab shell blocked the main
   thread ~12.9 s on the emulator (`Davey! duration=12898ms`, `Skipped 741 frames`). The pairing screen itself drew at
   ~2 fps (`app_time_stats avg=500ms`; software GPU). Suspects, unproven: RuntimeShader (AGSL) compile for the lens /
   interactive highlight on Main, 7 variable-font instances loaded with FontLoadingStrategy.Blocking, the aurora's four
   full-screen radial gradients redrawn every frame (move to drawWithCache). Let the stack decide.
3. Then the rest of the design audit below; then Phase 1's last check on Boss's phone (install + real code).

### Apple-design audit (2026-10-27, Boss: "do it like Apple best cases, apply them all, on Jetpack Compose")
Vetted: `emilkowalski/skills` ★50.4k MIT (use: apple-design, emil-design-eng, animate-expo, mobile-native);
ui-skills.com page = the same apple-design file; `VoltAgent/awesome-design-md` ★124k MIT (reference only: its Apple file
describes apple.com marketing pages); `dickwu/apple-design-skill` ★2.1k **no licence** (HIG numbers read, nothing copied).
The 5th link Boss sent was cut off at "https://git".
Applied (7218431): press scale 0.97 / 120 ms / strong ease-out on pressables (`ui/glass/Press.kt`); haptics SegmentTick on
tab change and Reject on a wrong code; no slide between tabs; scroll-edge fade under the glass bar; explicit line heights;
`heightIn` on the pill; error line with a mark + fade; empty project state. Kit wins on: mint, ExtraBold headings, list
panes white 78 % with shadow, light theme only.
Not yet: momentum-projected tab snap + velocity hand-off (catalog drag), reduced-motion check, skeleton rows, OTP field
polish, opaque fallback for the glass bar.

### CI runs

| Run | Commit | build | smoke |
|---|---|---|---|
| 40483716472 | Phase 0 | ✅ | ⏱ cancelled at 30 min, but its log proves: alive 30 s, no crash/ANR, forced crash file written. Hung after on `adb shell run-as … sh -c 'cat glob'` (adb drops the quotes → bare `cat`) |
| 40485580870 | toolchain | ✅ | cancelled (same hang) |
| 40487383756 | Phase 1 | ❌ Hilt: two `OkHttpClient` bindings → `@PagelHttp` | skipped |
| 40489358989 | Phase 1 + Hilt fix | ❌ 12 Robolectric tests: PagelApp started WorkManager → `robolectric.properties` plain Application | skipped |
| 40490348826 | design pass | ❌ 1 test matched the old error text exactly | skipped |
| 40491073171 | test fix | ✅ **219/219**, screenshots recorded (glass-bar, pair-empty, pair-error, projects, projects-font-1.5) | ❌ Phase 0 part ✅ (alive 30 s, crash file, relaunch); pairing reached the server, then the 12.9 s freeze |

### Mistake and rule (2026-10-27)
I ran `next build` in the **live** dashboard folder before the restart was allowed. The running server then loaded
chunks that no longer existed: `/notebook` and every project page answered 500 for ~10 minutes. Restoring the old
code and rebuilding did not help (the original build used the service's env file). Boss chose deploy + restart; fixed.
Rule: build in a separate worktree; the live folder changes only in the same step as the restart.

### Open
- Boss: install a build and pair with a real code (pagel.uz → More → Phones); open it 10 times (Phase 0 check).
- Room schema v1 committed (`app/schemas/…/1.json`, from the CI artifact); migration tests start at version 2.
- Brain (laptop): session note for this server session; the server's `~/brain` is a read-only mirror.
- Disk on the server: 88 % used (163 → 168 GB during the session; my 0.5 GB dashboard worktree is removed; the rest
  grew elsewhere on the shared box). CLAUDE.md calls ≥ 80 % critical: Boss should look.
- PR tolipovmurodjon/pagel#5 (draft): the deploy rule for CLAUDE.md §4.
