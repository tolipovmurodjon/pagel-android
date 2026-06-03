package org.pagel.ai.ui.theme

import androidx.compose.ui.graphics.Color

// PAGEL — JARVIS HUD palette. ONE accent (cyan), everything else neutral. No gold, no second hue:
// data series are encoded by brightness/alpha of the same cyan (anti-slop rule #1).

// The single accent
val Cyan = Color(0xFF2FE4D6)        // primary glow
val CyanBright = Color(0xFF8CF6EC)  // hot core / highlight
val CyanDeep = Color(0xFF13908A)    // dim accent / secondary
val CyanDim = Color(0xFF0C5651)     // faint guides

// Near-black ground (ref interface is pure black; surfaces lift a hair with a blue cast)
val Ink900 = Color(0xFF04070A)      // background
val Ink800 = Color(0xFF080D12)      // surface
val Ink700 = Color(0xFF0D141A)      // surfaceVariant / panels
val Ink600 = Color(0xFF131C24)
val Line = Color(0xFF13313A)        // hairline strokes / outline
val TextHi = Color(0xFFDCEEF0)      // cool white
val TextMid = Color(0xFF7E969C)     // cool grey labels

// Light ground (rarely used — app is dark-first JARVIS; kept for completeness)
val Cream = Color(0xFFF3F6F6)
val CreamCard = Color(0xFFFFFFFF)
val InkText = Color(0xFF0C1316)
val InkTextMid = Color(0xFF49595F)

val Danger = Color(0xFFE5484D)      // alerts only — never decorative
