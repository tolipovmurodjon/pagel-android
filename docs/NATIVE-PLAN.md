# PAGEL Android, rebuilt fully native: same as the web or better, live-synced, real liquid glass

## Context
Boss, 2026-10-27: "mobile app not opening, working like trash… go deep, plan, I want a better app, more native
Android… install skills… do the mobile app fully, one whole feature at a time" · "same as or better than the web,
audit and fix it fully" · AskUserQuestion answers: it **closes at once, shows a white/black screen, asks for the
password / says offline, and when it opens it is slow and web-like (the navbar scrolls away with the page)**;
**everything native**, "based on the web and Android working the same, synced fully in real time"; features: Today +
tick, Add task fast, Habits + reminders, Inbox sorting, all in the **same design as the web: real liquid glass, not
the plain blue**. Then: "get the top 5+ skills, load them, learn them, use them fully… before building make a deep
plan and write down all rules and skills".

Today the app (D:\PAGEL\android, `feat/senses-and-self-update`, 0.2.12) is a WebView of pagel.uz
(`ui/tasks/TasksScreen.kt`) on top of a well-tested phone-gateway stack (net/, 190 unit tests). Root causes found
(file:line from the code map):
1. **Self-update kills the app mid-use**: CI publishes a release on EVERY push to the feat branch
   (`.github/workflows/release.yml:6-9`) and `Updater` installs silently (`USER_ACTION_NOT_REQUIRED`,
   `update/Updater.kt:85-88`) every 30 min (`UpdateWorker.kt:34`) → "closes at once".
2. **Main-thread keystore at launch**: `SavedSignIn` builds EncryptedSharedPreferences in the first composition
   (`TasksScreen.kt:84`, `SavedSignIn.kt:12-18`), unguarded; it can throw (crash) and is slow on the A05s (freeze).
3. **Main-thread WebView cold start** (`TasksScreen.kt:97-159`) → white screen; `runBlocking` DataStore read on
   main (`net/GatewayModule.kt:162`).
4. Web-in-a-box: page scroll moves the bar, basic-auth password prompts, no offline data, no native gestures.
Outcome: a native Compose app where every web feature works as well or better, offline-first, updated live from
pagel.uz in ≤ 3 s, in the web's mint liquid-glass look, shipped one complete feature at a time.

## Decisions (Boss's, from his answers) and mine (marked ⚙)
- Everything native (Compose); the WebView is deleted at the end, not kept as a fallback.
- Same data, both ways, live: the server database (pagel.uz) is the truth; the phone keeps a Room copy; every change
  on either side shows on the other within 3 s (SSE event stream already exists: `/api/events/stream`).
- Same look: the web's mint liquid glass (`dashboard/app/src/app/globals.css` "Mint liquid glass" block: primary
  #1c7e69, soft #e3f4ec, ink #0f1630, aurora #66dda8/#a9f0cf, Inter Tight headings, Departure Mono numbers; tile
  gradients `src/app/tile-colors.ts`), light theme. Real refraction glass on the bars and sheets.
- ⚙ Sign-in = **pairing**, no password on the phone: pagel.uz (passkey session) shows "Connect phone" with a one-time
  6-digit code (5 min); the app sends it once; the server returns a per-device token (stored hashed, revocable from
  the web). The phone's token is kept with Android Keystore (DataStore + Tink AEAD), read off the main thread.
- ⚙ Releases only from `main` or a tag `app-v*`; the app asks before installing ("Update ready · Install"), never
  installs while open.
- ⚙ Builds and tests run in GitHub Actions (laptop: C: ~1 GB free, Gradle cache corrupt; no local emulator).
- ⚙ The phone-gateway stack (net/, service/, senses, OpenClaw node) is kept as is, under More → Phone.

## Skills and rules (read 2026-10-27 before any code; full rules go to `D:\PAGEL\android\docs\RULES.md` in step 1)
**Top 8 skills** (vetted: Google's `android/skills`, ★9,865, Apache-2.0, pushed 2026-10-26):
| # | Skill | Source | Used for |
|---|---|---|---|
| 1 | navigation-3 | official | shell, one back stack per tab, sheets as scenes, deep links (widget, notifications, share) |
| 2 | jetpack-compose-performance | official | Today/Inbox lists, swipe cards, glass cost, baseline profile |
| 3 | edge-to-edge | official | glass bar under the system bar, keyboard with the Add sheet |
| 4 | glance-canonical-layouts + glance-appwidget-updates-and-memory | official | Today widget (check-list), refresh on sync |
| 5 | testing-setup (+ archived android-testing) | official + local | fakes, Robolectric Compose tests, screenshot tests, Room tests |
| 6 | android-intent-security + android-permissions-security | official | share target, notification actions, exported flags, permission asks |
| 7 | anr-audit-and-fix | official | review lens for every main-thread call (SSE, Room, Keystore) |
| 8 | android-dev (active) + android-data-layer + claude-android-ninja refs (data-sync, notifications) | local, restored from `skills-archive` | MVVM UiState + effects, repository error boundary, offline-first sync, channels |
Skipped: android-retrofit (we use OkHttp + an auth interceptor), android-ux (M3 audit, not our glass), XML migration, r8-analyzer (at release).
Install: Boss types `/plugin marketplace add android/skills` then `/plugin install android-skills@android-skills`
(official README); I move the 3 archived skills back to `~/.claude/skills/`. Each feature session loads 1, 2, 3, 5.

**Rules** (each from a skill; the build follows them, review checks them):
- Data/sync: UI reads Room only; writes go DB-first then the outbox; every synced row has syncStatus, lastModified,
  serverVersion; one written conflict rule per entity, never drop a local edit silently; backoff with jitter;
  network errors become a sealed `DataError` at the repository; no OkHttp in a ViewModel; one `UiState` StateFlow per
  screen, one-off effects via `Channel(BUFFERED)`; inject dispatchers; `collectAsStateWithLifecycle`; Room schema
  export + migration tests.
- Navigation 3: type-safe `NavKey`s; `rememberNavBackStack`; one back stack per tab, exit through Today; re-tap a tab
  scrolls to top; Add sheet and task panel are scene destinations; `sceneStrategies` list; no hilt-navigation-compose
  (it pulls Nav 2); deep links rebuild a back stack, `onNewIntent` + `setIntent` with the same parser.
- Performance: profile release builds only, R8 on, baseline profile for cold start + Today scroll; every lazy item
  has a stable `key` + `contentType`; selection as a Set; fast animations read in layout/draw (`offset {}`,
  `graphicsLayer {}`); `drawWithCache`; no `BoxWithConstraints`/`SubcomposeLayout` in list items; Coil `AsyncImage`;
  no `Handler.postDelayed`.
- Edge-to-edge: `enableEdgeToEdge()` before `setContent`, `adjustResize`; insets applied once (`Scaffold`
  innerPadding → `contentPadding`); keyboard via `fitInside(WindowInsetsRulers.Ime)`; custom bar sets
  `isNavigationBarContrastEnforced = false`; status-bar scrim over glass.
- Main thread: no `runBlocking`, Keystore, DAO, JSON or bitmap work on Main; the SSE loop on IO; receivers finish
  < 10 s (`goAsync`).
- Testing: fakes before mocks; Compose tests on Robolectric; semantics before testTags; Room in-memory tests;
  screenshot tests at several sizes + 1.5× font; failing test first for every bug; pass `mainRule.dispatcher` to
  `runTest`.
- Widget: `SizeMode.Exact`, check-list layout, `update()` after state writes, revision counter, `ActionCallback`
  zero-arg constructor + R8 keep, no bitmaps in `provideContent`, solid mint (Glance cannot draw glass).
- Security: only launcher, share-target and widget receiver exported; every PendingIntent `FLAG_IMMUTABLE` +
  explicit; share intents are untrusted (validate, strip grant flags); ask for notifications at first use, never at
  launch, never loop; check permissions each time; exact-alarm rules looked up in Context7 before reminders.
- Toolchain (forced by backdrop 2.0.1 built on Kotlin 2.4.10 / AGP 9.3.2, and Navigation 3 1.2 needing AGP ≥ 9.2 +
  compileSdk 37): Kotlin 2.4.x, AGP 9.3.x, compileSdk 37, current Compose BOM, Hilt + KSP matching; one migration PR
  with the 190 tests green before any feature code.
- Ours (brain + CLAUDE.md): respond first, save after (< 50 ms); a UI change is done only after a screenshot of its
  open state; numbers before/after for speed; Boss's language kept; no secret printed; maker never checks own work
  (CI + screenshot diff + a fresh reviewer per phase).

## Phases (vertical slices: each = server API → Room → repository → ViewModel → screen → tests → release)

### Phase 0 · Stop the crashes (first, small, ships alone as 0.3.0)
- `release.yml`: publish only on `main` / `app-v*` tags; feat pushes build + test only.
- `Updater`: download in background, then a notification "PAGEL 0.x ready · Install" (user action); never while open.
- Crash capture: `Thread.setDefaultUncaughtExceptionHandler` writes the stack to a file; next launch POSTs it to
  `POST /api/v1/app/crash` (new, bearer) → stored in the DB events → I read real crashes from the phone.
- `SavedSignIn` + WebView creation off the first frame and wrapped (`runCatching`, show a retry screen, never crash);
  `GatewayModule.kt:162` runBlocking → suspend.
- CI emulator smoke job (API 34, `reactivecircus/android-emulator-runner`, vetted first): install, launch, 30 s, no
  crash in logcat, screenshot artifact.
- Done when: emulator job green; Boss opens 0.3.0 ten times, zero closes; crash endpoint receives a forced test crash.

### Phase 1 · Foundation (no visible feature yet; one release at the end)
1. Skills installed (see Skills) and the rules file `D:\PAGEL\android\docs\RULES.md` written from them.
2. Toolchain: Kotlin 2.0.21 → 2.4.x, AGP 8.13 → 9.x (official agp-9-upgrade skill), Compose BOM → current stable,
   KSP to match; add Room, Navigation 3, Retrofit + kotlinx-serialization (OkHttp exists), Coil 3, Glance,
   io.github.kyant0:backdrop 2.0.1 (Apache-2.0, ★4.3k, pushed 2026-08), Roborazzi (JVM screenshot tests).
   The 190 unit tests must stay green.
3. Server (dashboard repo, `feat/gtd-core`): `devices` table + `POST /api/v1/devices/pair` (code → token, rate
   limited, 5 tries) + "Connect phone" on the web + revoke; device tokens accepted by `apiAuthed`
   (`src/lib/api-token.ts`); `GET /api/v1/stream` = the SSE stream with bearer auth (reuse
   `src/app/api/events/stream/route.ts`); `GET /api/v1/sync?since=<event id>` = changed tasks/steps/cards since a
   cursor (so the phone pulls only deltas). Tests in vitest.
4. Data layer: Room (tasks, steps, cards, habits log, projects, resources, inbox items, outbox); `SyncEngine`:
   cold start = full pull, then SSE `change` → delta pull; writes go to Room first (UI updates < 50 ms,
   rules/respond-first-save-after), then the outbox → WorkManager push with backoff; a 4xx rolls back with a toast.
5. **Real liquid glass recipe** (io.github.kyant0:backdrop 2.0.1, minSdk 21; its catalog's `LiquidBottomTabs.kt`
   read 2026-10-27): the screen content gets `Modifier.layerBackdrop(backdrop)` (a live capture of what scrolls
   underneath); each glass piece draws it through `Modifier.drawBackdrop(backdrop, shape = { Capsule() }, effects =
   { vibrancy(); blur(8.dp); lens(24.dp, 24.dp) }, highlight = { Highlight.Default }, onDrawSurface = { mint tint })`.
   `lens` = true refraction (RuntimeShader, Android 13+; the A05s runs 14/15), `blur` = RenderEffect (12+), older =
   tint only. The selected tab is a liquid drop: a damped-spring drag (`DampedDragAnimation`) that swells under the
   finger (press scale 1 → 1.2) with an interactive highlight, exactly the iOS 26 behaviour. Port their catalog
   components (Apache-2.0, credited in the file header) into `ui/glass/`: LiquidBottomTabs, LiquidButton,
   LiquidToggle, LiquidSlider; then GlassSheet/GlassCard on the same recipe. Glass only on chrome (bars, sheets,
   buttons); lists stay ink on near-white for contrast, as on the web.
6. Design system `ui/glass/`: tokens from the web, `GlassBar` (fixed bottom bar, real refraction on API 33+, blur on
   31-32, frosted fill on 30), `GlassSheet`, `GlassCard`, `ProjectTile` (2-stop OKLCH gradients converted to sRGB),
   `TaskRow` (priority ring, tick with ding + confetti + fold, as `src/app/today/celebrate.ts`), chips, date pills,
   Inter Tight / Departure Mono fonts bundled (no GMS download).
7. App shell: Navigation 3 back stack, bottom bar Today · Inbox · Habits · Notebook · More (same as the web phone
   bar), edge-to-edge, predictive back, pairing screen.
- Done when: pair with a code from pagel.uz, see the project list from the API on a glass shell; screenshot tests
  of each glass component pass in CI; tick a task on the web → the phone's Room row changes in ≤ 3 s (instrumented
  test against a MockWebServer stream + one real check on pagel.uz).

### Phase 2+ · Features, one at a time, in Boss's order (each is a release Boss tries before the next)
| # | Feature (web source to match) | Native extras |
|---|---|---|
| 2 | **Today + tick** (`src/app/today/*`, `lib/views.ts` todayView/dealOrder: 3 at a time, overdue, project next step) | offline tick, haptic, widget "Today's 3" (Glance) |
| 3 | **Add task fast** (quick add + natural language "tomorrow 3pm #me p1", `/api/plans` add) | voice (SpeechRecognizer), share-sheet target (Instagram/X link → Inbox or task), Quick Settings tile, app shortcut |
| 4 | **Task panel** (edit title, date + half-hour time picker, priority, steps, finish line, notes, won't do, delete+undo) | bottom sheet with drag, swipe actions on rows |
| 5 | **Habits + reminders** (`/habits`: by time of day, streaks, all-done confetti) | exact-time notifications with a "Done" action that ticks offline (AlarmManager; exact-alarm permission per the skill) |
| 6 | **Inbox sorting** (`/learn`: AI guess, A/2/K/L/D/N, picture, undo, 20-a-day goal) | swipe cards with haptics |
| 7 | **Upcoming, Completed, Projects** (`/upcoming`, `/completed`, `/plans/[project]`, sidebar groups + gradient tiles) | |
| 8 | **Resources + Notebook** (`/resources` shelves, `/notebook`) | |
| 9 | **Rituals** (`/sweep`, `/shutdown`, `/review`) | evening notification for Close the day |
| 10 | **Search, Ask PAGEL, How PAGEL works**; then delete the WebView and `SavedSignIn` | |
Per feature: (a) server endpoint if missing + vitest, (b) Room + repository + unit tests (MockWebServer),
(c) screen + Compose UI test + Roborazzi screenshot at 390 dp next to the web's screenshot at 390 px,
(d) live-sync test (change on web → phone), (e) release, Boss tries it, his notes fixed before the next feature.

## Verification (the outside judges)
- CI on every push: unit tests (190 + new), Roborazzi screenshots (artifact), emulator smoke + instrumented sync test.
- Side-by-side screenshots web 390 px vs app per screen (the web is the reference; the app must match or beat it).
- Live sync: script adds/ticks via `pagel` CLI → emulator asserts the change within 3 s.
- Crash endpoint: zero crashes from Boss's phone for a release before the next feature starts.
- Feel (glass, ding, haptics, speed) = Boss's eyes and ears; never marked done by me.

## Limits and risks
- Server shared with lexion.uz: only PAGEL files/units; DB backup before each migration; nginx untouched except an
  already-approved PAGEL site path.
- ① Toolchain jump breaks the net/ stack (early sign: unit tests red) → upgrade in one PR, fix before anything else.
- ② Real refraction too slow on the A05s (early sign: jank in the bar while scrolling, macrobenchmark frame times) →
  refraction only on the bar and sheets, blur elsewhere, per the Compose performance skill.
- ③ Sync conflicts (same task edited on both) → server wins by event order; the phone's lost edit shows a toast.
- ④ Scope is large (~12-14 sessions): each phase ships alone; no phase starts before the last one is in Boss's hands.

