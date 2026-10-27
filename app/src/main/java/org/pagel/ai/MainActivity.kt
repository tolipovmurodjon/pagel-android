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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.pagel.ai.service.AnchorService
import org.pagel.ai.ui.gateway.GatewayDebugScreen
import org.pagel.ai.ui.home.HomeScreen
import org.pagel.ai.ui.tasks.TasksScreen
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
 * Tasks first (Boss 2026-10-27): the app opens straight into pagel.uz with no splash wait. The phone screen
 * (gateway, senses, updates) opens on top from More → Phone, and Back returns to the tasks, which stay loaded.
 */
@Composable
private fun PagelRoot() {
    var showPhone by remember { mutableStateOf(false) }
    var showGateway by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !showPhone
    }
    androidx.activity.compose.BackHandler(enabled = showPhone) { if (showGateway) showGateway = false else showPhone = false }
    Box(Modifier.fillMaxSize()) {
        TasksScreen(onOpenPhone = { showPhone = true })
        if (showPhone) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (showGateway) GatewayDebugScreen(onBack = { showGateway = false }) else HomeScreen(onOpenGateway = { showGateway = true })
            }
        }
    }
}
