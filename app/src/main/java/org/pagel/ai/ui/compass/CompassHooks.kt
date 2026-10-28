package org.pagel.ai.ui.compass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.compass.CompassRepository
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.rituals.Hint
import org.pagel.ai.ui.rituals.MintButton
import javax.inject.Inject

// ── Today's quiet line (off by default; More → Compass → Show on Today) ────────────────────────────────────────────

@HiltViewModel
class CompassLineViewModel @Inject constructor(repo: CompassRepository) : ViewModel() {
    val line: StateFlow<String?> = repo.line.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * One quiet line on top of Today: his chosen goal's first words, to remember why. Nothing when it is off. While it is
 * on screen, Today's window is FLAG_SECURE too (compass checker #1/#6: the safer of the two choices; a tap-to-reveal
 * line would still sit on a plain window once revealed). TalkBack reads the words themselves; no description repeats them.
 */
@Composable
fun CompassTodayLine(line: String, modifier: Modifier = Modifier) {
    SecureWindow()
    Row(
        modifier.fillMaxWidth().padding(start = Space.textEdge, end = Space.gutter, top = Space.headerTop)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CompassIcons.Compass, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.sm))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── A project serves a goal (the project page's picker; pagel.uz stores only the goal's id) ─────────────────────────

@HiltViewModel
class ServesGoalViewModel @Inject constructor(private val repo: CompassRepository) : ViewModel() {
    val links: StateFlow<Map<String, String>> = repo.links.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val state: StateFlow<VaultState> = repo.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultState.READING)
    /** The goals a project can serve: open goals above Someday, the season's first. */
    val goals: StateFlow<List<Goal>> = repo.opened.map { o ->
        o.goals.filter { it.level != Level.SOMEDAY && it.body.state == GoalBody.OPEN }.sortedBy { listOf(Level.SEASON, Level.YEAR, Level.TEN_YEAR, Level.PURPOSE).indexOf(it.level) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The one-use cipher for the Compass's own prompt, handed to [prompt] on Main (nothing if the key is gone). */
    fun beginUnlock(prompt: (javax.crypto.Cipher) -> Unit) { viewModelScope.launch { runCatching { repo.cipherForUnlock() }.onSuccess(prompt) } }
    fun unlocked(cipher: javax.crypto.Cipher) { viewModelScope.launch { runCatching { repo.unlock(cipher) } } }
    fun link(project: String, goal: String?) { viewModelScope.launch { repo.link(project, goal) } }
    fun leave() = repo.leave()
    fun stay() = repo.stay()
}

/** Under a project's title: "Serves a goal" (never the goal's words on this unlocked page) or "Link to a goal". */
@Composable
fun ServesGoalRow(project: String, vm: ServesGoalViewModel = hiltViewModel()) {
    val links by vm.links.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val linked = links[project] != null
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchMin).padding(horizontal = Space.textEdge).pressScale(press)
            .clickable(press, indication = null, role = Role.Button) { open = true }
            .semantics(mergeDescendants = true) { contentDescription = if (linked) "Serves a goal" else "Link to a goal" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CompassIcons.Compass, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.sm))
        Text(if (linked) "Serves a goal" else "Link to a goal", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1)
        if (linked) { Spacer(Modifier.width(Space.xs)); Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(16.dp)) }
    }
    if (open) GoalPickerSheet(project, vm, onDismiss = { open = false })
}

/** The picker: goal titles opened on the phone only, in a secured sheet; locked → unlock first. */
@Composable
private fun GoalPickerSheet(project: String, vm: ServesGoalViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    GlassSheet(onDismiss = onDismiss, secure = true) {
        SecureWindow()
        AutoLock(onLeave = vm::leave, onStay = vm::stay)
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Which goal does it serve?", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
            when (state) {
                VaultState.OPEN -> {
                    if (goals.isEmpty()) Hint("Write a goal in the Compass first.")
                    PickRow("No goal", links[project] == null) { vm.link(project, null); onDismiss() }
                    for (g in goals) {
                        val label = (if (g.level == Level.YEAR) CompassRules.yearTitle(CompassRules.yearStart(java.time.LocalDate.parse(g.starts ?: "2026-11-01"))) else g.level.label) + " · " + CompassRules.firstWords(g.body.title)
                        PickRow(label, links[project] == g.id) { vm.link(project, g.id); onDismiss() }
                    }
                }
                VaultState.LOCKED -> {
                    Hint("Your goals are locked. Unlock to pick one.")
                    MintButton("Unlock", CompassIcons.KeyRound, Modifier.fillMaxWidth()) {
                        val activity = ctx.findActivity() ?: return@MintButton
                        vm.beginUnlock { cipher -> askUnlock(activity, "Open your Compass", cipher) { a -> if (a is UnlockAnswer.Ok) vm.unlocked(a.cipher) } }
                    }
                }
                else -> Hint("Start your Compass in More → Compass first.")
            }
        }
    }
}

@Composable
private fun PickRow(label: String, on: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = on; contentDescription = "Serves: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (on) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp))
    }
}
