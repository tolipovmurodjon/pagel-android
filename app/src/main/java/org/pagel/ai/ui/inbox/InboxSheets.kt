package org.pagel.ai.ui.inbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectSheet
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.projects.shortName

/** The question each form asks, and the line it suggests (learn-client.tsx ib-form). */
private fun Choice.question() = when (this) {
    Choice.ACT -> "Next step: a verb and a thing"
    Choice.NOTE -> "One line you will want to find later"
    Choice.KEEP -> "In your words: the idea, or what you will do with it"
    else -> "Do it now: under 2 minutes, then it is gone for good."
}

private fun Choice.placeholder() = when (this) {
    Choice.ACT -> "Watch the build video and list the parts"
    Choice.NOTE -> "@account: who they are, and why you saved them"
    else -> "Quizzes beat rereading, so every lesson ends with one"
}

private fun Choice.button() = when (this) {
    Choice.ACT -> "Add to plan"
    Choice.NOTE -> "Save note"
    Choice.KEEP -> "Keep"
    else -> "Done"
}

@Composable
fun ChoiceFormSheet(form: Form, ui: InboxUi, onEdit: (Form) -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit, withPicture: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    GlassSheet(onDismiss) {
        ChoiceFormContent(form, ui, onEdit, onSubmit, onProject = { picking = true }, focus = true, withPicture = withPicture)
        Spacer(Modifier.height(SheetBottom))
    }
    if (picking) ProjectSheet(ui.projects, form.project, onPick = { onEdit(form.copy(project = it)) }, onDismiss = { picking = false })
}

/**
 * The words a choice needs before the card leaves: Action's next step (with a project and This week / Someday),
 * Note's one line, Learn's sentence in Boss's own words (6+), or Do now's "go do it". The button counts the words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceFormContent(form: Form, ui: InboxUi, onEdit: (Form) -> Unit, onSubmit: () -> Unit, onProject: () -> Unit, focus: Boolean = false, withPicture: Boolean = false) {
    val look = form.choice.look
    val uri = LocalUriHandler.current
    val need = if (form.choice == Choice.KEEP) ui.minWords else 2
    val count = words(form.line)
    val ready = form.choice == Choice.NOW || count >= need
    val field = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (focus && form.choice != Choice.NOW) runCatching { field.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(Brush.linearGradient(listOf(look.color.copy(alpha = 0.85f), look.color)), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(look.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(form.choice.question(), style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
        }
        if (form.choice != Choice.NOW) {
            val shape = RoundedCornerShape(16.dp)
            BasicTextField(
                value = form.line,
                onValueChange = { onEdit(form.copy(line = it.replace('\n', ' '))) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).focusRequester(field).onFocusChanged { focused = it.isFocused }
                    .background(Color.White.copy(alpha = 0.86f), shape)
                    .border(if (focused) 1.5.dp else 1.dp, SolidColor(if (focused) Glass.Primary else Glass.Ink.copy(alpha = 0.14f)), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics { contentDescription = look.label + ": your line" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
                cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> Box { if (form.line.isEmpty()) Text(form.choice.placeholder(), style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
            )
            Spacer(Modifier.height(10.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.choice == Choice.ACT || form.choice == Choice.NOTE) {
                val p = ui.projects.firstOrNull { it.name == form.project }
                GlassChip(TabIcons.Folder, p?.let { shortName(it.name, it.title) } ?: form.project, set = true, description = "Project: ${p?.title ?: form.project}",
                    leading = { ProjectTile(form.project, p?.color, p?.title ?: form.project, size = 20.dp) }, onClick = onProject)
            }
            // When? Today · Tomorrow · Someday (round D item 2): one optional pick; none = no date. Tapping the chosen one
            // again clears it. Today and Tomorrow date the task, so it shows on Today or Upcoming.
            if (form.choice == Choice.ACT) {
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent"))
                val chosen = when { form.someday -> 2; form.due == today.toString() -> 0; form.due == today.plusDays(1).toString() -> 1; else -> -1 }
                Segmented(listOf("Today", "Tomorrow", "Someday"), chosen) { i ->
                    onEdit(when {
                        i == chosen -> form.copy(due = null, someday = false)
                        i == 2 -> form.copy(due = null, someday = true)
                        else -> form.copy(due = today.plusDays(i.toLong()).toString(), someday = false)
                    })
                }
            }
            if (form.choice == Choice.NOW) form.card.link?.let { l -> GlassChip(TabIcons.ArrowUpRight, "Open it", set = false) { runCatching { uri.openUri(l) } } }
        }
        val hint = when (form.choice) {
            Choice.KEEP -> if (ready) "Ready" else "${need - count} more words"
            Choice.NOTE -> if (withPicture) "With the picture → the project's notes" else "→ the project's notes"
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(form.choice.button(), TabIcons.CornerDownLeft, enabled = ready, color = look.color, onClick = onSubmit)
    }
}

/** The sheet's one primary action: the choice's colour, white words, a press and a confirm tick. */
@Composable
private fun PrimaryButton(label: String, icon: ImageVector, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .background(color, RoundedCornerShape(16.dp))
            .clickable(press, indication = null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
    }
}

/** Two-way switch (This week / Someday): Apple's segmented control, the chosen half white and raised. */
@Composable
private fun Segmented(options: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.heightIn(min = TouchMin).background(Glass.Ink.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { i, o ->
            val on = i == chosen
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier.heightIn(min = 42.dp).pressScale(press)
                    .then(if (on) Modifier.shadow(3.dp, RoundedCornerShape(11.dp), ambientColor = Glass.Ink.copy(alpha = 0.10f), spotColor = Glass.Ink.copy(alpha = 0.18f)).background(Color.White, RoundedCornerShape(11.dp)) else Modifier)
                    .clickable(press, indication = null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(i) }
                    .semantics { selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = MaterialTheme.typography.labelLarge, color = if (on) Glass.Ink else Glass.Muted) }
        }
    }
}

/** "How sorting works": the five gestures as one picture, then each choice with when to use it and an example (the web's guide). */
@Composable
fun GuideSheet(onDismiss: () -> Unit) {
    val openGuide = org.pagel.ai.ui.guide.LocalGuideOpener.current
    GlassSheet(onDismiss) { GuideContent(onGuide = { onDismiss(); openGuide(org.pagel.ai.ui.guide.GuideCards.CLARIFY) }); Spacer(Modifier.height(SheetBottom)) }
}

@Composable
fun GuideContent(onGuide: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("How sorting works", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        SwipeMap(Modifier.fillMaxWidth().padding(bottom = 14.dp))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                listOf(Choice.ACT, Choice.NOTE, Choice.KEEP, Choice.NOW, Choice.LATER, Choice.DROP).forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 60.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                    val look = c.look
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(32.dp).background(Brush.linearGradient(listOf(look.color.copy(alpha = 0.85f), look.color)), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Icon(look.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(look.label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                            Text(look.whenText, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3B4256))
                            Text(look.eg, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        Text("GTD in one line: every save gets one decision now, so nothing waits in your head. Most saves are a Drop; that is the point.",
            style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
        if (onGuide != null) Text("How it works →", style = MaterialTheme.typography.titleMedium, color = Glass.PrimaryInk,
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onGuide).padding(horizontal = 4.dp, vertical = 12.dp))
    }
}

/**
 * The card's five gestures as one small picture (Boss 2026-10-28): four arrows around a held finger, each with its
 * icon, word and colour, as the stamps show them while dragging.
 */
@Composable
fun SwipeMap(modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "Swipe up to shuffle, down to drop, left to learn, right for an action; hold for Do now"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Way(TabIcons.ArrowUp, Swipe.SHUFFLE)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Way(TabIcons.ArrowLeft, Swipe.LEARN, Modifier.weight(1f))
            HoldMark(Modifier.padding(horizontal = 8.dp))
            Way(TabIcons.ArrowRight, Swipe.ACTION, Modifier.weight(1f))
        }
        Way(TabIcons.ArrowDown, Swipe.DROP)
    }
}

@Composable
private fun Way(arrow: ImageVector, swipe: Swipe, modifier: Modifier = Modifier) {
    val (icon, label, color) = swipe.look(false)
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(arrow, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(3.dp))
        Row(Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

/** A held finger: Do now's ring three-quarters full around a dot, "Hold" under it. */
@Composable
private fun HoldMark(modifier: Modifier = Modifier) {
    val color = Choice.NOW.look.color
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(46.dp)) {
                val w = 3.5.dp.toPx()
                drawCircle(color.copy(alpha = 0.12f), size.minDimension / 2 - w / 2)
                drawArc(color, -90f, 270f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            Icon(TabIcons.Zap, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text("Hold · Do now", style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** The whole post, for a long caption the card cuts at three lines. */
@Composable
fun ReadAllSheet(card: InboxCard, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    GlassSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(card.claim, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
            Spacer(Modifier.height(10.dp))
            Text(card.text, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF3B4256))
            card.link?.let { l ->
                Spacer(Modifier.height(12.dp))
                GlassChip(TabIcons.ArrowUpRight, "Open the post", set = false) { runCatching { uri.openUri(l) } }
            }
            Spacer(Modifier.height(SheetBottom))
        }
    }
}
