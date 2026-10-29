package org.pagel.ai.ui.habits

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetLabel
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TimeWheel
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.headline

internal fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

// ── The reminder sheet ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun ReminderSheet(
    t: TaskEntity, on: Boolean, exact: Boolean, notify: Boolean, onToggle: (Boolean) -> Unit, onTime: (String) -> Unit,
    onFixNotify: () -> Unit, onFixExact: () -> Unit, onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) { ReminderContent(t, on, exact, onToggle, onTime, notify, onFixNotify, onFixExact); Spacer(Modifier.height(SheetBottom)) }
}

/** Notifications allowed for PAGEL, and the habits channel not switched off (it is made at the first reminder). */
fun notificationsOn(ctx: android.content.Context): Boolean {
    if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
    val channel = ctx.getSystemService(android.app.NotificationManager::class.java)?.getNotificationChannel(org.pagel.ai.reminders.HabitAlarmReceiver.CHANNEL)
    return channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
}

/**
 * A habit's reminder: one switch and Apple's time wheel. On = a notification at that time on the days it repeats,
 * with a Done button that ticks it even offline. Without the exact-alarm grant it says it may come up to 10 min late.
 */
@Composable
fun ReminderContent(
    t: TaskEntity, on: Boolean, exact: Boolean, onToggle: (Boolean) -> Unit, onTime: (String) -> Unit,
    notify: Boolean = true, onFixNotify: () -> Unit = {}, onFixExact: () -> Unit = {},
) {
    // A range's reminder rings at its start.
    val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty) ?: "08:00"
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Reminder", time)
        Text(titleOf(t), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val press = remember { MutableInteractionSource() }
            val haptics = LocalHapticFeedback.current
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(press)
                    .clickable(press, indication = null, role = Role.Switch) { haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onToggle(!on) }
                    .semantics(mergeDescendants = true) { toggleableState = ToggleableState(on); stateDescription = if (on) "On" else "Off"; contentDescription = "Remind me" }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (on) TabIcons.BellRing else TabIcons.Bell, contentDescription = null, tint = if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.md))
                Text("Remind me", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                Switch(on)
            }
        }
        Spacer(Modifier.height(16.dp))
        SheetLabel("Time", Modifier.padding(start = 16.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TimeWheel(time, onChange = onTime, modifier = Modifier.padding(vertical = 8.dp))
        }
        // A missing grant is a row Boss can tap, never a red paragraph (checker #6): notifications first (without them
        // nothing rings at all), then the exact time (without it a reminder may come up to 10 min late).
        when {
            !on -> Hint("Off. The habit still shows here every day.")
            !notify -> FixRow(TabIcons.Bell, "Notifications off", "Turn on", Glass.Risk, onFixNotify)
            !exact -> FixRow(TabIcons.Clock, "Exact time", "Allow", Glass.Ink, onFixExact)
            else -> Hint("At $time on the days it repeats, with a Done button.")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = Space.md))
}

/** A missing grant: its icon and name, and the one word that opens the right Settings page, on a 48 dp row. */
@Composable
internal fun FixRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: String, tone: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).heightIn(min = 48.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = action, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label · $action" }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1,
            modifier = Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = Space.lg, vertical = 8.dp))
    }
}

/** Apple's switch: a capsule that fills mint, a white knob that springs across. Drawn only; the row is the target. */
@Composable
internal fun Switch(on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "knob")
    Box(
        // On = bright mint (Boss's colour theory), not a solid #1C7E69 slab (checker #9); the white knob carries the shape.
        Modifier.size(width = 51.dp, height = 31.dp)
            .background(if (on) Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)) else androidx.compose.ui.graphics.SolidColor(Glass.Ink.copy(alpha = 0.16f)), CircleShape).padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.graphicsLayer { translationX = x * 20.dp.toPx() }.size(27.dp).background(Color.White, CircleShape))
    }
}

/**
 * The grants a reminder needs (notifications, exact alarms), read on every resume, and the one call that turns a
 * habit's reminder on: it asks for notifications at first use (never at launch) and then for exact alarms once.
 * The Habits tab (the Namoz sheet) and each habit's page share it, so the bell lives on the page, not on the row.
 */
internal class ReminderGrants(
    val exact: Boolean, val notify: Boolean, val remind: (String, Boolean) -> Unit, val fixNotify: () -> Unit, val fixExact: () -> Unit,
)

@Composable
internal fun rememberReminderGrants(vm: HabitsViewModel): ReminderGrants {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val alarms = remember { ctx.getSystemService(android.app.AlarmManager::class.java) }
    var exact by remember { mutableStateOf(true) }
    var notify by remember { mutableStateOf(true) }
    fun read() {
        exact = alarms == null || org.pagel.ai.reminders.HabitAlarms.canExact(alarms)
        notify = notificationsOn(ctx)
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { read(); onPauseOrDispose {} }
    fun openExact() = runCatching {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}")))
    }
    fun askExact() = vm.askExactOnce(needed = alarms != null && !org.pagel.ai.reminders.HabitAlarms.canExact(alarms)) { openExact() }
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        read(); if (granted) askExact()
    }
    return ReminderGrants(
        exact = exact, notify = notify,
        remind = { id, on ->
            vm.setReminder(id, on)
            if (on) {
                val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needs) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) else askExact()
            }
            read()
        },
        fixNotify = {
            runCatching {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        },
        fixExact = { openExact() },
    )
}
