package org.pagel.ai.ui.compass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.compass.CompassRepository
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.GoalProgress
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.compass.NoteKind
import org.pagel.ai.data.compass.Score
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.rituals.Hint
import org.pagel.ai.ui.rituals.MintButton
import org.pagel.ai.ui.rituals.QuietButton
import org.pagel.ai.ui.rituals.Segmented
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Compass can ask for. Tests pass no-ops; the route wires them to the ViewModel and the system unlock. */
data class CompassActions(
    val unlock: (passphrase: CharArray?, startOver: Boolean) -> Unit = { _, _ -> },
    val save: (Goal, String?) -> Unit = { _, _ -> },
    val delete: (Goal, String?) -> Unit = { _, _ -> },
    val tickKeyResult: (Goal, Int) -> Unit = { _, _ -> },
    val door: (Goal) -> Unit = {},
    val reset: (Goal) -> Unit = {},
    val note: (kind: String, day: String, answers: List<String>, id: String) -> Unit = { _, _, _, _ -> },
    val setLine: (Boolean, Goal?) -> Unit = { _, _ -> },
    val setRecovery: (CharArray?) -> Unit = {},
    val lock: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val setBirthday: (Boolean) -> Unit = {},
    val setReview: (Boolean) -> Unit = {},
)

/** The Compass behind More (Phase 6a): screenshots blocked, the key wiped 60 s after Boss leaves. */
@Composable
fun CompassRouteScreen(padding: PaddingValues, vm: CompassViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    SecureWindow()
    AutoLock(onLeave = vm::leave, onStay = vm::stay)
    fun ask(after: (javax.crypto.Cipher) -> Unit) {
        val activity = ctx.findActivity() ?: return
        vm.beginUnlock { cipher ->
            askUnlock(activity, "Open your Compass", cipher) { a ->
                when (a) {
                    is UnlockAnswer.Ok -> after(a.cipher)
                    UnlockAnswer.Cancelled -> {}
                    is UnlockAnswer.Failed -> vm.said(a.why)
                }
            }
        }
    }
    CompassScreen(
        ui, padding,
        CompassActions(
            unlock = { pass, over -> ask { c -> vm.unlocked(c, pass, over) } },
            save = vm::save, delete = vm::delete, tickKeyResult = vm::tickKeyResult, door = vm::closeAsDoor, reset = vm::reset,
            note = { kind, day, answers, id -> vm.saveNote(kind, day, answers, id) },
            setLine = vm::setLine, setRecovery = vm::setRecovery, lock = vm::lock, setBirthday = vm::setBirthday, setReview = vm::setReview,
            openSettings = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } },
        ),
    )
}

/** A goal being written: the level and parent are fixed by where Boss tapped; the sheet fills the rest. */
data class Draft(val goal: Goal, val parents: List<Goal>)

@Composable
fun CompassScreen(ui: CompassUi, padding: PaddingValues, act: CompassActions = CompassActions(), startTab: Int = 0, startDraft: Draft? = null) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var draft by remember { mutableStateOf(startDraft) }
    var recovery by remember { mutableStateOf(false) }
    when (ui.state) {
        VaultState.READING -> Box(Modifier.fillMaxSize())
        VaultState.NO_LOCK -> Frame(
            padding, icon = CompassIcons.Lock, title = "Set a screen lock first",
            line = "Your goals are locked with your fingerprint or PIN. This phone has neither yet.", message = ui.message,
        ) { MintButton("Open settings", TabIcons.ExternalLink, Modifier.fillMaxWidth(), onClick = act.openSettings) }
        VaultState.NEW -> Frame(
            padding, icon = CompassIcons.Compass, title = "Write why you live",
            line = "Only you will ever read this. Your goals are sealed on this phone; pagel.uz keeps only a locked copy.", message = ui.message,
        ) { MintButton("Start my Compass", CompassIcons.Lock, Modifier.fillMaxWidth()) { act.unlock(null, false) } }
        VaultState.LOCKED -> Frame(
            padding, icon = CompassIcons.Lock, title = "Your Compass is locked",
            line = "Only your fingerprint or PIN opens it.", message = ui.message,
        ) { MintButton("Unlock", CompassIcons.KeyRound, Modifier.fillMaxWidth()) { act.unlock(null, false) } }
        VaultState.LOST -> LostFrame(ui, padding, act)
        VaultState.OPEN -> OpenCompass(ui, padding, act, tab, onTab = { tab = it }, onWrite = { draft = it }, onRecovery = { recovery = true })
    }
    if (ui.state == VaultState.OPEN) {
        draft?.let { d -> GoalSheet(d, ui, act, onDismiss = { draft = null }) }
        if (recovery) RecoverySheet(on = ui.vault.recovery != null, act = act, onDismiss = { recovery = false })
    }
}

/** A calm locked-state page: one glass circle with its icon, one title, one line, one button, a reserved message slot. */
@Composable
private fun Frame(padding: PaddingValues, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, line: String, message: String?, button: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = Space.xxxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), Glass.Aurora2)), CircleShape)
                .border(1.25.dp, Brush.linearGradient(listOf(Color.White, Glass.Aurora1)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(Space.xxl))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.sm))
        Text(line, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xxl))
        button()
        // The message slot is always laid out (RULES.md Layout 1): a failed unlock never moves the button.
        Box(Modifier.fillMaxWidth().height(Space.xxxl), contentAlignment = Alignment.Center) {
            if (message != null) Hint(message, color = Glass.Ink)
        }
    }
}

/** A new phone with sealed goals and no key: say so plainly; the recovery passphrase opens them, or start over. */
@Composable
private fun LostFrame(ui: CompassUi, padding: PaddingValues, act: CompassActions) {
    var pass by remember { mutableStateOf("") }
    val canRecover = ui.vault.serverRecovery != null || ui.vault.recovery != null
    Frame(
        padding, icon = CompassIcons.KeyRound, title = "This phone cannot open your goals",
        line = if (canRecover) "The key lived on your old phone. Your recovery passphrase opens the locked copy." else
            "The key lived on your old phone, and no recovery passphrase was set. Nobody can open the old goals, not even pagel.uz.",
        message = ui.message,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (canRecover) {
                SealedField(pass, { pass = it }, label = "Recovery passphrase", hint = "Your passphrase", secret = true)
                MintButton("Open with passphrase", CompassIcons.KeyRound, Modifier.fillMaxWidth(), enabled = pass.length >= CompassRules.PASSPHRASE_MIN) {
                    act.unlock(pass.toCharArray(), false)
                    pass = ""
                }
            }
            QuietButton("Start a new Compass", CompassIcons.Compass, Modifier.fillMaxWidth()) { act.unlock(null, true) }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
private fun OpenCompass(ui: CompassUi, padding: PaddingValues, act: CompassActions, tab: Int, onTab: (Int) -> Unit, onWrite: (Draft) -> Unit, onRecovery: () -> Unit) {
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.headerToCard)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.fillMaxWidth().padding(start = Space.textEdge, end = Space.sm, top = Space.headerTop), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Only you can read this", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
                    Text("Compass", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                }
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = act.lock)
                        .semantics { contentDescription = "Lock now" },
                    contentAlignment = Alignment.Center,
                ) { Icon(CompassIcons.Lock, contentDescription = null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(22.dp)) }
            }
        }
        item(key = "tabs", contentType = "tabs") {
            Segmented(listOf("Path", "Season", "Year"), tab, "View", Modifier.padding(horizontal = Space.gutter)) { onTab(it) }
        }
        when (tab) {
            0 -> pathItems(ui, act, onWrite, onRecovery)
            1 -> seasonItems(ui, act, onWrite)
            else -> yearItems(ui, act, onWrite = { onTab(0); onWrite(it) })
        }
        item(key = "foot", contentType = "foot") { Spacer(Modifier.height(Space.xxl)) }
    }
}

private fun newDraft(ui: CompassUi, level: Level, parents: List<Goal>): Draft {
    val (starts, ends) = when (level) {
        Level.YEAR -> ui.yearStart.toString() to CompassRules.yearEnd(ui.yearStart).toString()
        Level.SEASON -> ui.season.starts.toString() to ui.season.ends.toString()
        else -> null to null
    }
    return Draft(Goal("", level, parents.firstOrNull()?.id, starts, ends, GoalBody()), parents)
}

private fun parentsFor(ui: CompassUi, level: Level): List<Goal> = when (level) {
    Level.PURPOSE -> emptyList()
    Level.SOMEDAY -> ui.opened.goals.filter { it.level in setOf(Level.PURPOSE, Level.TEN_YEAR, Level.YEAR) }
    Level.YEAR -> ui.at(Level.YEAR.parentLevel!!)
    Level.SEASON -> ui.opened.goals.filter { it.level == Level.YEAR && it.starts == ui.yearStart.toString() }
    else -> ui.at(level.parentLevel!!)
}

// ── Path ────────────────────────────────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.pathItems(ui: CompassUi, act: CompassActions, onWrite: (Draft) -> Unit, onRecovery: () -> Unit) {
    val purpose = ui.at(Level.PURPOSE).firstOrNull()
    val tens = ui.at(Level.TEN_YEAR)
    val years = ui.opened.goals.filter { it.level == Level.YEAR && it.starts == ui.yearStart.toString() }
    val seasons = ui.seasonGoals
    val byId = ui.opened.goals.associateBy { it.id }
    val edit = { g: Goal -> onWrite(Draft(g, parentsFor(ui, g.level))) }
    val stops = Modifier.padding(start = Space.sm, end = Space.gutter)

    item(key = "purpose", contentType = "stop") {
        PathStop(first = true, last = false, mark = StopMark.STAR, modifier = stops) {
            if (purpose == null) EmptySlot("Write why you live. Only you will ever read this.", "Write why you live") { onWrite(newDraft(ui, Level.PURPOSE, emptyList())) }
            else GoalCard(purpose, "Why I live", null, ui, onClick = { edit(purpose) })
        }
    }
    item(key = "ten-label", contentType = "label") { LevelLabel("10 years · a direction, not a map") }
    items(tens.size, key = { "ten-" + tens[it].id }, contentType = { "stop" }) { i ->
        PathStop(first = false, last = false, mark = StopMark.FULL, modifier = stops) { GoalCard(tens[i], "10 years", null, ui, onClick = { edit(tens[i]) }) }
    }
    item(key = "ten-add", contentType = "stop") {
        PathStop(first = false, last = false, mark = StopMark.RING, modifier = stops) {
            if (purpose == null) Hint("Write why you live first: every goal leads to it.", lines = 2)
            else EmptySlot("Add a 10-year direction", "Add a 10-year direction") { onWrite(newDraft(ui, Level.TEN_YEAR, parentsFor(ui, Level.TEN_YEAR))) }
        }
    }
    val yearTitle = CompassRules.yearTitle(ui.yearStart)
    item(key = "year-label", contentType = "label") { LevelLabel("$yearTitle · ${ui.yearStart.format(DAY)} → ${CompassRules.yearEnd(ui.yearStart).format(DAY)}") }
    items(years.size, key = { "year-" + years[it].id }, contentType = { "stop" }) { i ->
        PathStop(first = false, last = false, mark = StopMark.FULL, modifier = stops) { GoalCard(years[i], yearTitle, byId[years[i].parent], ui, onClick = { edit(years[i]) }) }
    }
    item(key = "year-add", contentType = "stop") {
        PathStop(first = false, last = false, mark = StopMark.RING, modifier = stops) {
            if (tens.isEmpty()) Hint("A year goal serves a 10-year direction: write one first.", lines = 2)
            else EmptySlot("Add a goal for $yearTitle", "Add a goal for $yearTitle") { onWrite(newDraft(ui, Level.YEAR, parentsFor(ui, Level.YEAR))) }
        }
    }
    item(key = "season-label", contentType = "label") { LevelLabel(seasonLine(ui)) }
    items(seasons.size, key = { "season-" + seasons[it].id }, contentType = { "stop" }) { i ->
        PathStop(first = false, last = false, mark = StopMark.FULL, modifier = stops) { GoalCard(seasons[i], "Season ${ui.season.n}", byId[seasons[i].parent], ui, onClick = { edit(seasons[i]) }) }
    }
    item(key = "season-add", contentType = "stop") {
        PathStop(first = false, last = true, mark = StopMark.RING, modifier = stops) {
            when {
                years.isEmpty() -> Hint("A season goal serves a goal for $yearTitle: write one first.", lines = 2)
                !CompassRules.canAdd(seasons.size) -> Hint("A season holds 3. Park more in Someday.", lines = 2)
                else -> EmptySlot("Slot ${seasons.size + 1} of ${CompassRules.SEASON_CAP} this season", "Add a goal for this season") {
                    onWrite(newDraft(ui, Level.SEASON, parentsFor(ui, Level.SEASON)))
                }
            }
        }
    }
    val someday = ui.at(Level.SOMEDAY)
    item(key = "someday-label", contentType = "label") { LevelLabel("Someday · no limit") }
    items(someday.size, key = { "someday-" + someday[it].id }, contentType = { "card" }) { i ->
        Box(Modifier.padding(horizontal = Space.gutter)) { GoalCard(someday[i], "Someday", byId[someday[i].parent], ui, onClick = { edit(someday[i]) }) }
    }
    item(key = "someday-add", contentType = "card") {
        Box(Modifier.padding(horizontal = Space.gutter)) {
            EmptySlot("Park a goal for someday", "Park a goal for someday") { onWrite(newDraft(ui, Level.SOMEDAY, parentsFor(ui, Level.SOMEDAY))) }
        }
    }
    if (ui.opened.unreadable > 0) item(key = "unreadable", contentType = "note") {
        Hint("${ui.opened.unreadable} locked items from another key stay closed.", modifier = Modifier.padding(horizontal = Space.textEdge))
    }
    item(key = "settings", contentType = "settings") { SettingsPane(ui, act, onRecovery) }
}

private fun seasonLine(ui: CompassUi): String {
    val s = ui.season
    // 12 working weeks, then the review week (compass checker: "week 3 of 12 · → 30 Jan" mixed 12 with the 13-week end).
    return if (ui.week > CompassRules.WEEKS) "Season ${s.n} · review week · to ${s.ends.format(DAY)}"
    else "Season ${s.n} · week ${ui.week} of ${CompassRules.WEEKS} · ${s.starts.format(DAY)} → ${s.workEnds.format(DAY)}, review to ${s.ends.format(DAY)}"
}

@Composable
private fun LevelLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(start = Space.textEdge + RailWidth, end = Space.gutter, top = Space.sm).semantics { heading() },
    )
}

/** A goal on the path: its level, its words, the goal it serves, and its state (a different door is not a failure). */
@Composable
private fun GoalCard(goal: Goal, level: String, parent: Goal?, ui: CompassUi, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val b = goal.body
    Pane(
        Modifier.fillMaxWidth().pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$level: ${b.title}" },
        radius = 20.dp,
    ) {
        Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(level, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                when (b.state) {
                    GoalBody.DONE -> MintPill("Done")
                    GoalBody.DOOR -> MintPill("A different door")
                }
            }
            Text(b.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
            if (b.why.isNotBlank()) Text(b.why, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (parent != null) Hint("Serves: " + CompassRules.firstWords(parent.body.title), modifier = Modifier.padding(top = Space.xs))
            val p = ui.progress[goal.id]
            if (p != null && (p.projects.isNotEmpty() || p.doneThisWeek > 0)) Counts(p, ui, Modifier.padding(top = Space.sm))
        }
    }
}

/** Plain counts from the linked projects and the lead-action habit: "12 tasks done this week · 3 habit days". */
@Composable
private fun Counts(p: GoalProgress, ui: CompassUi, modifier: Modifier = Modifier) {
    val weekStart = CompassRules.weekStart(ui.season, ui.week.coerceAtMost(CompassRules.WEEKS))
    val days = p.hitDays.count { it >= weekStart.toString() && it < weekStart.plusDays(7).toString() }
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("${p.doneThisWeek}", style = Glass.NumberText, color = Glass.PrimaryInk)
        Text(if (p.doneThisWeek == 1) "task done this week" else "tasks done this week", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        if (p.habitId != null) {
            Text("·", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            Text("$days", style = Glass.NumberText, color = Glass.PrimaryInk)
            Text(if (days == 1) "habit day" else "habit days", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        }
    }
}

/** Asks for the notification permission the first time a Compass reminder is turned on (never at launch). */
@Composable
private fun notifyAsker(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    return {
        val need = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (need) runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
private fun SettingsPane(ui: CompassUi, act: CompassActions, onRecovery: () -> Unit) {
    val candidates = ui.seasonGoals + ui.opened.goals.filter { it.level == Level.YEAR } + ui.at(Level.PURPOSE)
    val chosen = candidates.firstOrNull { it.id == ui.vault.lineGoal }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.sm)) {
            SettingRow(TabIcons.Today, "Show on Today", if (ui.vault.lineOn) "On" else "Off", onClick = { act.setLine(!ui.vault.lineOn, chosen ?: candidates.firstOrNull()) }) {
                CompassSwitch(ui.vault.lineOn)
            }
            if (ui.vault.lineOn && candidates.size > 1) {
                Column(Modifier.padding(start = Space.rowText, end = Space.lg, bottom = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    for (g in candidates) {
                        val on = g.id == ui.vault.lineGoal
                        val press = remember(g.id) { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = TouchMin).pressScale(press)
                                .clickable(press, indication = null, role = Role.RadioButton) { act.setLine(true, g) }
                                .semantics(mergeDescendants = true) { contentDescription = "Today shows: " + CompassRules.firstWords(g.body.title) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(CompassRules.firstWords(g.body.title), style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (on) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Hint(
                "One quiet line on Today, sealed on this phone only. Screenshots of Today are blocked while it shows.",
                modifier = Modifier.padding(start = Space.rowText, end = Space.lg, bottom = Space.sm), lines = 2,
            )
            val askNotify = notifyAsker()
            SettingRow(TabIcons.Bell, "Birthday morning", if (ui.vault.birthdayOn) "On" else "Off", onClick = {
                if (!ui.vault.birthdayOn) askNotify()
                act.setBirthday(!ui.vault.birthdayOn)
            }) { CompassSwitch(ui.vault.birthdayOn) }
            Hint("1 November, 08:00: your new year starts. The note never shows a goal.", modifier = Modifier.padding(start = Space.rowText, end = Space.lg, bottom = Space.sm), lines = 2)
            SettingRow(TabIcons.CalendarCheck, "Season review", if (ui.vault.reviewOn) "On" else "Off", onClick = {
                if (!ui.vault.reviewOn) askNotify()
                act.setReview(!ui.vault.reviewOn)
            }) { CompassSwitch(ui.vault.reviewOn) }
            Hint("Week 13, 09:00, only for a season with goals.", modifier = Modifier.padding(start = Space.rowText, end = Space.lg, bottom = Space.sm), lines = 2)
            SettingRow(CompassIcons.KeyRound, "Recovery passphrase", if (ui.vault.recovery != null) "On" else "Off", onClick = onRecovery) {
                Text(if (ui.vault.recovery != null) "On" else "Off", style = MaterialTheme.typography.bodyMedium, color = Glass.PrimaryInk, maxLines = 1)
            }
            Hint(
                "Off by default. Without it, a lost phone means the goals stay locked forever.",
                modifier = Modifier.padding(start = Space.rowText, end = Space.lg, bottom = Space.sm), lines = 2,
            )
        }
    }
}

// ── Season ──────────────────────────────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.seasonItems(ui: CompassUi, act: CompassActions, onWrite: (Draft) -> Unit) {
    val goals = ui.seasonGoals
    item(key = "season-head", contentType = "head") {
        Column(Modifier.padding(horizontal = Space.textEdge)) {
            Text("Season ${ui.season.n}", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Hint(seasonLine(ui).substringAfter(" · "))
        }
    }
    items(goals.size, key = { "sg-" + goals[it].id }, contentType = { "season-goal" }) { i ->
        val g = goals[i]
        val p = ui.progress[g.id] ?: GoalProgress()
        SeasonCard(g, CompassRules.score(ui.season, ui.today, g.body, p.hitDays), p, ui, act, onEdit = { onWrite(Draft(g, parentsFor(ui, Level.SEASON))) })
    }
    if (CompassRules.canAdd(goals.size)) item(key = "sg-add", contentType = "add") {
        Box(Modifier.padding(horizontal = Space.gutter)) {
            EmptySlot("Slot ${goals.size + 1} of ${CompassRules.SEASON_CAP} this season", "Add a goal for this season") {
                onWrite(newDraft(ui, Level.SEASON, parentsFor(ui, Level.SEASON)))
            }
        }
    }
    item(key = "week-pane", contentType = "week") { WeekPane(ui, act) }
}

/**
 * One of the season's 3: "my part" scored (a full mint state at 70 %, never a percentage), the week's lead action,
 * plain counts, the key results he ticks, and the outcome that is not in his hands (closes as a different door).
 */
@Composable
private fun SeasonCard(g: Goal, score: Score, p: GoalProgress, ui: CompassUi, act: CompassActions, onEdit: () -> Unit) {
    val b = g.body
    val win = score.win
    Box(Modifier.padding(horizontal = Space.gutter)) {
        // A win is the same white glass with a mint rim and the "✓ Win" pill, never a mint slab (compass checker design:
        // the slab read as "selected" and left "Not in my hands" at 4.50:1; on glass every line is ink or muted ≥ 4.5:1).
        Pane(Modifier.fillMaxWidth(), radius = 22.dp) {
            if (win) Box(Modifier.matchParentSize().border(2.dp, Glass.Primary.copy(alpha = 0.7f), RoundedCornerShape(22.dp)))
            Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onEdit)
                        .semantics(mergeDescendants = true) { contentDescription = "Season goal: ${b.title}" + if (win) ", a win" else "" },
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(b.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // The slot is kept whether or not it is a win (RULES.md Layout 1).
                    Box(Modifier.heightIn(min = Space.xxl).padding(start = Space.sm), contentAlignment = Alignment.Center) {
                        if (win) WinMark()
                    }
                }
                if (b.myPart.isNotBlank()) Text("My part: " + b.myPart, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink.copy(alpha = 0.86f), maxLines = 3)
                if (b.leadAction.isNotBlank()) WeekDots(ui, b, p)
                Counts(p, ui)
                b.keyResults.forEachIndexed { i, kr -> KeyResultRow(kr.text, kr.done) { act.tickKeyResult(g, i) } }
                if (b.outcome.isNotBlank()) {
                    Text("Not in my hands: " + b.outcome, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 3)
                    if (b.state == GoalBody.OPEN) QuietButton("Close as a different door", CompassIcons.Door, Modifier.fillMaxWidth()) { act.door(g) }
                }
                if (score.missedLastWeek && b.state == GoalBody.OPEN) {
                    Hint(if (score.missedTwice) "Two quiet weeks. Reset, then one small step." else "Never miss twice: this week counts.", color = Glass.Ink, lines = 1)
                    QuietButton("Reset, not restart", CompassIcons.Reset, Modifier.fillMaxWidth(), description = "Reset, not restart: ${CompassRules.firstWords(b.title)}") { act.reset(g) }
                }
            }
        }
    }
}

/** The full mint state of a win: a bright mint disc with an ink check (no number, no percentage). */
@Composable
private fun WinMark() {
    Row(
        Modifier.background(Color.White.copy(alpha = 0.85f), CircleShape).padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(TabIcons.Check, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(16.dp))
        Text("Win", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1)
    }
}

/** The 12 weeks of the lead action: done = mint, quiet = a soft grey ring (never red), set aside by a reset = faint. */
@Composable
private fun WeekDots(ui: CompassUi, b: GoalBody, p: GoalProgress) {
    val now = ui.week.coerceAtMost(CompassRules.WEEKS)
    Column {
        Text("Each week: " + b.leadAction, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2)
        Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            for (w in 1..CompassRules.WEEKS) {
                val start = CompassRules.weekStart(ui.season, w)
                val hit = (0 until 7).any { start.plusDays(it.toLong()).toString() in p.hitDays }
                // Weeks before the goal was written, or set aside by a reset, never count: faint, never grey.
                val excused = start.toString() in b.excused || w < CompassRules.firstWeek(ui.season, b)
                val fill = when {
                    hit -> Glass.Primary
                    w > now || excused -> Color.White.copy(alpha = 0.5f)
                    else -> Glass.Ink.copy(alpha = 0.12f)
                }
                Box(
                    Modifier.weight(1f).height(Space.sm).background(fill, CircleShape)
                        .border(if (w == now) 1.5.dp else 0.dp, Glass.Ink.copy(alpha = if (w == now) 0.55f else 0f), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun KeyResultRow(text: String, done: Boolean, onTick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchMin).pressScale(press)
            .clickable(press, indication = null, role = Role.Checkbox, onClick = onTick)
            .semantics(mergeDescendants = true) { contentDescription = (if (done) "Done: " else "Not yet: ") + text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Space.xxl).background(if (done) Glass.Primary else Color.White.copy(alpha = 0.8f), CircleShape)
                .border(1.5.dp, if (done) Glass.Primary else Glass.Ink.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(Space.md))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The weekly review asks only two things (Boss's failure pattern): what got in the way, and the one smaller step. */
@Composable
private fun WeekPane(ui: CompassUi, act: CompassActions) {
    val now = ui.week.coerceAtMost(CompassRules.WEEKS)
    val start = CompassRules.weekStart(ui.season, now).toString()
    val review = ui.week > CompassRules.WEEKS
    val kind = if (review) NoteKind.SEASON_REVIEW else NoteKind.WEEK
    val old = ui.opened.notes.firstOrNull { it.kind == kind && it.day == start }
    var a by remember(old?.id) { mutableStateOf(old?.body?.answers?.getOrNull(0).orEmpty()) }
    var b by remember(old?.id) { mutableStateOf(old?.body?.answers?.getOrNull(1).orEmpty()) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text(if (review) "Season review" else "This week", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            SealedField(a, { a = it }, label = if (review) "What this season taught me" else "What got in the way", hint = "In your words", lines = 2)
            SealedField(b, { b = it }, label = if (review) "What the next season keeps" else "The one smaller step", hint = "One small step", lines = 2)
            MintButton(if (old == null) "Save" else "Save again", TabIcons.Check, Modifier.fillMaxWidth(), enabled = a.isNotBlank() || b.isNotBlank()) {
                act.note(kind, start, listOf(a, b), old?.id ?: "")
            }
        }
    }
}

// ── Year: the birthday age report ───────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.yearItems(ui: CompassUi, act: CompassActions, onWrite: (Draft) -> Unit) {
    val end = ui.reportEnd
    val title = CompassRules.reportTitle(end)
    item(key = "report-head", contentType = "head") {
        Column(Modifier.padding(horizontal = Space.textEdge)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Hint("${CompassRules.yearStart(end).format(DAY)} ${CompassRules.yearStart(end).year} → ${end.format(DAY)} ${end.year} · numbers PAGEL has", lines = 2)
        }
    }
    item(key = "report-numbers", contentType = "numbers") {
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                NumberTile(ui.report.tasksDone, "tasks done", Modifier.weight(1f))
                NumberTile(ui.report.habitDays, "habit days", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                NumberTile(ui.report.bestRun, "days in a row, best", Modifier.weight(1f))
                // Namaz is counted from 1 Nov 2026: the first report says when it starts, never a guilty 0 (checker).
                if (end.isBefore(java.time.LocalDate.parse(CompassRepository.NAMAZ_FROM))) WordTile(namazStart(ui.today), "namaz", Modifier.weight(1f))
                else NumberTile(ui.report.namazDays, "namaz days", Modifier.weight(1f))
            }
        }
    }
    item(key = "report-words", contentType = "words") { ReportPane(ui, act, end) }
    val next = CompassRules.yearTitle(end.plusDays(1))
    item(key = "report-next", contentType = "next") {
        Box(Modifier.padding(horizontal = Space.gutter)) {
            MintButton("Write $next's goals", CompassIcons.Compass, Modifier.fillMaxWidth()) {
                val parents = parentsFor(ui, Level.YEAR)
                val start = end.plusDays(1)
                onWrite(Draft(Goal("", Level.YEAR, parents.firstOrNull()?.id, start.toString(), CompassRules.yearEnd(start).toString(), GoalBody()), parents))
            }
        }
    }
}

/** "starts today" on 1 Nov, "starts 1 Nov" before it, "started 1 Nov" after. */
private fun namazStart(today: java.time.LocalDate): String {
    val from = java.time.LocalDate.parse(CompassRepository.NAMAZ_FROM)
    return when {
        today == from -> "starts today"
        today.isBefore(from) -> "starts 1 Nov"
        else -> "started 1 Nov"
    }
}

/** A tile whose value is words (the first report's namaz tile): the heading face, never Departure Mono. */
@Composable
private fun WordTile(words: String, label: String, modifier: Modifier = Modifier) {
    Pane(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $words" }, radius = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md)) {
            Text(words, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, maxLines = 2)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun NumberTile(n: Int, label: String, modifier: Modifier = Modifier) {
    Pane(modifier.semantics(mergeDescendants = true) { contentDescription = "$n $label" }, radius = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md)) {
            Text("$n", style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize, lineHeight = MaterialTheme.typography.headlineLarge.lineHeight), color = Glass.Ink, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2)
        }
    }
}

@Composable
private fun ReportPane(ui: CompassUi, act: CompassActions, end: java.time.LocalDate) {
    val day = end.plusDays(1).toString()
    val old = ui.opened.notes.firstOrNull { it.kind == NoteKind.AGE_REPORT && it.day == day }
    var words by remember(old?.id) { mutableStateOf(old?.body?.answers?.firstOrNull().orEmpty()) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("In my words", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            SealedField(words, { words = it }, label = "This year, in my words", hint = "What this year was, what it gave, what it taught", lines = 4, maxChars = 4000)
            MintButton(if (old == null) "Save" else "Save again", TabIcons.Check, Modifier.fillMaxWidth(), enabled = words.isNotBlank()) {
                act.note(NoteKind.AGE_REPORT, day, listOf(words), old?.id ?: "")
            }
        }
    }
}
