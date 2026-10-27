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

## START HERE v3 · TODAY UI (saved 2026-10-27 ~17:50 Tashkent, laptop session, context 57 %)
Boss's last words: "I installed it fully, it's the best ever, I love that liquid glass, real iPhone vibe… native feels
better" · "keep that style, save a rule for the navbar… Humopedia needs it" · "liquid glass not working on More, it's
simple, audit and fix it".
State:
- Boss runs **0.3.13** (release run 40499990183 green, served by the server since 12:10 UTC). He LOVES the glass bar →
  brain card `D:Brainulesliquid-glass-navbar.md` (all numbers, code paths, Humopedia reuse).
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
