package org.pagel.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first JARVIS HUD. Monochrome cyan accent over near-black; neutrals do the rest.
private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink900,
    secondary = CyanDeep,
    onSecondary = TextHi,
    tertiary = CyanBright,
    background = Ink900,
    onBackground = TextHi,
    surface = Ink800,
    onSurface = TextHi,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextMid,
    outline = Line,
    outlineVariant = CyanDim,
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = CyanDeep,
    onPrimary = CreamCard,
    secondary = CyanDeep,
    onSecondary = CreamCard,
    background = Cream,
    onBackground = InkText,
    surface = CreamCard,
    onSurface = InkText,
    surfaceVariant = Color(0xFFE6EDED),
    onSurfaceVariant = InkTextMid,
    outline = Color(0xFFCBD6D6),
    error = Danger,
)

@Composable
fun PagelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PagelTypography,
        content = content,
    )
}
