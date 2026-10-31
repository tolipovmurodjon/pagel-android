package org.pagel.ai.ui.capture

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.ui.add.AddTaskContent
import org.pagel.ai.ui.add.AddTaskViewModel
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.glass.rememberProjectPickerActions

private val FieldRim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.55f)))

/**
 * The one capture sheet the + opens (Inbox by default): a text field with the keyboard up, Enter saves to the Inbox and
 * the field clears for the next one. A quiet "Plan it" chip turns it into the task form. Long-press the arrow, or the +
 * that opened it, to speak.
 */
@Composable
fun CaptureSheet(onClose: () -> Unit, speak: Boolean = false, vm: CaptureViewModel = hiltViewModel(), taskVm: AddTaskViewModel = hiltViewModel()) {
    var planning by rememberSaveable { mutableStateOf(false) }
    val projects by taskVm.projects.collectAsStateWithLifecycle()
    val notSaved by taskVm.notSaved.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    GlassSheet(onClose) {
        if (planning) {
            AddTaskContent(projects, home = "me", toToday = true, now = taskVm::now, notSaved = notSaved, onAdded = onClose, picker = rememberProjectPickerActions(), onSave = taskVm::save)
            Row(Modifier.padding(start = 16.dp, bottom = 4.dp)) { GlassChip(TabIcons.Inbox, "To Inbox", set = false, description = "To Inbox") { planning = false } }
        } else {
            CaptureContent(vm, speak)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassChip(TabIcons.ListPlus, "Plan it", set = false, description = "Plan it") { planning = true }
                Spacer(Modifier.weight(1f))
                if (count > 0) Text("In Inbox · $count", style = MaterialTheme.typography.labelMedium, color = Glass.PrimaryInk, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(SheetBottom))
    }
}

/** The field and its arrow: the capture itself, apart from the sheet so a test can draw it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CaptureContent(vm: CaptureViewModel, speak: Boolean = false, initial: String = "") {
    var typed by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit(text: String) {
        if (vm.save(text)) {
            // One soft tick per save (rules/soft-tick-on-every-move); the field is empty again for the next one.
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            typed = ""
        }
    }
    val heard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) typed = (typed + " " + said).trim()
    }
    fun listen() {
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try { heard.launch(ask) } catch (_: ActivityNotFoundException) { vm.noSpeech() }
    }
    LaunchedEffect(speak) { if (speak) listen() }

    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Add to Inbox")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp)
                .background(Color.White.copy(alpha = 0.84f), RoundedCornerShape(26.dp)).border(1.dp, FieldRim, RoundedCornerShape(26.dp))
                .padding(start = 18.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = typed,
                // A hardware Enter arrives as one new line at the end: that is "save". A paste may hold many lines.
                onValueChange = { v -> if (v.length == typed.length + 1 && v.endsWith("\n") && typed.isNotBlank()) submit(typed) else typed = v },
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
                cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(typed) }),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp).focusRequester(focus).semantics { contentDescription = "New capture" },
                decorationBox = { inner -> Box { if (typed.isEmpty()) Text("A thought, a link or a list", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, maxLines = 1); inner() } },
            )
            Spacer(Modifier.width(8.dp))
            val ready = typed.isNotBlank()
            val press = remember { MutableInteractionSource() }
            val fill by animateColorAsState(if (ready) Glass.Primary else Color.White.copy(alpha = 0.72f), label = "capture-send")
            // Tap saves; long-press speaks. A tap on an empty field speaks too: the arrow has nothing else to do then.
            Box(
                Modifier.size(TouchMin).pressScale(press)
                    .combinedClickable(press, indication = null, role = Role.Button, onLongClickLabel = "Speak", onLongClick = { listen() }) { if (ready) submit(typed) else listen() }
                    .semantics { contentDescription = if (ready) "Add to Inbox" else "Speak" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(44.dp).background(fill, CircleShape).then(if (ready) Modifier else Modifier.border(1.dp, FieldRim, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (ready) TabIcons.ArrowUp else TabIcons.Mic, contentDescription = null, tint = if (ready) Color.White else Glass.Muted, modifier = Modifier.size(22.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
