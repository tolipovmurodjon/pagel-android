package org.pagel.ai.ui.rituals

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.projects.shortName

private val STEP_NAMES = listOf("Inbox", "Head", "Steps", "Someday")

@Composable
fun ReviewRoute(contentPadding: PaddingValues, onInbox: () -> Unit, onSweep: () -> Unit, onToday: () -> Unit, vm: ReviewViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ReviewScreen(ui, contentPadding, onGo = vm::go, onStep = vm::setStep, onDrop = vm::toggleDrop, onFinish = vm::finish, onInbox = onInbox, onSweep = onSweep, onToday = onToday)
}

/**
 * The weekly review, native (Phase 5, the web's /review) as a stepper: 1 empty the Inbox, 2 empty your head, 3 every
 * stuck project gets one next step, 4 someday pruned; Finish adds the steps (P1), drops what Boss let go and logs the
 * review. Step 5 is on Today: star the week's first 3.
 */
@Composable
fun ReviewScreen(
    ui: ReviewUi,
    contentPadding: PaddingValues,
    onGo: (Int) -> Unit = {},
    onStep: (String, String) -> Unit = { _, _ -> },
    onDrop: (String) -> Unit = {},
    onFinish: () -> Unit = {},
    onInbox: () -> Unit = {},
    onSweep: () -> Unit = {},
    onToday: () -> Unit = {},
) {
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head", contentType = "head") {
            RitualHead("About 30 minutes", "Weekly review", ui.last?.let { "It keeps your lists true. Last one: $it." } ?: "It keeps your lists true. This is your first one.")
        }
        when {
            ui.done -> item(key = "done", contentType = "pane") {
                Column {
                    RitualPane("Review done ✓") {
                        Text("Your lists match your life again. Now star this week's first 3.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                    }
                    Spacer(Modifier.size(12.dp))
                    ButtonRow { MintButton("Go to Today", TabIcons.Today, Modifier.weight(1f), onClick = onToday) }
                }
            }
            ui.loading -> item(key = "loading", contentType = "pane") { Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3) } }
            else -> {
                item(key = "dots", contentType = "dots") { StepDots(STEP_NAMES, ui.step, onGo) }
                item(key = "step", contentType = "step") {
                    AnimatedContent(
                        targetState = ui.step,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInHorizontally(spring(dampingRatio = 1f, stiffness = 500f)) { if (forward) it / 3 else -it / 3 } + fadeIn())
                                .togetherWith(slideOutHorizontally(spring(dampingRatio = 1f, stiffness = 600f)) { if (forward) -it / 3 else it / 3 } + fadeOut())
                        },
                        label = "review-step",
                    ) { step -> Step(step, ui, onStep, onDrop, onInbox, onSweep) }
                }
                item(key = "nav", contentType = "buttons") {
                    ButtonRow {
                        if (ui.step > 0) QuietButton("Back", TabIcons.ChevronLeft, Modifier.weight(1f)) { onGo(ui.step - 1) }
                        if (ui.step < ReviewUi.STEPS - 1) MintButton("Next", TabIcons.ChevronRight, Modifier.weight(1f)) { onGo(ui.step + 1) }
                        else MintButton("Finish review", TabIcons.Check, Modifier.weight(1.4f), enabled = ui.steps.values.none { org.pagel.ai.data.rituals.Rituals.words(it) > org.pagel.ai.data.rituals.Rituals.MAX_WORDS }, onClick = onFinish)
                    }
                }
                if (ui.step == ReviewUi.STEPS - 1) item(key = "five", contentType = "text") {
                    Hint("Step 5 is on Today: star the week's first 3.", modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun Step(step: Int, ui: ReviewUi, onStep: (String, String) -> Unit, onDrop: (String) -> Unit, onInbox: () -> Unit, onSweep: () -> Unit) {
    when (step) {
        0 -> RitualPane("1. Empty the Inbox") {
            if (ui.inbox == 0) Text("Inbox is empty ✓", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
            else {
                Text(if (ui.inbox == 1) "1 save waiting." else "${ui.inbox} saves waiting.", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
                Hint("Clear them, then come back.")
                Spacer(Modifier.size(12.dp))
                QuietButton("Open the Inbox", TabIcons.Inbox, Modifier.fillMaxWidth(), onClick = onInbox)
            }
        }
        1 -> RitualPane("2. Empty your head") {
            Text("Anything new since last week?", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
            Hint("A mind sweep takes 5-10 minutes.")
            Spacer(Modifier.size(12.dp))
            QuietButton("Empty your head", TabIcons.Sparkles, Modifier.fillMaxWidth(), onClick = onSweep)
        }
        2 -> RitualPane("3. Every project needs one next step") {
            if (ui.stuck.isEmpty()) Text("All projects have a next step ✓", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
            ui.stuck.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    ProjectTile(s.project, null, s.title, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(shortName(s.project, s.title), style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                }
                val step = ui.steps[s.project].orEmpty()
                StepField(step, "Next step for ${shortName(s.project, s.title)}") { onStep(s.project, it) }
                // pagel.uz takes 20 words; the sweep checks it, so does this (checker P5 #10).
                val n = org.pagel.ai.data.rituals.Rituals.words(step)
                if (n > org.pagel.ai.data.rituals.Rituals.MAX_WORDS) Hint("$n words: pagel.uz takes ${org.pagel.ai.data.rituals.Rituals.MAX_WORDS}. Cut it down.", Glass.Risk, Modifier.padding(top = 4.dp))
            }
        }
        else -> RitualPane("4. Someday: still want these?") {
            if (ui.someday.isEmpty()) Text("Nothing on someday.", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
            ui.someday.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                val gone = t.id in ui.drops
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProjectTile(t.project, t.projectColor, t.projectTitle, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        t.title, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (gone) TextDecoration.LineThrough else null),
                        color = if (gone) Glass.Muted else Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    QuietButton(if (gone) "Undo" else "Drop", if (gone) TabIcons.Undo else TabIcons.Trash, description = (if (gone) "Undo drop: " else "Drop: ") + t.title) { onDrop(t.id) }
                }
            }
        }
    }
}

/** A next step for a stuck project: a verb and a thing, saved with the review. */
@Composable
private fun StepField(value: String, label: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.replace('\n', ' ')) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
            cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("A verb and a thing (or leave empty)", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
                    inner()
                }
            },
        )
    }
}
