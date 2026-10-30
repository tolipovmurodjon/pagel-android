package org.pagel.ai.ui.inbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import org.pagel.ai.data.inbox.OLD_DAYS
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.DateSheet
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
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
    Choice.WAITING -> "What are you waiting for?"
    Choice.CALENDAR -> "What happens, and when?"
    Choice.PROJECT -> "Name it, then its first step"
    else -> "Do it now: under 2 minutes, then it is gone for good."
}

private fun Choice.placeholder() = when (this) {
    Choice.ACT -> "Watch the build video and list the parts"
    Choice.NOTE -> "@account: who they are, and why you saved them"
    Choice.WAITING -> "The signed contract"
    Choice.CALENDAR -> "The grant round closes"
    Choice.PROJECT -> "Pick the tool and write issue one"
    else -> "Quizzes beat rereading, so every lesson ends with one"
}

private fun Choice.button() = when (this) {
    Choice.ACT -> "Add to plan"
    Choice.NOTE -> "Save note"
    Choice.KEEP -> "Keep"
    Choice.WAITING -> "Add to waiting"
    Choice.CALENDAR -> "Put on calendar"
    Choice.PROJECT -> "Start project"
    else -> "Done"
}

@Composable
fun ChoiceFormSheet(form: Form, ui: InboxUi, onEdit: (Form) -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit, withPicture: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    // Calendar asks for the time at once (checker 2026-10-30 #6): the date sheet opens on the wheel, on the day the save
    // names (or tomorrow) at 09:00; Done comes back to the form.
    var dating by remember { mutableStateOf(form.choice == Choice.CALENDAR) }
    GlassSheet(onDismiss) {
        ChoiceFormContent(form, ui, onEdit, onSubmit, onProject = { picking = true }, onDate = { dating = true }, focus = form.choice != Choice.CALENDAR, withPicture = withPicture)
        Spacer(Modifier.height(SheetBottom))
    }
    if (picking) ProjectSheet(ui.projects, form.project, onPick = { onEdit(form.copy(project = it)) }, onDismiss = { picking = false })
    if (dating) DateSheet(form.due, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent")), onPick = { onEdit(form.copy(due = it)) }, onDismiss = { dating = false },
        startMode = if (form.choice == Choice.CALENDAR && (form.due?.length ?: 0) > 10) "wheel" else "")
}

/**
 * The words a choice needs before the card leaves: Action's next step (with a project and This week / Someday),
 * Note's one line, Learn's sentence in Boss's own words (6+), or Do now's "go do it". The button counts the words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceFormContent(form: Form, ui: InboxUi, onEdit: (Form) -> Unit, onSubmit: () -> Unit, onProject: () -> Unit, onDate: () -> Unit = {}, focus: Boolean = false, withPicture: Boolean = false) {
    val look = form.choice.look
    val uri = LocalUriHandler.current
    val need = if (form.choice == Choice.KEEP) ui.minWords else 2
    val count = words(form.line)
    val ready = formReady(form, ui.minWords)
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent"))
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
        if (form.choice == Choice.PROJECT) {
            FormField(form.name, { onEdit(form.copy(name = it.replace('\n', ' '))) }, "Newsletter for parents", "Project name")
            Spacer(Modifier.height(Space.sm))
            AreaChips(ui, form.area) { onEdit(form.copy(area = it)) }
            Spacer(Modifier.height(Space.sm))
            FieldLabel("First step")
        }
        if (form.choice == Choice.WAITING) {
            FormField(form.who, { onEdit(form.copy(who = it.replace('\n', ' '))) }, "Aziz", "Waiting for who (optional)")
            Spacer(Modifier.height(Space.sm))
            FieldLabel("What you wait for")
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
            if (form.choice == Choice.ACT || form.choice == Choice.NOTE || form.choice == Choice.WAITING || form.choice == Choice.CALENDAR) {
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
            // Waiting: the check day, 3 days on by default (Boss 2026-10-30). Calendar: the date and the wheel's hour.
            if (form.choice == Choice.WAITING) {
                val days = listOf(3L, 7L, 14L)
                val chosen = days.indexOfFirst { form.due == today.plusDays(it).toString() }
                Segmented(listOf("3 days", "1 week", "2 weeks"), chosen) { i -> onEdit(form.copy(due = today.plusDays(days[i]).toString())) }
            }
            if (form.choice == Choice.CALENDAR) {
                GlassChip(TabIcons.Calendar, DatePick.dueText(form.due, today), set = form.due != null, description = "Date and time: ${DatePick.dueText(form.due, today)}", onClick = onDate)
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

/** A field's name over it, kept when the field is filled (checker 2026-10-30 #9: the placeholder was the only label). */
@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1, modifier = Modifier.padding(start = Space.xs, bottom = Space.xs))
}

/**
 * The new project's life Area (checker 2026-10-30 #7): None or one of the Areas Boss uses, in one scrolling row; it
 * starts on the area of the project the AI guessed for the card.
 */
@Composable
private fun AreaChips(ui: InboxUi, area: String?, onPick: (String?) -> Unit) {
    val areas = (ui.projects.mapNotNull { it.area } + listOf("Work", "Health", "Mind", "Faith", "Money", "People", "Games")).distinct()
    FieldLabel("Area")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.chipGap)) {
        for (a in listOf<String?>(null) + areas) {
            val on = a == area
            GlassChip(TabIcons.Folder, a ?: "None", set = on, checked = on, description = "Area: ${a ?: "None"}", modifier = Modifier.semantics { selected = on }) { onPick(a) }
        }
    }
}

/** A one-line field under the sheet's question (the project's name, who is waited on): its name over it, one row tall. */
@Composable
private fun FormField(value: String, onChange: (String) -> Unit, placeholder: String, label: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    FieldLabel(label)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch).onFocusChanged { focused = it.isFocused }
            .background(Color.White.copy(alpha = 0.86f), shape)
            .border(if (focused) 1.5.dp else 1.dp, SolidColor(if (focused) Glass.Primary else Glass.Ink.copy(alpha = 0.14f)), shape)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics { contentDescription = label },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
        cursorBrush = SolidColor(Glass.Primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
    )
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

/** The second action of a sheet: tonal ink on soft glass, never red (Boss's rule; checker 2026-10-30 #9). */
@Composable
private fun QuietButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .background(Glass.Ink.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Glass.Ink.copy(alpha = 0.78f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.sm))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
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

/**
 * The bulk tidy-up (Boss 2026-10-30: "900+ items"): the saves older than 60 days, counted before anything moves, by where
 * they came from. One tap moves them all to Someday or drops them all; nothing leaves the phone for 5 s and one Undo
 * takes every one back.
 */
@Composable
fun TidySheet(tidy: Tidy, onSomeday: () -> Unit, onDrop: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit = {}) {
    // Material's sheet keeps the height it opened with (CI 41084160574: it opened on "Looking…" and the found count's
    // buttons stayed below the screen). A new state is a new sheet, opened at its own height; a tall one scrolls.
    androidx.compose.runtime.key(tidy.loading, tidy.failed) {
        GlassSheet(onDismiss) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { TidyContent(tidy, onSomeday, onDrop, onRetry); Spacer(Modifier.height(SheetBottom)) }
        }
    }
}

/**
 * pagel.uz's whole count of old saves (checker 2026-10-30 #2: it said 60 when there were 412), the whole Inbox by source,
 * then the two moves. Offline: say so and offer Retry. Drop all is the quiet ink button, never red.
 */
@Composable
fun TidyContent(tidy: Tidy, onSomeday: () -> Unit, onDrop: () -> Unit, onRetry: () -> Unit = {}) {
    val n = tidy.count
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Text("Old saves", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs, bottom = Space.sm))
        when {
            tidy.loading -> Text("Looking for saves older than ${OLD_DAYS} days…", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs))
            tidy.failed -> {
                Text("Could not reach pagel.uz. Nothing moved.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs, bottom = Space.md))
                QuietButton("Retry", TabIcons.Refresh, onRetry)
            }
            n == 0 -> Text("Nothing older than ${OLD_DAYS} days. The Inbox is fresh.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs))
            else -> {
                Text("$n saves older than ${OLD_DAYS} days", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs))
                Text(if (tidy.bySource.isNotEmpty()) "Your whole Inbox, by where it came from:" else "By where they came from:", style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
                    modifier = Modifier.padding(start = Space.xs, top = Space.sm))
                // One line, not a row per source: the sheet stays short enough that both moves show on a 640 dp phone.
                Pane(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
                    Text(tidy.sources.joinToString(" · ") { (name, count) -> "$name\u00A0$count" }, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink,
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md))
                }
                Text("Nothing is sent for 5 seconds; one Undo stops all $n. None of it counts toward today's 20.", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs, bottom = Space.md))
                PrimaryButton("Move all $n to Someday", TabIcons.Moon, enabled = true, color = Choice.SOMEDAY.look.color, onClick = onSomeday)
                Spacer(Modifier.height(Space.sm))
                QuietButton("Drop all $n", TabIcons.Trash, onDrop)
            }
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
                (NO_GROUP + YES_GROUP + Choice.LATER).forEachIndexed { i, c ->
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
        Text("GTD in one line: ask \"Can I do something about it?\". No → Drop, Someday, Note or Learn. Yes → Do now, Action, Project, Waiting or Calendar. Most saves are a Drop; that is the point.",
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
