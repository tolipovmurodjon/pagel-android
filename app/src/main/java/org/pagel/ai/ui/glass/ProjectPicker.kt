package org.pagel.ai.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import org.pagel.ai.ui.projects.dropIndex
import org.pagel.ai.ui.projects.shortName
import javax.inject.Inject

/** The project every task without one belongs to (pagel.uz files it there). */
internal const val NO_PROJECT = "inbox"

/** One line of the picker, top to bottom. */
sealed interface PickerRow {
    /** A project touched lately (shown above the list once there are more than [RECENT_AFTER]). */
    data class Recent(val p: ProjectEntity) : PickerRow
    data class Item(val p: ProjectEntity) : PickerRow
    /** "Create “Gym”": what Enter makes when no project is called that. */
    data class Create(val title: String) : PickerRow
    /** "Restore “Gym”": the name typed is an archived project's; it comes back instead of a twin being made. */
    data class Restore(val p: ProjectEntity) : PickerRow
    data object NoProject : PickerRow
}

private const val RECENT_AFTER = 8

private fun ProjectEntity.words() = shortName(name, title)

private fun ProjectEntity.called(q: String) = words().equals(q, ignoreCase = true) || title.trim().equals(q, ignoreCase = true)

/** A row's colour for TalkBack (and the walkthrough): the swatch's name, or "custom" for a hue picked by hand. */
internal fun colourWord(p: ProjectEntity): String = TileColors.nameFor(p.name, p.color).let { if (Regex("""h\d+""").matches(it)) "custom colour" else it }

/**
 * What the picker lists for [query]: Recent first (only with more than 8 projects and nothing typed), then the projects
 * that match, then "Create “query”" unless one is already called that ("Restore “query”" when an archived one is), and
 * "No project" last (nothing typed).
 */
fun pickerRows(projects: List<ProjectEntity>, query: String, recent: List<String>, canCreate: Boolean): List<PickerRow> {
    val q = query.trim()
    val all = projects.filter { it.name != NO_PROJECT && !it.archived && !it.hidden }
    val rows = mutableListOf<PickerRow>()
    if (q.isEmpty() && all.size > RECENT_AFTER) recent.mapNotNull { r -> all.firstOrNull { it.name == r } }.take(3).forEach { rows += PickerRow.Recent(it) }
    all.filter { it.words().contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }.forEach { rows += PickerRow.Item(it) }
    if (canCreate && q.isNotEmpty() && all.none { it.called(q) }) {
        val archived = projects.firstOrNull { it.archived && !it.hidden && it.name != NO_PROJECT && it.called(q) }
        rows += if (archived != null) PickerRow.Restore(archived) else PickerRow.Create(q)
    }
    if (q.isEmpty()) rows += PickerRow.NoProject
    return rows
}

/** What the project picker may do besides choosing: make a project, move one. Null → the old read-only picker. */
data class ProjectPickerActions(
    val recent: List<String> = emptyList(),
    /** Makes the project (or finds the one with that name) and gives its name; [undone] runs when its Undo is tapped. */
    val create: suspend (title: String, undone: () -> Unit) -> String,
    /** One drag: [name] now sits after [after] (or before [before] when it leads). */
    val move: (name: String, after: String?, before: String?) -> Unit,
    /** Brings an archived project back (it is then picked); [undone] runs when its Undo is tapped. */
    val restore: suspend (name: String, undone: () -> Unit) -> Unit = { _, _ -> },
)

@HiltViewModel
class ProjectPickerViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val toasts: ToastCenter,
    @AppScope private val scope: CoroutineScope,
) : ViewModel() {
    val recent: StateFlow<List<String>> = repo.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun create(title: String, undone: () -> Unit): String {
        repo.existing(title)?.let { p ->
            if (p.archived) restore(p.name, undone) else toasts.show("Using “${p.words()}”")
            return p.name
        }
        val made = repo.createUndoable(title.trim())
        toasts.show("“${title.trim()}” created", action = "Undo", millis = 5_000) { scope.launch { repo.undoCreate(made) }; undone() }
        return made.name
    }

    /** An archived project back in the list, said out loud with Undo (never a silent pick of a hidden one). */
    suspend fun restore(name: String, undone: () -> Unit) {
        val p = repo.byName(name) ?: return
        repo.archive(name, false)
        toasts.show("“${p.words()}” restored", action = "Undo", millis = 5_000) { scope.launch { repo.archive(name, true) }; undone() }
    }

    fun move(name: String, after: String?, before: String?) { scope.launch { repo.place(name, after, before) } }
}

@Composable
fun rememberProjectPickerActions(vm: ProjectPickerViewModel = hiltViewModel()): ProjectPickerActions {
    val recent by vm.recent.collectAsStateWithLifecycle()
    return remember(recent, vm) { ProjectPickerActions(recent, vm::create, vm::move, vm::restore) }
}

/**
 * The one "make a project" field (the picker and the Projects tab; the Inbox joins after its round): a mint plus, the name
 * as you type, Enter creates. Empty Enter closes it. It has the height of a picker row, so opening it moves nothing.
 */
@Composable
fun ProjectCreateField(onCreate: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(modifier.fillMaxWidth().heightIn(min = Space.row).padding(horizontal = Space.md), verticalAlignment = Alignment.CenterVertically) {
        PlusTile()
        Spacer(Modifier.width(Space.md))
        BasicTextField(
            // pagel.uz takes a name of 1-60 letters (projects-api.ts).
            value = text, onValueChange = { text = it.replace('\n', ' ').take(60) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink), cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text.isBlank()) onCancel() else onCreate(text.trim()) }),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "New project name" },
            decorationBox = { inner -> Box { if (text.isEmpty()) Text("Name the project", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
        )
    }
}

@Composable
private fun PlusTile(icon: ImageVector = TabIcons.Plus) {
    Box(Modifier.size(Space.xxxl - Space.xs).background(Glass.Primary.copy(alpha = 0.14f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(Space.lg))
    }
}

@Composable
private fun TextRow(label: String, description: String, icon: ImageVector = TabIcons.Plus, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlusTile(icon)
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.PrimaryInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SmallHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Glass.Muted,
        modifier = Modifier.padding(start = Space.md, top = Space.sm, bottom = Space.xs).semantics { heading() })
}

/**
 * The project picker: type to find one, "+ New project" first, every project as its tile with a check on the chosen one,
 * a ⋯ to edit it, a long press to move it. [picker] null → the old read-only list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectContent(projects: List<ProjectEntity>, value: String, picker: ProjectPickerActions? = null, onEdit: ((String) -> Unit)? = null, onPick: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    val rows = remember(projects, q, picker?.recent) { pickerRows(projects, q, picker?.recent.orEmpty(), picker != null) }
    val items = remember(rows) { rows.filterIsInstance<PickerRow.Item>().map { it.p } }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableIntStateOf(-1) }
    var slot by remember { mutableIntStateOf(-1) }
    var dy by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }

    fun make(title: String) {
        scope.launch {
            val name = picker!!.create(title) { onPick(value) }
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onPick(name)
        }
    }

    fun restore(p: ProjectEntity) {
        scope.launch {
            picker!!.restore(p.name) { onPick(value) }
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onPick(p.name)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // While the keyboard is up the title steps aside, so a 640 dp phone keeps 5 rows in view, not 3 (checker #10).
        AnimatedVisibility(!WindowInsets.isImeVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { SheetTitle("Project") }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg).height(Space.touch)
                .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(24.dp))
                .border(1.dp, SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(24.dp))
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TabIcons.Search, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.md))
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink), cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val first = rows.firstOrNull { it is PickerRow.Item || it is PickerRow.Create || it is PickerRow.Restore }
                    when (first) { is PickerRow.Item -> onPick(first.p.name); is PickerRow.Create -> make(first.title); is PickerRow.Restore -> restore(first.p); else -> {} }
                }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Find a project" },
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Type a project name", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
            )
        }
        Spacer(Modifier.height(Space.md))
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
            if (picker == null && rows.none { it is PickerRow.Item }) {
                Text("No project called “${q.trim()}”", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
            } else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(vertical = Space.xs)) {
                // With a name typed, the one way to make it is its own "Create “X”" row (checker #8).
                if (picker != null) {
                    if (creating) ProjectCreateField(onCreate = { creating = false; make(it) }, onCancel = { creating = false })
                    else if (q.isBlank()) TextRow("New project", "New project") { creating = true }
                }
                val dragOk = picker != null && q.isEmpty() && onEdit != null
                var itemIndex = -1
                rows.forEach { row ->
                    when (row) {
                        is PickerRow.Recent -> {
                            if (rows.first() == row) SmallHeading("Recent")
                            ProjectLine(row.p, row.p.name == value, { onPick(row.p.name) }, null)
                            if (rows.getOrNull(rows.indexOf(row) + 1) !is PickerRow.Recent) SmallHeading("All projects")
                        }
                        is PickerRow.Item -> {
                            val i = ++itemIndex
                            val lifted = from == i
                            val shift = if (from >= 0 && !lifted && slot >= 0) {
                                val h = (heights[from] ?: 0).toFloat()
                                when { i in (from + 1)..slot -> -h; i in slot until from -> h; else -> 0f }
                            } else 0f
                            val drag = if (dragOk) Modifier.pointerInput(row.p.name, items) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { from = i; slot = i; dy = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDrag = { c, d ->
                                        c.consume(); dy += d.y
                                        val s = dropIndex(items.indices.map { heights[it] ?: 0 }, i, dy)
                                        if (s != slot) { slot = s; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                                    },
                                    onDragEnd = {
                                        val f = from; val t = slot
                                        from = -1; slot = -1; dy = 0f
                                        if (f >= 0 && t >= 0 && t != f) {
                                            val after = items.toMutableList().apply { add(t, removeAt(f)) }
                                            picker?.move(items[f].name, after.getOrNull(t - 1)?.name, after.getOrNull(t + 1)?.name)
                                        }
                                    },
                                    onDragCancel = { from = -1; slot = -1; dy = 0f },
                                )
                            } else Modifier
                            ProjectLine(
                                row.p, row.p.name == value, { onPick(row.p.name) }, onEdit?.let { e -> { e(row.p.name) } },
                                Modifier.onSizeChanged { heights[i] = it.height }
                                    .graphicsLayer {
                                        translationY = if (lifted) dy else shift
                                        if (lifted) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 16.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true }
                                    }
                                    .then(if (lifted) Modifier.background(Color.White.copy(alpha = 0.92f)) else Modifier)
                                    .then(drag)
                                    .semantics { stateDescription = if (lifted) "Lifted" else "" },
                            )
                        }
                        is PickerRow.Create -> TextRow("Create “${row.title}”", "Create ${row.title}") { make(row.title) }
                        is PickerRow.Restore -> TextRow("Restore “${row.p.words()}”", "Restore ${row.p.words()}", TabIcons.Undo) { restore(row.p) }
                        PickerRow.NoProject -> NoProjectLine(value == NO_PROJECT) { onPick(NO_PROJECT) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectLine(p: ProjectEntity, chosen: Boolean, onPick: () -> Unit, onEdit: (() -> Unit)?, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick() }
            // Name and colour, so TalkBack (and the walkthrough) hear a recolour from the ⋯ at once.
            .semantics { selected = chosen; contentDescription = "${p.words()}, ${colourWord(p)}" }
            .padding(start = Space.md, end = if (onEdit == null) Space.md else Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectTile(p.name, p.color, p.title, size = Space.xxxl - Space.xs, icon = p.icon)
        Spacer(Modifier.width(Space.md))
        Text(p.words(), style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (chosen) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
        if (onEdit != null) GlassIconButton(TabIcons.More, "Edit ${p.words()}", plain = true, onClick = onEdit)
    }
}

@Composable
private fun NoProjectLine(chosen: Boolean, onPick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onPick)
            .semantics { selected = chosen; contentDescription = "No project" }
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Space.xxxl - Space.xs).background(Glass.Ink.copy(alpha = 0.06f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(TabIcons.Inbox, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(Space.lg))
        }
        Spacer(Modifier.width(Space.md))
        Text("No project", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.weight(1f))
        if (chosen) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
    }
}

/** The picker in its own glass sheet; with [picker] it can make, move and (⋯) edit projects, calmly: no delete or merge here. */
@Composable
fun ProjectSheet(projects: List<ProjectEntity>, value: String, onPick: (String) -> Unit, onDismiss: () -> Unit, picker: ProjectPickerActions? = null) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss) {
        ProjectContent(projects, value, picker, onEdit = if (picker != null) { n -> editing = n } else null) { onPick(it); onDismiss() }
        Spacer(Modifier.height(SheetBottom))
    }
    editing?.let { org.pagel.ai.ui.projects.ProjectSettingsRoute(it, onDismiss = { editing = null }, onGone = { editing = null }, calm = true) }
}
