package org.pagel.ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import org.pagel.ai.R

// Loaded via the GMS downloadable-fonts provider (no TTFs shipped; cached on device).
// Display = Space Grotesk (geometric, techy), Body = Inter (clean UI), Numeric = JetBrains Mono.
private val Provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val SpaceGrotesk = GoogleFont("Space Grotesk")
private val Inter = GoogleFont("Inter")
private val JetBrainsMono = GoogleFont("JetBrains Mono")

val DisplayFamily = FontFamily(
    Font(googleFont = SpaceGrotesk, fontProvider = Provider, weight = FontWeight.Medium),
    Font(googleFont = SpaceGrotesk, fontProvider = Provider, weight = FontWeight.SemiBold),
    Font(googleFont = SpaceGrotesk, fontProvider = Provider, weight = FontWeight.Bold),
)

val BodyFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.SemiBold),
)

/** Use for stats/numbers (screen time, counts) so digits align and read like a HUD. */
val MonoFamily = FontFamily(
    Font(googleFont = JetBrainsMono, fontProvider = Provider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMono, fontProvider = Provider, weight = FontWeight.Bold),
)

val PagelTypography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 2.sp),
)
