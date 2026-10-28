package org.pagel.ai.ui.rituals

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.reminders.NotifyPrefs
import org.pagel.ai.reminders.TaskAlarms
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TimeWheel
import org.pagel.ai.ui.glass.pressScale

/** What the Notifications page shows: the saved choices, read off Main once. */
data class NotifyUi(val tone: String = NotifyPrefs.NORMAL, val morning: String = "08:00", val evening: Boolean = true, val allowed: Boolean = true)

/** More → Notifications (round C): reads and writes NotifyPrefs off Main, plans again after each change. */
@Composable
fun NotifyRoute(padding: PaddingValues, onReplan: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<NotifyUi?>(null) }
    fun allowed() = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) { ui = withContext(Dispatchers.IO) { NotifyUi(NotifyPrefs.tone(ctx), NotifyPrefs.morning(ctx), NotifyPrefs.evening(ctx), allowed()) } }
    // Asked at first use, here, never at launch (RULES.md security).
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> ui = ui?.copy(allowed = ok); onReplan() }
    LaunchedEffect(ui?.allowed) { if (ui?.allowed == false && android.os.Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    fun save(block: (android.content.Context) -> Unit, next: NotifyUi) { ui = next; scope.launch(Dispatchers.IO) { block(ctx); onReplan() } }
    val u = ui ?: NotifyUi()
    NotifyScreen(
        u, padding,
        onTone = { t -> save({ NotifyPrefs.setTone(it, t) }, u.copy(tone = t)) },
        onMorning = { t -> save({ NotifyPrefs.setMorning(it, t) }, u.copy(morning = t)) },
        onEvening = { on -> save({ NotifyPrefs.setEvening(it, on) }, u.copy(evening = on)) },
        onAllow = { if (android.os.Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onChannel = { ch ->
            TaskAlarms.channels(ctx)
            ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    )
}

/**
 * The page: the tone (chill · normal · no mercy) with a sample line, the morning plan's time, the evening nudge, and
 * one row per channel (Tasks, Habits, Rituals) that opens Android's own switch for it, so Boss can mute one.
 */
@Composable
fun NotifyScreen(
    ui: NotifyUi, padding: PaddingValues,
    onTone: (String) -> Unit = {}, onMorning: (String) -> Unit = {}, onEvening: (Boolean) -> Unit = {}, onAllow: () -> Unit = {}, onChannel: (String) -> Unit = {},
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(bottom = Space.xl)) {
        RitualHead("Reminders, morning plan, tone", "Notifications")
        Spacer(Modifier.height(Space.headerToCard))
        if (!ui.allowed) {
            RitualPane(null) { org.pagel.ai.ui.habits.FixRow(TabIcons.Bell, "Notifications off", "Turn on", Glass.Risk, onAllow) }
            Spacer(Modifier.height(Space.paneGap))
        }
        RitualPane("Tone") {
            Segmented(listOf("Chill", "Normal", "No mercy"), NotifyPrefs.TONES.indexOf(ui.tone).coerceAtLeast(0), "Tone", fill = true) { onTone(NotifyPrefs.TONES[it]) }
            Hint(when (ui.tone) { NotifyPrefs.CHILL -> "“soft reminder, no pressure 🌱”"; NotifyPrefs.NO_MERCY -> "“NOW. not in 5 min. now ⚡”"; else -> "“lock in. 1 task. 10 min. go 🔒”" }, lines = 1)
        }
        Spacer(Modifier.height(Space.paneGap))
        RitualPane("Every day") {
            SettingRow(TabIcons.Sunrise, "Morning plan", ui.morning.ifEmpty { "Off" }, "Morning plan at ${ui.morning.ifEmpty { "off" }}") { picking = true }
            HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
            SettingRow(TabIcons.Moon, "Evening nudge", if (ui.evening) "On" else "Off", "Evening nudge ${if (ui.evening) "on" else "off"}") { onEvening(!ui.evening) }
            Hint("30 min before Close the day, while stars are left. Quiet 23:00-07:00.", lines = 2)
        }
        Spacer(Modifier.height(Space.paneGap))
        RitualPane("Channels") {
            SettingRow(TabIcons.CalendarCheck, "Tasks", "›", "Tasks channel settings") { onChannel(TaskAlarms.CH_TASKS) }
            HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
            SettingRow(TabIcons.Habits, "Habits", "›", "Habits channel settings") { onChannel(org.pagel.ai.reminders.HabitAlarmReceiver.CHANNEL) }
            HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
            SettingRow(TabIcons.Sparkles, "Rituals", "›", "Rituals channel settings") { onChannel(TaskAlarms.CH_RITUALS) }
        }
    }
    if (picking) GlassSheet(onDismiss = { picking = false }) {
        SheetTitle("Morning plan", ui.morning, onDone = { picking = false })
        TimeWheel(ui.morning.ifEmpty { "08:00" }, onChange = onMorning, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.sm))
        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, said: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.padding(end = Space.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1)
    }
}
