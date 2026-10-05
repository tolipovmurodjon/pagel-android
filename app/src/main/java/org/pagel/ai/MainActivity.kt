package org.pagel.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import org.pagel.ai.service.AnchorService
import org.pagel.ai.ui.gateway.GatewayDebugScreen
import org.pagel.ai.ui.home.HomeScreen
import org.pagel.ai.ui.hud.JarvisCore
import org.pagel.ai.ui.theme.Cyan
import org.pagel.ai.ui.theme.CyanBright
import org.pagel.ai.ui.theme.PagelTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ensureNotifPermission()
        ContextCompat.startForegroundService(this, Intent(this, AnchorService::class.java))
        setContent {
            PagelTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PagelRoot()
                }
            }
        }
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Brief branded boot splash, then the gateway connection surface.
 *
 * The connection screen is the *whole* app right now, deliberately: everything that used to live
 * here — usage dashboard, spend, voice, the humopedia brain-sync settings — was removed so this
 * becomes a true OpenClaw client rather than a parallel private pipe. Chat is the next surface to
 * land, and it lands on top of a connection that is already proven.
 */
@Composable
private fun PagelRoot() {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1600)
        ready = true
    }
    if (!ready) {
        BootScreen()
        return
    }
    var showGateway by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showGateway) { showGateway = false }
    if (showGateway) GatewayDebugScreen(onBack = { showGateway = false }) else HomeScreen(onOpenGateway = { showGateway = true })
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
            text = "v0.1 · GATEWAY",
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
