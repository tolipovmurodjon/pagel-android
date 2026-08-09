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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.pagel.ai.data.usage.UsagePermission
import org.pagel.ai.domain.AppCategory
import org.pagel.ai.domain.AppUsage
import org.pagel.ai.ui.hud.FocusDial
import org.pagel.ai.ui.hud.JarvisCore
import org.pagel.ai.ui.theme.MonoFamily

@Composable
fun DigitalWellbeingScreen(
    onSettings: () -> Unit = {},
    onVoice: () -> Unit = {},
    onGateway: () -> Unit = {},
    vm: WellbeingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val events by vm.storedEvents.collectAsState()
    val ctx = LocalContext.current
    // Initial load happens in WellbeingViewModel.init; REFRESH re-harvests on demand.

    Surface(Modifier.fillMaxSize().scanlines(), color = MaterialTheme.colorScheme.background) {
        if (!state.hasAccess) {
            PermissionGate(
                onGrant = { ctx.startActivity(UsagePermission.settingsIntent()) },
                onRefresh = { vm.harvestAndRefresh() },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Header(
                    focusScore = state.snapshot?.focusScore ?: 0,
                    productiveMs = state.snapshot?.productiveMs ?: 0L,
                    distractingMs = state.snapshot?.distractingMs ?: 0L,
                    activeMs = state.snapshot?.activeMs ?: 0L,
                    loading = state.loading,
                    onRefresh = { vm.harvestAndRefresh() },
                    onSettings = onSettings,
                    onVoice = onVoice,
                    onGateway = onGateway,
                )
                val list = state.snapshot?.apps.orEmpty()
                val maxMs = (list.firstOrNull()?.totalMs ?: 1L).coerceAtLeast(1L)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                ) {
                    item { SectionHeader("TOP APPS", list.size) }
                    items(list, key = { it.pkg }) { AppRow(it, maxMs) }
                    item { TrackingFooter(events) }
                }
            }
        }
    }
}

@Composable
private fun TrackingFooter(events: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Text(
            "TRACKING · $events EVENTS STORED ON-DEVICE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(
    focusScore: Int,
    productiveMs: Long,
    distractingMs: Long,
    activeMs: Long,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onVoice: () -> Unit,
    onGateway: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JarvisCore(Modifier.size(40.dp), particleCount = 130)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PAGEL", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("AWARENESS · LAST 24H", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onVoice) {
                Icon(Icons.Rounded.Mic, contentDescription = "Talk to PAGEL", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onGateway) {
                Icon(Icons.Rounded.Wifi, contentDescription = "Gateway", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = if (loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            FocusDial(focusScore, Modifier.size(190.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(Modifier.weight(1f), fmtDuration(productiveMs), "FOCUSED", MaterialTheme.colorScheme.primary)
            Stat(Modifier.weight(1f), fmtDuration(distractingMs), "DISTRACTED", MaterialTheme.colorScheme.onSurfaceVariant)
            Stat(Modifier.weight(1f), fmtDuration(activeMs), "ACTIVE", MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun Stat(
    modifier: Modifier,
    value: String,
    label: String,
    valueColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier
            .hudCorners(MaterialTheme.colorScheme.outlineVariant)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 14.dp, horizontal = 10.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFamily),
            color = valueColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRow(app: AppUsage, maxMs: Long) {
    val barColor = when (app.category) {
        AppCategory.PRODUCTIVE -> MaterialTheme.colorScheme.primary       // bright cyan
        AppCategory.DISTRACTING -> MaterialTheme.colorScheme.onSurfaceVariant // grey (neutral, not a 2nd hue)
        AppCategory.NEUTRAL -> MaterialTheme.colorScheme.secondary         // dim cyan
    }
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
                    .background(barColor),
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

/** Subtle CRT scanline texture over the whole screen (fixed; content scrolls under it). */
private fun Modifier.scanlines(): Modifier = drawWithContent {
    drawContent()
    val gap = 3.5.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(Color(0x08FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        y += gap
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(12.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Iron-Man HUD: thin L-shaped brackets at the four corners of a panel. */
private fun Modifier.hudCorners(color: Color, len: Dp = 9.dp, stroke: Dp = 1.5.dp): Modifier =
    drawBehind {
        val l = len.toPx()
        val w = stroke.toPx()
        val cw = size.width
        val ch = size.height
        drawLine(color, Offset(0f, 0f), Offset(l, 0f), w)
        drawLine(color, Offset(0f, 0f), Offset(0f, l), w)
        drawLine(color, Offset(cw, 0f), Offset(cw - l, 0f), w)
        drawLine(color, Offset(cw, 0f), Offset(cw, l), w)
        drawLine(color, Offset(0f, ch), Offset(l, ch), w)
        drawLine(color, Offset(0f, ch), Offset(0f, ch - l), w)
        drawLine(color, Offset(cw, ch), Offset(cw - l, ch), w)
        drawLine(color, Offset(cw, ch), Offset(cw, ch - l), w)
    }
