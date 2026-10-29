# Compass checker · Phase 6a (app-v0.12.0 = d14cb0f, CI 40765099471, server c1ce527)

Fresh read-only checker, 2026-10-29. Read: PHASE-BRIEF, builder brief, START HERE "Phase 6a Compass", every file in
`app/src/main/java/org/pagel/ai/{data,ui}/compass/`, CompassPrivacyTest, manifest, crash/, PagelHttp, GlassSheet,
dashboard `compass-api.ts` + `route.ts` + `compass-api.test.ts`. Opened walk shots 103-109 + XML dumps and 7 Roborazzi shots.

## Verdict: 6/10. The crypto core is sound, but one leak is proven by CI's own screenshot

The crypto core is good: AES-256-GCM with a random nonce per seal, a Keystore-wrapped data key, only ciphertext in Room
and the outbox, the machine token gets 403, and the server refuses text fields. But **walk shot `108-c-picker.png` shows
"Why I live · Walkthrough zebra purpose line" in a plain screen capture**: FLAG_SECURE does not hold on the project
page's goal picker. Three more paths send goal words or the passphrase off the sealed path: the habit, the keyboard and
accessibility. Fix #1-#4 before Boss types a single goal on 1 Nov.

## Privacy: each leak path

| Path | Verdict | Evidence (file:line) |
|---|---|---|
| Room columns | ✅ safe | CompassDb.kt:18-31: only id, kind, parent, blob, dates. Today's line is sealed by its own Keystore key (CompassDb.kt:69-71, DeviceKeys.kt:80-99) |
| Outbox payload | ✅ safe | CompassApi.kt:19 SealedItemBody; walkthrough.sh:596 regex + :598 "zebra" grep, both pass |
| OkHttp logging | ✅ safe | PagelHttp.kt:21-29: no interceptor anywhere (git grep HttpLoggingInterceptor = 0) |
| logcat | ✅ safe (own pid) | walkthrough.sh:601; CompassPrivacyTest.kt:189 (Timber + ShadowLog) |
| Crash report | ⚠️ risk | CrashLog.kt:36 uploads `e.message` (500 chars) unfiltered. No Compass path throws with words today, but nothing stops a future one |
| Notifications / widget / What's new | ✅ safe | no compass/goal ref in widget/, reminders/, service/; whats_new.xml:10 is a fixed line. Birthday alarm not built yet |
| **Today's quiet line** | ⚠️ LEAK when on | CompassHooks.kt:66-75 → TodayScreen.kt:169: goal words on a non-secure window (screenshot, Recents thumbnail, screen record), plus `contentDescription = "Compass: $line"` (CompassHooks.kt:69). Off by default |
| **Project link picker** | ❌ LEAK (proven) | CompassHooks.kt:124-125 → GlassSheet.kt:54 `ModalBottomSheet` with no `properties`: M3's default `securePolicy = Inherit` copies the non-secure project window and overrides the `addFlags` from SecureWindow (CompassSecurity.kt:32). Shot 108 is readable. Inside the Compass the parent is secure, so 104-106 come out black |
| **Habit from the weekly step** | ❌ LEAK (by design, never approved) | CompassRepository.kt:207-209 `adder.add(body.leadAction…)`: the step's words go plain into pagel.db → task outbox → pagel.uz tasks → MCP `list_tasks` and the AI chats. CompassPrivacyTest.kt:143 uses an UNMARKED step ("Walk 20 minutes"), so the proof never tests this. The habit bell also shows these words |
| Android backup | ⚠️ risk | Manifest:60 `allowBackup="false"` with targetSdk 36 stops cloud backup, but NOT device-to-device transfer (no `dataExtractionRules`). compass.db moves over as ciphertext without its Keystore key, so the new phone shows LOST. pagel.db moves the habit text |
| Recents thumbnail | ✅ Compass / ⚠️ Today | dumpsys shows `fl=…SECURE` on MainActivity (window-dump-compass.txt:208). Today with the line on is not covered |
| **Keyboard (IME learning)** | ⚠️ LEAK risk | CompassKit.kt:129: autocorrect + personalised learning ON. Gboard / Samsung Keyboard learn the goal words and suggest them in other apps, including when typing to PAGEL's AI chat. The builder's "no switch" note is wrong: `InterceptPlatformTextInput` (compose-ui ≥1.7) can OR `IME_FLAG_NO_PERSONALIZED_LEARNING` into EditorInfo |
| **Passphrase field** | ❌ LEAK + lock-out risk | GoalSheet.kt:218-219 and CompassScreen.kt:185 use SealedField: visible text, `KeyboardCapitalization.Sentences`, autocorrect ON. The IME stores the passphrase in its dictionary, and autocorrect/auto-caps can change it, so the same hands type a different string on a new phone |
| Clipboard | ⚠️ risk | BasicTextField copy/cut is enabled; Gboard/Samsung clipboard history (sometimes cloud-synced). The clip is not marked `EXTRA_IS_SENSITIVE` |
| Accessibility | ⚠️ risk | 106-c-saved.xml / 108-c-picker.xml hold the goal words (uiautomator reads them through FLAG_SECURE). Any accessibility service can read them. contentDescriptions repeat titles: CompassScreen.kt:335, :388, :452, :471, :521; GoalSheet.kt:186; CompassHooks.kt:157 |
| Server API | ✅ safe | compass-api.ts:56-79 refuses any text key and any non-envelope blob; route.ts:63 and :123 return 403 to the machine token (test.ts:98); no console logging in route.ts |
| Server: any paired device | ⚠️ risk (integrity) | compass-api.ts:30-33: ANY paired device token may read the ciphertext + recovery blob and overwrite or delete items |
| Nonce reuse | ✅ safe | CompassCrypto.kt:34 random 96-bit nonce per seal; the Keystore makes IVs for wrap/line (DeviceKeys.kt:70, :83) |
| AAD binding | ⚠️ low | CompassCrypto.kt:35: AAD = id only. kind, parent and dates are not bound, so the server can move a goal to another level or parent unseen |
| Key on new fingerprint | ✅ OK by choice | DeviceKeys.kt:62 `setInvalidatedByBiometricEnrollment(false)`: adding a fingerprint needs the PIN; removing the screen lock → KeyGone → LOST (said plainly) |
| Auth binding | ⚠️ medium | DeviceKeys.kt:60 uses a 60 s time-bound key. The plain phone unlock (keyguard) also opens it, so the BiometricPrompt is UX, not crypto. Better: timeout 0 + `BiometricPrompt.CryptoObject(cipher)` (API 30+, DEVICE_CREDENTIAL allowed) |
| Recovery KDF | ⚠️ medium | CompassCrypto.kt:26 scrypt N=2^15, r=8, p=1 (OWASP minimum) + min length 12 (GoalSheet.kt:214) with no strength check. The recovery blob sits on the shared server, where server-side AI agents have shell access → offline guessing. Use N=2^17 (or Argon2id 64 MB) and refuse obvious passphrases |

## 1 Nov ritual math

- ✅ CompassRules.kt:95-111: 31 Oct 2026 → year 2025-11-01; 1 Nov 2026 → 2026-11-01; `ageOn(2026-11-01)` = 19;
  `reportTitle(2026-10-31)` = "Year 18 → 19". The age-report shot matches. Tashkent zone (CompassRepository.kt:98).
- ❌ CompassViewModel.kt:64-65 sets `today` and `reportEnd` ONCE, when the ViewModel is created. The AnchorService keeps
  the process alive for days and the More stack keeps the ViewModel. If Boss opens the Compass on 31 Oct, it still says
  Year 18 / old season on his birthday morning. Re-read the date per emission (a ticker at Tashkent midnight).
- ⚠️ CompassScreen.kt:575: the first report (the year ending 31 Oct 2026) always shows **"0 namaz days"**, because
  counting starts 2026-11-01 (CompassRepository.kt:371). A zero tile on his birthday reads like guilt. Hide the tile when
  `reportEnd < NAMAZ_FROM`.
- ⚠️ CompassRules.kt:171: the score counts every week from season week 1, whatever `createdAt` is. A goal written in
  week 5 starts with 4 "misses", so "Two quiet weeks" (CompassScreen.kt:470) fires on day one. Start counting at the
  week of `createdAt` (or the habit's first day).

## Top 10 fixes, ranked

1. **Picker leak:** `GlassSheet(secure = true)` → `ModalBottomSheet(properties = ModalBottomSheetProperties(securePolicy =
   SecureFlagPolicy.SecureOn))`, and use it in CompassHooks.kt:124, GoalSheet.kt:64 and :204. Walk step 108 must come out black.
2. **Habit text** (CompassRepository.kt:207-209): ask Boss. Either an opt-in line ("This step becomes a normal habit:
   PAGEL can read these words") or a neutral habit title ("🧭 weekly step") with the words only in the sealed body.
   Then add a MARKED lead action to CompassPrivacyTest.kt:143.
3. **Passphrase fields** (GoalSheet.kt:218-219, CompassScreen.kt:185): `KeyboardType.Password`, `autoCorrectEnabled =
   false`, capitalization None, `PasswordVisualTransformation` + an eye toggle. Make the length rule the same (8 vs 12).
4. **IME learning** (CompassKit.kt:121-136): wrap SealedField in `InterceptPlatformTextInput` that sets
   `IME_FLAG_NO_PERSONALIZED_LEARNING`; this fixes it on Gboard and Samsung.
5. **Stale date** (CompassViewModel.kt:64-65): put `today` in the flow (a midnight ticker); add a test that
   crosses 31 Oct 23:59 → 1 Nov 00:00 inside one ViewModel.
6. **Today line** (CompassHooks.kt:66-75): while the line shows, call `setRecentsScreenshotEnabled(false)` (API 33);
   drop the words from contentDescription ("Compass line"); say "visible to anyone who sees Today" next to the switch.
7. **Accessibility:** while a Compass window is up, `view.setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES)`
   (API 34; the A05s runs it). TalkBack still works; other services do not get the words.
8. **Score start** (CompassRules.kt:171, :180): count from the creation week; add a test for a week-5 goal.
9. **Key binding** (DeviceKeys.kt:60): timeout 0 + CryptoObject on wrap/unwrap. Also raise scrypt to 2^17
   (CompassCrypto.kt:26; logN is stored, so old blobs still open).
10. **Backup + metadata:** add `android:dataExtractionRules` excluding `compass.db` from device-transfer (Manifest:60);
    put `kind` in the AAD (CompassCrypto.kt:35); hide the 0-namaz tile (CompassScreen.kt:575); skip `message` in
    CrashLog.kt:36 when the stack holds `.compass.`.

## Design (apple-design + emil-design-eng)

- ✅ `compass-empty-360x800.png`: calm, one icon, one sentence, one mint button; "Only you will ever read this" is right.
  Glass reads as glass.
- ✅ `compass-path-360x800.png`: the vertical rail with a star on top works. Level captions are quiet and the cards have air.
  The "12" in Departure Mono sits well.
- ⚠️ `compass-season-360x800.png`: the Win card is a full mint gradient slab. It reads as "selected", not "won", and the
  rule says no heavy mint surfaces. Calmer: a white glass card, a mint rim, and only the "✓ Win" pill. Measured
  contrast on that slab: "Not in my hands" 4.50:1 (borderline), "Each week" 4.63:1, counts 6.06:1.
- ⚠️ Season header "week 3 of 12 · 1 Nov → 30 Jan": 30 Jan is the 13-week end, but the text says 12. Show
  "→ 23 Jan · review week to 30 Jan" or "of 13".
- ⚠️ `compass-goal-sheet-360x800-font-1.5.png`: "This season" sits at y≈0 with no top inset, so the sheet title touches
  the edge. The "Serves" chip row has no end padding and is cut mid-word ("ship the a"; GoalSheet.kt:118 needs
  `contentPadding`/end gutter and maxWidth + ellipsis on ParentChip).
- ⚠️ `compass-age-report-360x800.png`: "namaz days, from 1 Nov" wraps to 2 lines at 1× in its tile, while the other
  tiles take 1 line. Make it "namaz days" + a caption, or hide it (fix #10).
- ✅ The locked shots and the 1.5× path/season shots have no clipping I found beyond the above.
