# Phase brief — read this first (builder and checker agents)

The native PAGEL Android app is being rebuilt in 10 phases, every pagel.uz feature 1:1 or better, native, liquid glass,
Apple-clean. Plan: `C:\Users\Murodjon\.claude\plans\tranquil-wondering-melody.md` (top section, the 10-phase table).
Repo `D:\PAGEL\android` (= `D:\10_projects\pagel\android`), branch `feat/native-app`. Web: `D:\10_projects\pagel\dashboard\app`
(branch `feat/gtd-core`). Rules file: `docs/RULES.md`. Progress + START HERE blocks: `docs/native-progress.md`.

## Hard limits (Boss's words and standing rules)
- CI is the judge (GitHub Actions `build` = unit + Roborazzi, `smoke` = emulator walkthrough). The laptop cannot build Android.
  ONE push per CI cycle (cancel-in-progress kills the earlier run). Never tag `app-v*` (the lead tags), never touch `main`.
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_017aVWmLTQFpbj9PURu2wM7W`
- Server changes: commit + push the dashboard change on `feat/gtd-core` with vitest for it, then say "DEPLOY NEEDED <sha>" in your
  report. The lead deploys with `~/bin/deploy-dashboard.sh <label>` on the server (130.61.104.240, shared with lexion.uz production:
  tests gate, backup, worktree build, restart only pagel-dashboard, lexion check). You may read the server; do not restart anything.
  Stop only PIDs you started (never `pkill -f`/`pkill -x`). Until it is deployed, the fake server in CI must accept the new field.
- Never read/print secrets (`.env`, `*token*`, `*secret*`, `~/.config/pagel/*`, `~/.openclaw/openclaw.json`).
- Do not spawn agents. Final report ≤ 200 lines.

## The look (Boss's rules)
- "Everything liquid glass with our colour theory mint." Glass must read as glass on a plain page: clear white ~28-32 %, conic rim
  (white .95 → mint 55 % → white) 1.25 dp, Shadow(.45). A 55 % mint tint = flat slab (scored 1/10).
- No spinners, no "Syncing…": show the saved copy, sync silently, skeleton only on first start. Toasts: light glass + icon, never black.
- No dull green surfaces (white, ink, or bright mint). Concise: one icon beats many words; icons from lucide-react paths.
- Text ≥ 4.5:1, marks ≥ 3:1; mint thin line #1C7E69; label on mint pill #0B5C45. 48 dp targets. Works at 1.5× font, 360 dp width.
- Every tap answers < 50 ms: Room first, outbox after; a refused write rolls back with a red toast.
- Apple feel: pickers are wheels/sheets with haptic ticks, current choice marked (✓ or fill), springs not tweens for interruptible motion.
- CI emulator has a software GPU: a first frame > 5 s = launch ANR. Keep first frames light (no blur on panes).

- **Layout (RULES.md "Layout", Boss 2026-10-28):** no layout jumps (a conditional back arrow, chip, count or button keeps
  its slot: fixed/min height or an invisible placeholder; wrapping text gets maxLines + a fixed height where the content
  below must not move); spacing only from `Space` tokens (4 · 8 · 12 · 16 · 20 · 24 · 32, roles gutter / paneGap /
  headerToCard); every screen with steps or states proves it with a Roborazzi pair + an equal-top assert on the main card.

## Builder loop
server endpoint (if missing) + vitest → deploy (procedure above) → Room (migration + test) → repository (Room first + outbox) →
ViewModel → screens from `ui/glass/` kit → unit + Roborazzi (360 dp + 1.5× font) → walkthrough steps + fake-pagel asserts
(`.github/scripts/fake-pagel.py`, `walkthrough.sh`) → push → read CI → download artifacts, LOOK at every new screenshot (Read the PNG)
before calling it done. Done = build ✅ + smoke ✅ + screenshots looked at + START HERE block updated.

## Checker loop (fresh agent, read-only)
Download the green run's artifacts (`gh run download <id> -R tolipovmurodjon/pagel-android -n screenshots-and-reports` and
`-n smoke-evidence`), open every screenshot, read the web source of the same feature, apply skills `apple-design` +
`emil-design-eng`, compute contrast. Return: top 10 fixes ranked (screenshot → what → fix file:line → rule), parity gaps vs the web,
broken/clipped states, a score /10. Never "looks fine" without naming the file.
