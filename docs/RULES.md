# PAGEL Android: the rules every change follows

> Written 2026-10-27 from Google's official Android skills (`android/skills`, Apache-2.0) and our local skills
> (`~/.claude/skills/android-dev`, `android-data-layer`, `android-testing`, `claude-android-ninja`), for the native
> rebuild (plan: `C:\Users\Murodjon\.claude\plans\tranquil-wondering-melody.md`). The skill named after each rule
> is where to read more. Review checks every change against this file.

## Before any code in a session
Load: `navigation-3`, `jetpack-compose-performance`, `edge-to-edge`, `testing-setup` (official, via the
`android-skills` plugin) + `android-dev`. Add `glance-*` for the widget, `android-intent-security` /
`android-permissions-security` for share/notifications, `claude-android-ninja/references/android-data-sync-quick.md`
for sync, `.../android-notifications.md` for reminders.

Installed plugins (laptop, 2026-10-27; server: `claude plugin marketplace add android/skills` +
`claude plugin install android-skills@android-skills`, same for ui-ux-pro-max):
- `android-skills@android-skills` 1.0.18: Google's official skills (★9,865, Apache-2.0).
- `ui-ux-pro-max@ui-ux-pro-max-skill` (★141,200, MIT): design checklist with Jetpack Compose, glassmorphism and
  platform rules (`python3 .../scripts/search.py "glassmorphism" --domain style`). A reference only: where it
  disagrees with our mint glass kit (`D:\30_library\taste\kit\glass-data`, brain `rules/use-our-taste`), the kit wins.

## Data and sync (claude-android-ninja data-sync, android-data-layer, android-dev)
- The UI reads Room only. Every write goes to Room first, then to the outbox; WorkManager pushes it.
- Every synced row carries `syncStatus`, `lastModified`, `serverVersion`.
- Each repository states its conflict rule in a comment (default: server wins by event order). A local edit is never
  dropped silently: a rejected write rolls back and shows a toast.
- Retries: exponential backoff with jitter, bounded. Check connectivity (a NetworkMonitor Flow) before syncing.
- Network and database errors become a sealed `DataError` at the repository; nothing past it sees `IOException`.
- No OkHttp in a ViewModel. No DataStore for lists. No leaked repository scopes. Dispatchers are injected.
- One immutable `UiState` per screen as a `StateFlow`; one-off effects through `Channel(BUFFERED).receiveAsFlow()`.
- Collect with `collectAsStateWithLifecycle()`. Hoist Flow collection above lazy lists.
- Room schema export on; every migration has a test.

## Navigation 3 (navigation-3)
- Type-safe `NavKey` routes; back stacks in `rememberNavBackStack`.
- One back stack per bottom tab, exit through Today; re-tapping a tab scrolls its list to the top.
- The Add sheet and the task panel are scene destinations (`sceneStrategies = listOf(...)`), not loose state.
- No `hilt-navigation-compose` (it pulls Navigation 2); ViewModels via the Nav3 ViewModel decorator.
- Deep links (widget, notification, share) rebuild a back stack; `onNewIntent` calls `setIntent` and the same parser.

## Compose performance (jetpack-compose-performance)
- Measure release builds only; R8 on; a baseline profile for cold start and the Today scroll.
- Every lazy item: stable `key` (Long/String id, never index) + `contentType`. `itemsIndexed`, never `indexOf`.
- Selection is a Set behind `isSelected(id)`. 1-10 items use `Column`/`Row`, not a lazy list.
- Fast-changing values are read in layout/draw: `Modifier.offset { }`, `graphicsLayer { }`, lambda providers.
- Never write state in a later phase after reading it. `drawWithCache` for decoration; remember shaders/backdrops.
- No `BoxWithConstraints`/`SubcomposeLayout` in list items; no `onGloballyPositioned`; Coil `AsyncImage` for images.
- No `Handler.postDelayed`/`TimerTask`: `LaunchedEffect` + `delay()`.

## Edge-to-edge (edge-to-edge)
- `enableEdgeToEdge()` before `setContent`; `adjustResize` in the manifest.
- Insets applied exactly once: `Scaffold` innerPadding → `LazyColumn(contentPadding = …)` + `consumeWindowInsets`.
- Keyboard: `Modifier.fitInside(WindowInsetsRulers.Ime.current)` (or `imePadding()` before `verticalScroll`); never
  with `contentWindowInsets = safeDrawing` too.
- The glass bar draws under the navigation bar: `window.isNavigationBarContrastEnforced = false`.
- A status-bar scrim keeps icons readable over glass.

## Main thread (anr-audit-and-fix)
- Never on Main: `runBlocking`, Keystore/Tink, DAO calls, JSON parsing, bitmap decoding, `SharedPreferences.commit`.
- The SSE read loop runs on IO. Receivers finish in < 10 s (`goAsync`).
- Prove which thread a call runs on before calling it slow or safe.

## Liquid glass (io.github.kyant0:backdrop 2.0.1)
- Content under glass: `Modifier.layerBackdrop(backdrop)`. Glass: `Modifier.drawBackdrop(backdrop, shape, effects =
  { vibrancy(); blur(8.dp); lens(24.dp, 24.dp) }, highlight, onDrawSurface)`.
- Glass only on chrome (bottom bar, sheets, buttons); lists stay ink on near-white (contrast, as the web).
- Lens = Android 13+, blur = 12+, below that a tint. The A05s runs 14/15.
- Ported catalog components keep their Apache-2.0 credit in the file header.

## Testing (testing-setup, android-testing)
- A bug is fixed only when a test that was red because of it turns green.
- Fakes before mocks (interface + Default + Fake in test). Compose UI tests on Robolectric in `test/`.
- Find nodes by semantics first; `testTag` only when needed. Check state restoration.
- Room tests: in-memory database. Screenshot tests (Roborazzi) at phone sizes + 1.5× font.
- `runTest(mainRule.dispatcher)`; test callbacks directly, not through a mocked ViewModel.
- CI is the judge: unit + screenshot + emulator smoke on every push.

## Widget (glance-canonical-layouts, glance-appwidget-updates-and-memory)
- `SizeMode.Exact`, the check-list layout for Today's 3. Glance cannot blur, so the "glass" is a drawable (Phase 8,
  `res/drawable/widget_glass.xml`: white card + aurora glow from the top left + 1.25 dp mint rim; `drawable-night` = ink);
  rings are bundled vector arcs in twelfths; emoji are the bundled Fluent WebP; resource images only.
- No Compass, journal or goal words on any widget (`WidgetPrivacyTest`): a home screen is readable on a locked phone.
- After a state write call `update()`/`updateAll`; a revision counter decides re-decoding.
- `ActionCallback`: public zero-arg constructor + R8 keep; params via `ActionParameters.Key`.
- No bitmaps in `provideContent`; `onCompositionError` fallback; no foreground service from the provider.

## Security (android-intent-security, android-permissions-security)
- Exported: only the launcher activity, the share-target activity, the widget receiver.
- Every PendingIntent: `FLAG_IMMUTABLE` + explicit component.
- Share intents are untrusted: validate extras, strip `FLAG_GRANT_*`, `IntentSanitizer` for nested intents.
- Ask for notifications at first use, never at launch, never in a loop; check permissions every time.
- Tokens: per-device, stored encrypted, read off Main, never logged.

## Layout (Boss 2026-10-28: "don't make that trash thing ever again, audit and fix yourself")
1. **No layout jumps.** Chrome never moves content between states. Any header, footer or row with a conditional child (a
   back arrow, chip, count or button) reserves the child's slot with a fixed or min height or an invisible placeholder.
   Text that can wrap gets `maxLines` plus a fixed height where the content below must not move. (Close the day's star
   counter grew from ~16 dp to 48 dp when Back appeared on star 2: the card jumped ~40 px and touched the counter on star 1.)
2. **Spacing comes from named tokens** in `ui/glass/Space.kt`: `Space.xs` 4 · `sm` 8 · `md` 12 · `lg` 16 · `xl` 20 ·
   `xxl` 24 · `xxxl` 32, and the roles `Space.gutter` (screen edge), `Space.paneGap` (pane to pane), `Space.headerToCard`
   (a header or counter to its first card). No raw off-scale dp in new code. CI's `build` runs `.github/scripts/dp-gate.sh`:
   a file's count of off-scale dp (1 2 3 5 6 7 9 10 13 14 15 18 22 26) in padding/spacedBy/Spacer outside `ui/glass/` may
   only go down (`--write` records a lower count in `dp-baseline.txt`).
3. **Proof for any screen with steps or states:** a Roborazzi pair (state A and state B) plus an assert that the main
   card's top y is equal (example: `RitualsScreenshotTest.closeTheDayCardDoesNotMoveBetweenStars`).

4. **System bars show the page's own background; never a painted band** (Boss 2026-10-28: "that colour is applied
   to the status bar too"). The content melts under the status bar by an alpha mask (`Modifier.edgeFade`), the aurora
   runs unbroken from the top pixel; a full sheet runs edge to edge with transparent bars and no scrim; only a half
   sheet dims the page. Proof: `theContentMeltsUnderTheStatusBarAndTheAuroraShows`, `theFullTaskPanelRunsEdgeToEdge`,
   `aHalfSheetEndsOnTheAuroraNotABand` (pixel asserts on the top and bottom rows).

## Ours (Boss's rules)
- Respond first, save after: a tap shows its result in < 50 ms; a failed save rolls back with a toast.
- A UI change is done only after a screenshot of its open state; speed claims come with before/after numbers.
- Boss's words stay in his language. No secret printed. The maker never checks its own work: CI, screenshots, a
  fresh reviewer, and Boss's eyes for feel.
- Releases only from `main` or an `app-v*` tag; the app asks before installing an update.
- **Light only, for now.** The native screens ignore the system dark mode on purpose (walk 30 shows light); the dark
  theme is Phase 9. Do not report "dark ignored" as a bug before then.
- **Type:** words in Inter Tight (headings) and Schibsted Grotesk (body, labels); Departure Mono only on a number
  that stands alone (`Glass.NumberText`: counts, "2/3", "+2", the pairing code), as the web keeps it "for numbers".
- **Landscape:** the tab bar is 48 dp, icon beside word, at most 440 dp wide and centred; the + sits beside it.
