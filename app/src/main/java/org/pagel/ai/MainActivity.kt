package org.pagel.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.pagel.ai.ui.hud.JarvisCore
import org.pagel.ai.ui.theme.Cyan
import org.pagel.ai.ui.theme.CyanBright
import org.pagel.ai.ui.theme.PagelTheme

/**
 * A1 host / branded boot screen — the JARVIS energy core on pure black, cyan-only HUD.
 * Proves the Hilt + Compose + downloadable-fonts stack launches on the PAGEL design system.
 * The Digital-Wellbeing dashboard replaces the body in Task 7; this core is reused as its hero.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PagelTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BootScreen()
                }
            }
        }
    }
}

@Composable
private fun BootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x142FE4D6), Color(0xFF04070A)),
                    radius = 1300f,
                ),
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            JarvisCore(Modifier.size(240.dp))
            Spacer(Modifier.size(26.dp))
            Text(
                text = "PAGEL",
                style = MaterialTheme.typography.displayLarge.copy(
                    brush = Brush.verticalGradient(listOf(CyanBright, Cyan)),
                ),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "your phone, aware",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(22.dp))
            LiveChip()
        }
        Text(
            text = "v0.1 · WEDGE A · AWARENESS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

@Composable
private fun LiveChip() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dot",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).alpha(a).clip(CircleShape).background(Cyan))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun BootScreenPreview() {
    PagelTheme(darkTheme = true) { BootScreen() }
}
