package org.pagel.ai.ui.wellbeing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.pagel.ai.data.usage.UsagePermission
import org.pagel.ai.domain.AppUsage
import org.pagel.ai.ui.hud.JarvisCore
import org.pagel.ai.ui.theme.MonoFamily

@Composable
fun DigitalWellbeingScreen(vm: WellbeingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!state.hasAccess) {
            PermissionGate(
                onGrant = { ctx.startActivity(UsagePermission.settingsIntent()) },
                onRefresh = { vm.refresh() },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Header(
                    activeMs = state.snapshot?.activeMs ?: 0L,
                    opens = state.snapshot?.totalOpens ?: 0,
                    apps = state.snapshot?.distinctApps ?: 0,
                    loading = state.loading,
                    onRefresh = { vm.refresh() },
                )
                val list = state.snapshot?.apps.orEmpty()
                val maxMs = (list.firstOrNull()?.totalMs ?: 1L).coerceAtLeast(1L)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                ) {
                    items(list, key = { it.pkg }) { AppRow(it, maxMs) }
                }
            }
        }
    }
}

@Composable
private fun Header(activeMs: Long, opens: Int, apps: Int, loading: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JarvisCore(Modifier.size(48.dp), particleCount = 160)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PAGEL", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("AWARENESS · TODAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = if (loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(Modifier.weight(1f), fmtDuration(activeMs), "ACTIVE")
            Stat(Modifier.weight(1f), opens.toString(), "OPENS")
            Stat(Modifier.weight(1f), apps.toString(), "APPS")
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun Stat(modifier: Modifier, value: String, label: String) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 14.dp, horizontal = 12.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRow(app: AppUsage, maxMs: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                fmtDuration(app.totalMs),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        // thin usage bar — encodes time by length, single accent
        Box(
            Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((app.totalMs.toFloat() / maxMs).coerceIn(0.02f, 1f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${app.opens} opens",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        JarvisCore(Modifier.size(180.dp), particleCount = 420)
        Spacer(Modifier.height(28.dp))
        Text(
            "Grant Usage Access",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "PAGEL needs Usage Access to see your apps, open counts and screen time. " +
                "Everything stays on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text("GRANT USAGE ACCESS", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(12.dp))
        Text(
            "Granted it? Tap to refresh.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).padding(8.dp),
        )
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) { Text("REFRESH", style = MaterialTheme.typography.labelLarge) }
    }
}

private fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "${m}m"
        else -> "${ms / 1000L}s"
    }
}
