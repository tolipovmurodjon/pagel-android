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
- `SizeMode.Exact`, the check-list layout, solid mint (Glance cannot draw glass).
- After a state write call `update()`/`updateAll`; a revision counter decides re-decoding.
- `ActionCallback`: public zero-arg constructor + R8 keep; params via `ActionParameters.Key`.
- No bitmaps in `provideContent`; `onCompositionError` fallback; no foreground service from the provider.

## Security (android-intent-security, android-permissions-security)
- Exported: only the launcher activity, the share-target activity, the widget receiver.
- Every PendingIntent: `FLAG_IMMUTABLE` + explicit component.
- Share intents are untrusted: validate extras, strip `FLAG_GRANT_*`, `IntentSanitizer` for nested intents.
- Ask for notifications at first use, never at launch, never in a loop; check permissions every time.
- Tokens: per-device, stored encrypted, read off Main, never logged.

## Ours (Boss's rules)
- Respond first, save after: a tap shows its result in < 50 ms; a failed save rolls back with a toast.
- A UI change is done only after a screenshot of its open state; speed claims come with before/after numbers.
- Boss's words stay in his language. No secret printed. The maker never checks its own work: CI, screenshots, a
  fresh reviewer, and Boss's eyes for feel.
- Releases only from `main` or an `app-v*` tag; the app asks before installing an update.
