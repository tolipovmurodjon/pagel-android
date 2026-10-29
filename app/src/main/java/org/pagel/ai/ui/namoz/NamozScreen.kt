package org.pagel.ai.ui.namoz

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.NamozMarkEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.habits.HabitsViewModel
import org.pagel.ai.ui.habits.rememberReminderGrants

/** The Namoz page (Boss 2026-10-28): the next prayer, five rows to tick, his own counts, his learning list, settings. */
@Composable
fun NamozRoute(contentPadding: PaddingValues, onBack: () -> Unit, vm: NamozViewModel = hiltViewModel(), habits: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val grants = rememberReminderGrants(habits)
    var settings by remember { mutableStateOf(false) }
    NamozScreen(ui, contentPadding, onBack, onTap = vm::tap, onMark = vm::mark, onStart = habits::startNamoz, onSettings = { settings = true },
        onQaza = vm::qaza, onAddLearn = vm::addLearn, onLearned = vm::setLearned, onDeleteLearn = vm::deleteLearn)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // "Use my location": the phone's last known place, read once; no place known yet = Tashkent stays.
    @android.annotation.SuppressLint("MissingPermission") // called only once ACCESS_COARSE_LOCATION is granted
    fun usePlace() {
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        val loc = runCatching { lm?.getProviders(true)?.mapNotNull { lm.getLastKnownLocation(it) }?.maxByOrNull { it.time } }.getOrNull()
        vm.save(ui.settings.copy(autoLocation = true, lat = loc?.latitude ?: ui.settings.lat, lon = loc?.longitude ?: ui.settings.lon))
    }
    val askPlace = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) usePlace() }
    val onPlace = {
        val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) usePlace() else askPlace.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (settings) NamozSettingsSheet(ui, onSave = { vm.save(it) }, onRemind = grants.remind, onPlace = onPlace) { settings = false }
}

@Composable
fun NamozScreen(
    ui: NamozUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onTap: (Namoz.Prayer) -> Unit = {}, onMark: (Namoz.Prayer, String?) -> Unit = { _, _ -> },
    onStart: () -> Unit = {}, onSettings: () -> Unit = {}, onQaza: (Int) -> Unit = {}, onAddLearn: (String) -> Unit = {},
    onLearned: (Long, Boolean) -> Unit = { _, _ -> }, onDeleteLearn: (Long) -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
                Column(Modifier.weight(1f).padding(start = Space.xs)) {
                    Text("Namoz", style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, maxLines = 1, modifier = Modifier.semantics { heading() })
                    // Today's date, the Hijri date under it (computed; may differ from the local calendar by a day).
                    Text("${ui.today.dayOfMonth} ${ui.today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} · ${ui.hijri}",
                        style = MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                GlassIconButton(TabIcons.More, "Namoz settings", size = Space.touch, plain = true, onClick = onSettings)
            }
        }
        item(key = "next") { NextPane(ui) }
        item(key = "rows") { PrayerRows(ui, onTap, onMark, onStart) }
        if (ui.tracking && ui.started) item(key = "stats") { StatsPane(ui) }
        if (ui.settings.qazaOn) item(key = "qaza") { QazaPane(ui.settings.qaza, onQaza) }
        item(key = "learn") { LearnPane(ui.learning, onAddLearn, onLearned, onDeleteLearn) }
        item(key = "end") { Spacer(Modifier.height(Space.xxl)) }
    }
}

/** The big card: the next prayer, its time and the countdown; under it the day and the Hijri date. */
@Composable
private fun NextPane(ui: NamozUi) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        // "Next · Asr", then "15:42  in 1 h 03 m" on one line; the countdown changes once a minute, never per second.
        Row(Modifier.fillMaxWidth().padding(Space.lg).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Emoji.res(ui.next.prayer.emoji)), null, Modifier.size(48.dp))
            Spacer(Modifier.width(Space.lg))
            Column(Modifier.weight(1f)) {
                Text("Next · ${ui.next.prayer.uz}", style = MaterialTheme.typography.labelLarge, color = Glass.Muted, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(ui.next.label().take(5), style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize, lineHeight = MaterialTheme.typography.headlineLarge.lineHeight, fontWeight = FontWeight.Bold),
                        color = Glass.Ink, maxLines = 1, softWrap = false)
                    Text(ui.countdown, style = Glass.NumberText, color = Glass.PrimaryInk, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = Space.md, bottom = Space.xs))
                }
            }
        }
    }
}

/** Five rows: tap = prayed (or taken back), long press = how. Before 1 November the rows wait; no red, no shaming. */
@Composable
private fun PrayerRows(ui: NamozUi, onTap: (Namoz.Prayer) -> Unit, onMark: (Namoz.Prayer, String?) -> Unit, onStart: () -> Unit) {
    var marking by remember { mutableStateOf<Namoz.Prayer?>(null) }
    val haptics = LocalHapticFeedback.current
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(Space.sm)) {
            if (!ui.tracking) {
                val press = remember { MutableInteractionSource() }
                Text("Start tracking", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press).background(Glass.PrimarySoft, CircleShape)
                        .combinedClickable(press, indication = null, role = Role.Button, onClick = onStart).padding(vertical = Space.md))
                Spacer(Modifier.height(Space.sm))
            }
            for (w in ui.windows.filter { it.prayer in Namoz.Prayer.entries }) {
                val p = w.prayer
                val done = p in ui.doneToday
                val can = ui.tracking && ui.started
                val how = ui.marksToday[p]
                val press = remember { MutableInteractionSource() }
                // Ticked = a soft mint fill; the prayer whose time it is = a faint mint wash with a hairline. Never red.
                val fill = when { done -> Glass.PrimarySoft; ui.current == p && ui.started -> Glass.Primary.copy(alpha = 0.05f); else -> Color.Transparent }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.row)
                        .background(fill, RowShape)
                        .then(if (ui.current == p && !done && ui.started) Modifier.border(1.dp, Glass.Primary.copy(alpha = 0.25f), RowShape) else Modifier)
                        .pressScale(press)
                        .combinedClickable(press, indication = null, enabled = can, role = Role.Checkbox, onLongClickLabel = "How I prayed",
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); marking = p },
                            onClick = { if (!done) haptics.performHapticFeedback(HapticFeedbackType.Confirm); onTap(p) })
                        .semantics(mergeDescendants = true) { contentDescription = "${p.uz} ${w.label()}"; stateDescription = if (done) "Prayed" else if (can) "Not yet" else "From 1 November" }
                        .padding(horizontal = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Not ticked = an empty ring, nothing more (before 1 November a fainter one).
                    val ring = if (done) Modifier.background(Glass.Primary, CircleShape)
                        else Modifier.border(1.5.dp, Glass.Ink.copy(alpha = if (can) 0.28f else 0.12f), CircleShape)
                    Box(Modifier.size(26.dp).then(ring), contentAlignment = Alignment.Center) {
                        if (done) androidx.compose.material3.Icon(TabIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(Space.md))
                    Image(painterResource(Emoji.res(p.emoji)), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.sm))
                    Column(Modifier.weight(1f)) {
                        Text(p.uz, style = MaterialTheme.typography.bodyLarge, color = if (done) Glass.Muted else Glass.Ink)
                        if (how != null) Text(howLabel(how), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                    }
                    Text(w.label(), style = Glass.NumberText, color = Glass.Muted, maxLines = 1, softWrap = false)
                }
            }
            if (!ui.started) Text(
                "Tracking starts 1 November", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
            )
        }
    }
    marking?.let { p -> MarkSheet(p, onPick = { onMark(p, it); marking = null }) { marking = null } }
}

/** Long press: On time · Later · In the mosque, or Clear. Picking one also ticks the prayer. */
@Composable
private fun MarkSheet(p: Namoz.Prayer, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    org.pagel.ai.ui.glass.GlassSheet(onDismiss) {
        org.pagel.ai.ui.glass.SheetTitle(p.uz, null)
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column {
                for (how in listOf(NamozMarkEntity.ON_TIME, NamozMarkEntity.LATER, NamozMarkEntity.MOSQUE)) {
                    org.pagel.ai.ui.habits.MenuRow(if (how == NamozMarkEntity.MOSQUE) TabIcons.Habits else TabIcons.Check, howLabel(how)) { onPick(how) }
                }
                org.pagel.ai.ui.habits.MenuRow(TabIcons.Close, "Clear") { onPick(null) }
            }
        }
        Spacer(Modifier.height(org.pagel.ai.ui.glass.SheetBottom))
    }
}

/** The Qaza counter (off unless he turns it on): his own number, never computed for him. */
@Composable
private fun QazaPane(n: Int, onQaza: (Int) -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Row(Modifier.fillMaxWidth().padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            Text("Qaza", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f))
            GlassIconButton(TabIcons.Minus, "One less", size = Space.touch, plain = true) { onQaza(-1) }
            Text("$n", style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = Glass.Ink, modifier = Modifier.padding(horizontal = Space.sm))
            GlassIconButton(TabIcons.Plus, "One more", size = Space.touch, plain = true) { onQaza(1) }
        }
    }
}

/** "What I'm learning": his own lines (a sura, a dua, a rule), ticked when learned. The app writes none of them. */
@Composable
private fun LearnPane(items: List<org.pagel.ai.data.db.NamozLearnEntity>, onAdd: (String) -> Unit, onLearned: (Long, Boolean) -> Unit, onDelete: (Long) -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(Space.md)) {
            Text("What I'm learning", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(bottom = Space.sm).semantics { heading() })
            for (l in items) androidx.compose.runtime.key(l.id) {
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(TabIcons.Check, if (l.learned) "Learned: ${l.text}" else "Mark learned: ${l.text}", size = Space.touch, plain = true, on = l.learned,
                        tint = if (l.learned) Glass.Primary else Glass.Muted) { onLearned(l.id, !l.learned) }
                    Text(l.text, style = MaterialTheme.typography.bodyLarge, color = if (l.learned) Glass.Muted else Glass.Ink, modifier = Modifier.weight(1f))
                    GlassIconButton(TabIcons.Trash, "Delete: ${l.text}", size = Space.touch, plain = true) { onDelete(l.id) }
                }
            }
            // Keyed on the count: after an add the field starts empty again.
            androidx.compose.runtime.key(items.size) {
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(TabIcons.Plus, null, tint = Glass.Muted, modifier = Modifier.padding(horizontal = Space.md).size(20.dp))
                    org.pagel.ai.ui.glass.InlineField("", "Add a sura, a dua, a rule…", "Add to what I'm learning", MaterialTheme.typography.bodyLarge, Modifier.weight(1f), onSave = onAdd)
                }
            }
        }
    }
}

private val RowShape = RoundedCornerShape(14.dp)

private fun howLabel(how: String) = when (how) {
    NamozMarkEntity.ON_TIME -> "On time"; NamozMarkEntity.LATER -> "Later"; NamozMarkEntity.MOSQUE -> "In the mosque"; else -> ""
}
