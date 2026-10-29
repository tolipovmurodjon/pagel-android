package org.pagel.ai.ui.namoz

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.hilt.navigation.compose.hiltViewModel
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
                Text("Namoz", style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, modifier = Modifier.weight(1f).padding(start = Space.xs).semantics { heading() })
                GlassIconButton(TabIcons.More, "Namoz settings", size = Space.touch, plain = true, onClick = onSettings)
            }
        }
        item(key = "next") { NextPane(ui) }
        item(key = "rows") { PrayerRows(ui, onTap, onMark, onStart) }
        if (ui.tracking) item(key = "stats") { StatsPane(ui) }
        if (ui.settings.qazaOn) item(key = "qaza") { QazaPane(ui.settings.qaza, onQaza) }
        item(key = "learn") { LearnPane(ui.learning, onAddLearn, onLearned, onDeleteLearn) }
        item(key = "end") { Spacer(Modifier.height(Space.xxl)) }
    }
}

/** The big card: the next prayer, its time and the countdown; under it the day and the Hijri date. */
@Composable
private fun NextPane(ui: NamozUi) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Row(Modifier.fillMaxWidth().padding(Space.lg).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Emoji.res(ui.next.prayer.emoji)), null, Modifier.size(48.dp))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Next · ${ui.next.prayer.uz}", style = MaterialTheme.typography.labelLarge, color = Glass.Muted)
                Text(ui.next.label().take(5), style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize, fontWeight = FontWeight.Bold), color = Glass.Ink)
                Text("${ui.today.dayOfMonth} ${ui.today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} · ${ui.hijri}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
            Text(ui.countdown, style = Glass.NumberText, color = Glass.PrimaryInk)
        }
    }
}

/** Five rows: tap = prayed (or taken back), long press = how. Before 1 November the rows wait; no red, no shaming. */
@Composable
private fun PrayerRows(ui: NamozUi, onTap: (Namoz.Prayer) -> Unit, onMark: (Namoz.Prayer, String?) -> Unit, onStart: () -> Unit) {
    var marking by remember { mutableStateOf<Namoz.Prayer?>(null) }
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
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.row)
                        .background(if (ui.current == p) Glass.PrimarySoft.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
                        .combinedClickable(press, indication = null, enabled = can, role = Role.Checkbox, onLongClickLabel = "How I prayed",
                            onLongClick = { marking = p }, onClick = { onTap(p) })
                        .semantics(mergeDescendants = true) { contentDescription = "${p.uz} ${w.label()}"; stateDescription = if (done) "Prayed" else if (can) "Not yet" else "From 1 November" }
                        .padding(horizontal = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(26.dp).background(if (done) Glass.Primary else Glass.Ink.copy(alpha = 0.08f), CircleShape), contentAlignment = Alignment.Center) {
                        if (done) androidx.compose.material3.Icon(TabIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(Space.md))
                    Image(painterResource(Emoji.res(p.emoji)), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.sm))
                    Column(Modifier.weight(1f)) {
                        Text(p.uz, style = MaterialTheme.typography.bodyLarge, color = if (done) Glass.Muted else Glass.Ink)
                        if (how != null) Text(howLabel(how), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                    }
                    Text(w.label(), style = Glass.NumberText, color = Glass.Muted)
                }
            }
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

/** His own counts: full days this week, each prayer over 30 days, the month as dots, mosque prayers this month. */
@Composable
private fun StatsPane(ui: NamozUi) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            val (full, of) = ui.week
            Text(if (of == 0) "Counts start on 1 November" else "$full/$of full days this week", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            for ((p, n, days) in ui.perPrayer) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.uz, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, modifier = Modifier.width(72.dp))
                Box(Modifier.weight(1f).height(8.dp).background(Glass.Ink.copy(alpha = 0.08f), CircleShape)) {
                    if (days > 0 && n > 0) Box(Modifier.fillMaxWidth(n / days.toFloat()).height(8.dp).background(Glass.Primary, CircleShape))
                }
                Text("$n/$days", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(start = Space.sm).width(44.dp))
            }
            // The month: one dot per day, its fill = prayers ticked that day (an empty day is just an empty dot).
            ui.month.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((d, set) in week) Box(
                        Modifier.size(18.dp).background(if (set.isEmpty()) Glass.Ink.copy(alpha = 0.06f) else Glass.Primary.copy(alpha = 0.2f + 0.16f * set.size), CircleShape)
                            .semantics { contentDescription = "${d.dayOfMonth}: ${set.size} of 5" },
                    )
                }
            }
            if (ui.mosque > 0) Text("${ui.mosque} in the mosque this month", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
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

private fun howLabel(how: String) = when (how) {
    NamozMarkEntity.ON_TIME -> "On time"; NamozMarkEntity.LATER -> "Later"; NamozMarkEntity.MOSQUE -> "In the mosque"; else -> ""
}
