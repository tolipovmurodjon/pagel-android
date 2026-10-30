package org.pagel.ai.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.projects.ProjectRepository
import org.pagel.ai.di.AppScope
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.InlineField
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.pressScale
import javax.inject.Inject
import kotlin.math.roundToInt

private val Hairline = 1.dp
private val FieldShape = RoundedCornerShape(14.dp)
private val SmallIcon = 14.dp

@HiltViewModel
class ProjectSettingsViewModel @Inject constructor(val repo: ProjectRepository, @AppScope private val scope: CoroutineScope) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = repo.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Merge and delete close this page, which clears this ViewModel: an Undo tapped seconds later must still run
    // (on viewModelScope it was cancelled, and the held merge reached pagel.uz; CI 40960659809).
    fun run(block: suspend ProjectRepository.() -> Unit) { scope.launch { repo.block() } }
}

/** What the settings sheet can change; the sheet itself holds no data logic (tests drive it with fakes). */
data class SettingsActions(
    val onRename: (String) -> Unit = {},
    val onColor: (String) -> Unit = {},
    val onIcon: (String?) -> Unit = {},
    val onArea: (String?) -> Unit = {},
    val onParent: (String?) -> Unit = {},
    val onMerge: (into: String) -> Unit = {},
    val onArchive: (Boolean) -> Unit = {},
    val onDelete: (dropTasks: Boolean) -> Unit = {},
)

@Composable
fun ProjectSettingsRoute(project: String, onDismiss: () -> Unit, onGone: () -> Unit, onOpenProject: (String) -> Unit = {}, calm: Boolean = false, vm: ProjectSettingsViewModel = hiltViewModel()) {
    val all by vm.projects.collectAsStateWithLifecycle()
    val p = all.firstOrNull { it.name == project } ?: return
    val toasts = LocalToasts.current
    val name = shortName(p.name, p.title)
    GlassSheet(onDismiss, full = true) {
        ProjectSettingsContent(
            p, all, onDone = onDismiss, calm = calm, goal = { org.pagel.ai.ui.compass.ServesGoalRow(project) },
            actions = SettingsActions(
                onRename = { t -> vm.run { rename(project, t) } },
                onColor = { c -> vm.run { color(project, c) } },
                onIcon = { i -> vm.run { icon(project, i) } },
                onArea = { a -> vm.run { area(project, a) } },
                onParent = { pa -> vm.run { parent(project, pa) } },
                onMerge = { into ->
                    vm.run {
                        val op = merge(project, into)
                        toasts.show("$name merged", action = "Undo", millis = 5_000) { vm.run { undoMerge(project, into, op) } }
                    }
                    onGone(); onOpenProject(into)
                },
                onArchive = { on -> vm.run { archive(project, on) }; if (on) { toasts.show("$name archived", action = "Undo", millis = 5_000) { vm.run { archive(project, false) } }; onGone() } },
                onDelete = { drop ->
                    vm.run {
                        val op = delete(project, drop)
                        toasts.show("$name deleted", action = "Undo", millis = 5_000) { vm.run { undoDelete(project, op) } }
                    }
                    onGone()
                },
            ),
        )
    }
}

/**
 * Project settings, all in one place (Boss 2026-10-29: "editing, removing… many more settings"): name, colour with a
 * live preview of the tile, a task row and a calendar block, icon (line icons + emoji, with search), area, sub-project,
 * the goal it serves, merge, archive, delete. Every change saves at once (Room first, outbox after).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectSettingsContent(p: ProjectEntity, all: List<ProjectEntity>, onDone: () -> Unit, actions: SettingsActions, goal: (@Composable () -> Unit)? = null, calm: Boolean = false) {
    var color by remember(p.name) { mutableStateOf(TileColors.nameFor(p.name, p.color)) }
    var icon by remember(p.name) { mutableStateOf(p.icon) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xxxl)) {
        SheetTitle("Project settings", onDone = onDone)
        Section("Name") {
            InlineField(p.title, "Project name", "Project name", MaterialTheme.typography.titleLarge, onSave = actions.onRename)
        }
        Section("Colour") {
            ColourPreview(p, color, icon)
            Spacer(Modifier.height(Space.md))
            ColourPicker(color) { color = it; actions.onColor(it) }
        }
        Section("Icon") { IconPicker(icon) { icon = it; actions.onIcon(it) } }
        Section("Area") {
            val areas = (all.mapNotNull { it.area } + listOf("Health", "Mind", "Faith", "Work", "Money", "People", "Games")).distinct()
            ChoiceChips(listOf<String?>(null) + areas, p.area, label = { it ?: "None" }, onPick = actions.onArea)
            NewWordField("New area") { actions.onArea(it) }
        }
        val parents = all.filter { it.name != p.name && it.parent == null && !it.archived }
        val hasChildren = all.any { it.parent == p.name }
        Section("Inside project") {
            if (hasChildren) Text("It holds sub-projects, so it stays on top.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            else ChoiceChips(listOf<String?>(null) + parents.map { it.name }, p.parent, label = { n -> n?.let { all.firstOrNull { x -> x.name == it }?.let { x -> shortName(x.name, x.title) } } ?: "None" }, onPick = actions.onParent)
        }
        if (goal != null) Section("Serves goal") { goal() }
        // Opened from the picker (calm): the place to shape one project, not to remove or fold it away.
        if (!calm) Section("Merge") {
            var open by rememberSaveable { mutableStateOf(false) }
            var into by rememberSaveable { mutableStateOf<String?>(null) }
            ActionRow(TabIcons.Shuffle, "Merge into another project…", "Merge into another project") { open = !open }
            if (open) {
                ChoiceChips(all.filter { it.name != p.name && !it.archived }.map { it.name }, into, label = { n -> all.firstOrNull { it.name == n }?.let { shortName(it.name, it.title) } ?: n.orEmpty() },
                    describe = { n -> "Into " + (all.firstOrNull { it.name == n }?.let { shortName(it.name, it.title) } ?: n.orEmpty()) }) { into = it }
                into?.let { target ->
                    val t = all.firstOrNull { it.name == target }?.let { shortName(it.name, it.title) } ?: target
                    Text("Every task of ${shortName(p.name, p.title)} moves into $t. You can undo it.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                        modifier = Modifier.padding(vertical = Space.sm))
                    BigButton("Merge into $t", TabIcons.Shuffle) { actions.onMerge(target) }
                }
            }
        }
        if (!calm) Section("Keep or remove") {
            ActionRow(TabIcons.Briefcase, if (p.archived) "Restore" else "Archive", if (p.archived) "Restore the project" else "Archive the project") { actions.onArchive(!p.archived) }
            var deleting by rememberSaveable { mutableStateOf(false) }
            ActionRow(TabIcons.Trash, "Delete…", "Delete the project", tint = Glass.Risk) { deleting = !deleting }
            if (deleting) {
                ActionRow(TabIcons.Inbox, "Delete · its tasks go to Inbox", "Delete, its tasks go to Inbox", tint = Glass.Risk) { actions.onDelete(false) }
                ActionRow(TabIcons.Trash, "Delete with its tasks", "Delete with its tasks", tint = Glass.Risk) { actions.onDelete(true) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.sm)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Glass.Muted, modifier = Modifier.padding(bottom = Space.xs))
        content()
    }
    HorizontalDivider(Modifier.padding(horizontal = Space.xl), thickness = Hairline, color = Glass.Ink.copy(alpha = 0.07f))
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, description: String, tint: Color = Glass.Ink, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(items: List<T>, chosen: T, label: (T) -> String, describe: ((T) -> String)? = null, onPick: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        items.forEach { v ->
            val on = v == chosen
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.72f))
                    .border(1.dp, if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).pressScale(press)
                    .clickable(press, indication = null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(v) }
                    .semantics { selected = on; describe?.let { contentDescription = it(v) } }.padding(horizontal = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) { Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(SmallIcon)); Spacer(Modifier.width(Space.xs)) }
                Text(label(v), style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NewWordField(placeholder: String, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val style = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink)
    BasicTextField(
        text, { text = it.take(40) }, Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(top = Space.md).semantics { contentDescription = placeholder },
        textStyle = style, singleLine = true, cursorBrush = SolidColor(Glass.Primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }),
        decorationBox = { inner -> Box { if (text.isEmpty()) Text("+ $placeholder", style = style, color = Glass.Muted); inner() } },
    )
}

/** The tile, a task row and a calendar block in the chosen colour, so the change is seen before it is kept. */
@Composable
internal fun ColourPreview(p: ProjectEntity, color: String, icon: String?) {
    val (a, b) = TileColors.stops(p.name, color)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Preview" }, verticalAlignment = Alignment.CenterVertically) {
        ProjectTile(p.name, color, p.title, size = 48.dp, icon = icon)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectTile(p.name, color, p.title, size = 16.dp, icon = icon)
                Spacer(Modifier.width(Space.sm))
                Text("Next step of ${shortName(p.name, p.title)}", style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Space.sm))
            // A calendar block: one soft tint of the colour, no stripe (Boss 11:30), title in ink, time small.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(a.copy(alpha = 0.28f)).border(1.dp, b.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = Space.md, vertical = Space.sm)) {
                Text(shortName(p.name, p.title), style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1)
                Text("10:00 – 11:00", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
        }
    }
}

/** The ten swatches and a hue track (any hue → the same two-stop OKLCH recipe, "h<deg>"); the chosen one wears a ✓. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColourPicker(chosen: String, onPick: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        TileColors.SWATCH_NAMES.forEach { name ->
            val (a, b) = TileColors.stops("", name)
            val on = name == chosen
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier.size(Space.touch).pressScale(press).clickable(press, indication = null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(name) }
                    .semantics { contentDescription = "Colour $name"; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))).then(if (on) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center) {
                    if (on) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(Space.md))
    val hue = TileColors.hueOf(chosen)
    var width by remember { mutableStateOf(1) }
    var last by remember { mutableStateOf(-1) }
    fun pick(x: Float) {
        val h = ((x / width.coerceAtLeast(1)) * 359f).roundToInt().coerceIn(0, 359)
        if (h / 10 != last / 10) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        last = h
        onPick("h$h")
    }
    val stops = remember { (0..12).map { TileColors.oklch(0.7, 0.14, it * 30.0) } }
    Box(
        Modifier.fillMaxWidth().height(Space.touch).onSizeChanged { width = it.width }
            .pointerInput(Unit) { detectTapGestures { pick(it.x) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); pick(change.position.x) } }
            .semantics { contentDescription = "Any colour: slide along the hues" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(stops)))
        if (hue != null) {
            val px = with(LocalDensity.current) { (hue / 359f * width).roundToInt() - 14.dp.roundToPx() }
            Box(Modifier.offset { IntOffset(px, 0) }.size(28.dp).clip(CircleShape).background(TileColors.stops("", chosen).first).border(3.dp, Color.White, CircleShape))
        }
    }
}

/** Fluent-style emoji with words to search them by (drawn by the phone's emoji font). */
private val EMOJI = listOf(
    "🚀" to "rocket launch space", "🎮" to "game play", "📚" to "book read study", "💪" to "gym strength health", "🧠" to "brain mind learn",
    "🕌" to "mosque faith", "💰" to "money finance", "❤️" to "heart love people", "🎓" to "school university study", "🛠️" to "tools build fix",
    "✈️" to "plane drone fly", "🤖" to "robot ai bot", "🎨" to "art design paint", "🎬" to "film video motion", "🎵" to "music sound",
    "🏃" to "run sport", "🍎" to "food health eat", "🏠" to "home house", "🌱" to "grow plant start", "⭐" to "star goal",
    "🔥" to "fire hot streak", "📈" to "chart grow numbers", "🧪" to "lab test science", "🗺️" to "map travel", "📝" to "note write",
    "💡" to "idea light", "🎯" to "target goal focus", "🧩" to "puzzle game", "📱" to "phone app mobile", "💻" to "laptop code work",
    "🌍" to "world earth", "🏆" to "trophy win", "⏰" to "clock time", "🧘" to "calm yoga mind", "👥" to "people team", "🕋" to "kaaba faith",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconPicker(chosen: String?, onPick: (String?) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val style = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink)
    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(FieldShape).background(Glass.Ink.copy(alpha = 0.05f)).padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(TabIcons.Search, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.sm))
        BasicTextField(q, { q = it.take(24) }, Modifier.weight(1f).semantics { contentDescription = "Search icons" }, textStyle = style, singleLine = true,
            cursorBrush = SolidColor(Glass.Primary), decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search icons", style = style, color = Glass.Muted); inner() } })
    }
    Spacer(Modifier.height(Space.md))
    val term = q.trim().lowercase()
    val lines = TabIcons.pickable.filterKeys { term.isEmpty() || term in it }
    val emoji = EMOJI.filter { term.isEmpty() || term in it.second }
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        IconCell("Default icon", chosen == null, { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(null) }) {
            Text("Aa", style = MaterialTheme.typography.labelLarge, color = Glass.Muted)
        }
        lines.forEach { (name, v) ->
            IconCell("Icon $name", chosen == "lucide:$name", { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick("lucide:$name") }) {
                Icon(v, contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(22.dp))
            }
        }
        emoji.forEach { (e, words) ->
            IconCell("Emoji ${words.substringBefore(' ')}", chosen == "emoji:$e", { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick("emoji:$e") }) {
                Text(e, fontSize = 22.sp)
            }
        }
    }
    if (lines.isEmpty() && emoji.isEmpty()) Text("No icon for “$q”.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
}

@Composable
private fun IconCell(description: String, on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(Space.touch).clip(RoundedCornerShape(12.dp)).background(if (on) Glass.PrimarySoft else Color.Transparent)
            .then(if (on) Modifier.border(1.5.dp, Glass.Primary, RoundedCornerShape(12.dp)) else Modifier)
            .pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description; selected = on },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The finish moment: confetti, one line of what was shipped (saved to the project), then Done projects. */
@Composable
fun FinishProjectSheet(ui: ProjectUi, onDismiss: () -> Unit, onFinish: (String) -> Unit) {
    GlassSheet(onDismiss) { FinishProjectContent(ui, onDismiss, onFinish) }
}

/** The finish sheet's body on its own, so a screenshot test can show it without the sheet's window. */
@Composable
fun FinishProjectContent(ui: ProjectUi, onDismiss: () -> Unit, onFinish: (String) -> Unit, confetti: Boolean = true) {
    var shipped by rememberSaveable { mutableStateOf("") }
    var burst by remember { mutableStateOf(if (confetti) org.pagel.ai.ui.today.Burst(1L, androidx.compose.ui.geometry.Offset(420f, 140f), power = 2) else null) }
    run {
        Box {
            Column(Modifier.padding(bottom = Space.xxxl)) {
                SheetTitle("Finish ${ui.title}", onDone = onDismiss)
                Column(Modifier.padding(horizontal = Space.xl)) {
                Text(finishSummary(ui.done, ui.days), style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                Text("What did you ship?", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                Spacer(Modifier.height(Space.md))
                val style = MaterialTheme.typography.titleMedium.copy(color = Glass.Ink)
                BasicTextField(
                    shipped, { shipped = it.take(140) },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(FieldShape).background(Glass.Ink.copy(alpha = 0.05f)).padding(Space.md)
                        .semantics { contentDescription = "What I shipped" },
                    textStyle = style, singleLine = true, cursorBrush = SolidColor(Glass.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onFinish(shipped) }),
                    decorationBox = { inner -> Box { if (shipped.isEmpty()) Text("I shipped…", style = style, color = Glass.Muted); inner() } },
                )
                Spacer(Modifier.height(Space.lg))
                BigButton("Finish project", TabIcons.Flag) { onFinish(shipped) }
                }
            }
            burst?.let { b -> org.pagel.ai.ui.today.ConfettiBurst(b, onEnd = { burst = null }) }
        }
    }
}

/** The finish moment's summary: "8 tasks done over 12 days." (one day reads "in 1 day"; no days known, just the count). */
internal fun finishSummary(done: Int, days: Int?): String {
    val tasks = "$done ${if (done == 1) "task" else "tasks"} done"
    return when (days) {
        null -> "$tasks."
        1 -> "$tasks in 1 day."
        else -> "$tasks over $days days."
    }
}
