package org.pagel.ai.ui.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.pagel.ai.R

/**
 * The web's mint liquid glass, as tokens (dashboard src/app/globals.css "Mint liquid glass", from our kit
 * D:\30_library\taste\kit\glass-data, theme "mint"). Glass stays on chrome; text stays ink on near-white.
 */
object Glass {
    val Primary = Color(0xFF1C7E69)
    val PrimarySoft = Color(0xFFE3F4EC)
    val PrimaryInk = Color(0xFF0B5C45)
    val Ink = Color(0xFF0F1630)
    val Muted = Color(0xFF5B6275)
    val Page = Color(0xFFFBFDFC)
    val Aurora1 = Color(0xFF66DDA8)
    val Aurora2 = Color(0xFFA9F0CF)
    /** `.pane` background: rgb(238 249 244 / .55). */
    val PaneTint = Color(0xFFEEF9F4).copy(alpha = 0.55f)
    val Risk = Color(0xFFB3261E)

    private fun variable(res: Int, weight: FontWeight) =
        Font(res, weight, variationSettings = FontVariation.Settings(weight, FontStyle.Normal))

    /** Bundled, not downloaded: no Google Play services, no first-launch font wait. */
    val Heading = FontFamily(variable(R.font.inter_tight, FontWeight.SemiBold), variable(R.font.inter_tight, FontWeight.Bold), variable(R.font.inter_tight, FontWeight.ExtraBold))
    val Body = FontFamily(variable(R.font.schibsted_grotesk, FontWeight.Normal), variable(R.font.schibsted_grotesk, FontWeight.Medium), variable(R.font.schibsted_grotesk, FontWeight.SemiBold), variable(R.font.schibsted_grotesk, FontWeight.Bold))
    val Numbers = FontFamily(Font(R.font.departure_mono))
}

private val GlassTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Glass.Heading, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-0.96).sp),
    headlineSmall = TextStyle(fontFamily = Glass.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = Glass.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Glass.Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Glass.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Glass.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    labelSmall = TextStyle(fontFamily = Glass.Numbers, fontSize = 12.sp),
)

private val GlassColors = lightColorScheme(
    primary = Glass.Primary, onPrimary = Color.White, primaryContainer = Glass.PrimarySoft, onPrimaryContainer = Glass.PrimaryInk,
    background = Glass.Page, onBackground = Glass.Ink, surface = Color.White, onSurface = Glass.Ink, onSurfaceVariant = Glass.Muted,
    error = Glass.Risk,
)

/** The native screens' theme: light, mint, the web's fonts. The Phone screen keeps its own dark HUD theme. */
@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GlassColors, typography = GlassTypography, content = content)
}
