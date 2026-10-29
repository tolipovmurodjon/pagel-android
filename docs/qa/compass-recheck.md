# Compass privacy re-check · fix round (app-v0.12.1 = cd8da1c, CI 40830769241, server origin/feat/gtd-core 223e58b)

Fresh read-only re-checker, 2026-10-29. Read: the first check (`docs/qa/compass-checker.md`, 6/10), the FIX ROUND block
in `docs/native-progress.md`, the full `app-v0.12.0..cd8da1c` diff of `app/src/main` (1,629 lines), the new tests,
dashboard `compass-api.ts` + `api/v1/[...path]/route.ts`. Opened walk shots 108/109 with their XML, the fake-pagel log,
logcat, the window dump, the JUnit XML and 3 Roborazzi shots.

## SAFE TO WRITE GOALS: yes

Every privacy leak from the first check is closed and has proof. Walk shot `108-c-picker.png` is now **fully black**,
while its XML still holds "Walkthrough zebra purpose line", so the words were on screen and the capture was blocked.
`109-c-linked.png` shows the plain project page with only "Serves a goal ✓". "zebra" appears 0 times in
`fake-pagel.log` and 0 times in `logcat-compass.txt`. The outbox body is `{kind, parent, blob, starts, ends, deleted, base}`
with a base64 blob. All Compass suites pass with 0 skipped: CompassPrivacy 6, CompassLeak 7, CompassSecurity 4,
CompassCrypto 5, CompassRules 13, CompassDay 1, CompassSchema 2, CrashLog 4, CompassScreenshot 13.
One new integrity bug remains (N1 below). It cannot leak a word, but it can quietly let the pagel.uz copy go stale.
Fix it before Boss counts on the recovery copy.

## First check's items → now

| Item | Now | Evidence |
|---|---|---|
| ❌ Project link picker (shot 108) | ✅ closed | CompassHooks.kt:132 `GlassSheet(secure = true)` → GlassSheet.kt `ModalBottomSheetProperties(securePolicy = SecureOn)`; shot 108 black; CompassSecurityTest `aCompassSheetIsSecureOverAPlainPage` |
| ❌ Habit from weekly step | ✅ closed | CompassRepository.kt:360-365 adds the habit as `CompassRules.stepTitle()` = "🧭 Compass step" (2, 3…). CompassPrivacyTest:194-200 uses a MARKED step and scans pagel.db `tasks`, and :207 scans every file on the phone |
| ❌ Passphrase field | ✅ closed | CompassKit.kt `keyboardFor(secret)`: Password keyboard, autocorrect off, caps None, masked with an eye. GoalSheet.kt:224-225 and CompassScreen.kt:189 use `secret = true`. One rule on both screens (`PASSPHRASE_MIN` 12 + `passphraseOk`). Test `thePassphraseIsAPasswordFieldThatDoesNotLearn` passed |
| ⚠️ Keyboard learning | ✅ closed | `PrivateKeyboard` (InterceptPlatformTextInput) ORs `IME_FLAG_NO_PERSONALIZED_LEARNING` into the field's request after Compose fills it. All 13 Compass fields are SealedField, and there is no other text field in `ui/compass` |
| ⚠️ Today's quiet line | ✅ closed | CompassHooks.kt:71 `SecureWindow()` in the line, and its description no longer repeats the words. Test `todaysWindowIsSecureOnlyWhileTheLineShows` passed |
| ⚠️ Crash report message | ✅ closed | CrashLog.kt `touchesCompass` (the whole cause chain) → message "(hidden…)", stack = types + frames. CompassLeakTest :32 and :40 passed |
| ⚠️ Backup / device transfer | ✅ closed | Manifest `dataExtractionRules` + `fullBackupContent`. Both exclude compass.db plus its -wal and -shm, for cloud and for device transfer. pagel.db now holds only the neutral habit title |
| ⚠️ Auth binding (60 s key) | ✅ closed | DeviceKeys.kt: timeout 0 + `askUnlock(…, CryptoObject(cipher))`, guard alias `_v2`. Walk 104 opened with the PIN, so the real Keystore path works on the emulator |
| ⚠️ Recovery KDF | ✅ closed | CompassCrypto.kt: Argon2id 64 MB, 3 passes, p=1. The cost is stored in the blob. The passphrase must pass the strength rule |
| ⚠️ AAD binding | ✅ closed | `aad(id, kind, parent, starts, ends)` is used in seal/open (Repository.kt:267, :377). The test proves that a blob relabelled "someday" does not open (PrivacyTest:183) |
| ⚠️ Any paired device writes | 🟡 partly closed | compass-api.ts `apiPutItem`: a stale `base` gets 409. This stops accidental overwrites, not a hostile device (it can GET the version first). `/links` and `/recovery` have no version check, so a paired device can delete the recovery blob |
| ⚠️ Accessibility | 🟡 open by choice | Boss's choice, so TalkBack keeps working. PickRow still has `"Serves: $label"`, and 108-c-picker.xml shows the words. Any accessibility service he enables can read them |
| ⚠️ Clipboard | 🟡 partly closed | A masked passphrase cannot be copied (Password transform). Goal fields, and the passphrase while the eye shows it, can still be copied (by choice) |
| Room / outbox / OkHttp / logcat / nonce / server text | ✅ still safe | No new log call in compass/; the server takes only `ITEM_KEYS` and still returns 403 to the machine token (route.ts:105, :209) |
| Notifications (new) | ✅ safe | CompassAlarmReceiver `text()` is fixed text ("Year 19 starts today", "Season review week"); the planner reads only kinds and dates (`seasonStarts`); receiver not exported, intent immutable. CompassLeakTest :83 passed |
| Stale date (31 Oct → 1 Nov) | ✅ closed | CompassViewModel `day` flow + midnight ticker + `stay()` refresh; CompassDayTest passed |
| 0 namaz tile / score from week 1 | ✅ closed | the tile shows `namazStart()` before 1 Nov; `firstWeek()` in score/reset/WeekDots |
| Design leftovers | ✅ closed | season shot: white glass + mint rim + "✓ Win" pill, "week 3 of 12 · 1 Nov → 23 Jan, review to 30 Jan"; the goal sheet at 1.5× has top air and the chip ends in "…" |

## New findings, ranked

1. **N1 · medium (integrity, no privacy) · a 409 drops the change for good.**
   - **What happens:** CompassRepository.kt:328 treats a `Refused(409)` like any other 4xx, so it runs `dao.sent(op.id)`. The row keeps its old `serverVersion`, and nothing pulls again (`pullIfNew` runs only when there is no key). From then on, every edit of that goal sends the stale base, gets 409 and is dropped. The goal stays fine on the phone, but the pagel.uz copy freezes, and a new phone recovers the old text.
   - **How it starts on one phone:** a PUT succeeds but the reply is lost (a network drop), so the retry carries the old base.
   - **Fix:** on 409, keep the op and take the `version` that the 409 body returns. That version is the newest one, so overwrite with `base = version` (one phone is the writer); or pull and merge. Add a test: lost response → retry → 409 → the next send succeeds.
2. **N2 · low · a race can undo a version.** `write()` (Repository.kt:266-267) reads `old.serverVersion` and then upserts it. If the worker's `setServerVersion` lands between those two steps, the stale version is written back, and the next send hits N1. Fix: do not touch `serverVersion` in `write()` (use an UPDATE of the other columns, or one transaction).
3. **N3 · low · the walkthrough does not enforce the proof.** The black shot 108 is checked by eye only. The `dumpsys … SECURE` check is a WARN (walkthrough.sh:581). Make both hard: fail if more than ~1 % of the pixels in 108 are not black, and fail if SECURE is missing.
4. **N4 · low · CompassSchemaTest cannot catch a schema change.** The Compass schema JSON is not committed (no `app/schemas/org.pagel.ai.data.compass…`), and CI makes it fresh on each run. This round added `reviewOn` to v1 with no version bump. That is harmless only because no phone has compass.db yet (the latest release is 0.3.28). Commit `CompassDatabase/1.json` before the first release; any later column then needs v2 + a Migration.
5. **N5 · info · the Today line makes the whole app FLAG_SECURE.** While the line shows on Today, every screen of the single activity is secure, so Boss cannot screenshot or record Today. That is intended, but say it in What's new.

## Score: 8.5 / 10

The privacy core is now proven in the real emulator run (shot 108 black, 0 words on the wire or in logcat) as well as in unit tests. The keyboard, the passphrase, the habit and the crash leaks are closed in code, and tests pin them. The 1.5 points lost are N1 (the server copy can freeze without a word) plus the two exposures left by choice (accessibility, clipboard). Fix N1 + N2 and make N3 a hard gate to reach 9.5.
