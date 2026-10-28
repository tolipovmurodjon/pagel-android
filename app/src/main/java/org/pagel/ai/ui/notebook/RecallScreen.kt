package org.pagel.ai.ui.notebook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.notebook.Grade
import org.pagel.ai.data.notebook.RecallSession
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.rituals.MintButton
import org.pagel.ai.ui.rituals.QuietButton

@Composable
fun RecallRouteScreen(padding: PaddingValues, nav: NotebookNav, vm: RecallViewModel = hiltViewModel()) {
    val s by vm.session.collectAsStateWithLifecycle()
    RecallScreen(s, padding, onReveal = vm::reveal, onGrade = vm::grade, onBack = nav.back)
}

/** The grades' colours: Again red, Hard orange, Good mint, Easy blue (each word on white, ≥ 4.5:1). */
private val GradeInk = mapOf(
    Grade.AGAIN to Color(0xFFB3261E), Grade.HARD to Color(0xFFB45309), Grade.GOOD to Color(0xFF0B5C45), Grade.EASY to Color(0xFF1D4ED8),
)

/**
 * Review 5 (Phase 6b): one card at a time, the question on top, its answer hidden until "Show answer", then four
 * grades with a haptic each (Again = reject, Hard = tick, Good and Easy = confirm). The card never moves between
 * face down and face up (Layout 1): the answer's area and the grade row keep their space from the first frame.
 */
@Composable
fun RecallScreen(s: RecallSession?, padding: PaddingValues, onReveal: () -> Unit = {}, onGrade: (Grade) -> Unit = {}, onBack: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        BackHead("Review", onBack) {
            if (s != null && s.cards.isNotEmpty() && !s.finished) Text(s.position, style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.semantics { contentDescription = "Card ${s.position}" })
        }
        when {
            s == null -> Spacer(Modifier.weight(1f))
            s.cards.isEmpty() || s.finished -> Done(s, onBack)
            else -> {
                val card = s.current!!
                AnimatedContent(
                    card.id,
                    transitionSpec = { (slideInHorizontally(spring(dampingRatio = 1f, stiffness = 400f)) { it / 4 } + fadeIn()).togetherWith(fadeOut()) },
                    label = "card", modifier = Modifier.weight(1f),
                ) { _ ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.md)) {
                        Pane(Modifier.fillMaxWidth().testTag("recall-card")) {
                            Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                                Text(card.question, style = MaterialTheme.typography.titleLarge, color = Glass.Ink)
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.Ink.copy(alpha = 0.1f)))
                                // The answer's place is kept face down: the card is the same height either way.
                                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                                    if (s.revealed) Text(card.answer, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.fillMaxWidth())
                                    else QuietButton("Show answer", null, Modifier.fillMaxWidth(), onClick = onReveal)
                                }
                            }
                        }
                    }
                }
                GradeRow(s.revealed, onGrade)
            }
        }
    }
}

@Composable
private fun GradeRow(shown: Boolean, onGrade: (Grade) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.md).graphicsLayer { alpha = if (shown) 1f else 0f },
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        for (g in Grade.entries) {
            val press = remember { MutableInteractionSource() }
            val ink = GradeInk.getValue(g)
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).pressScale(press)
                    .background(Color.White.copy(alpha = 0.84f), RoundedCornerShape(16.dp))
                    .border(1.25.dp, ink.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .clickable(press, indication = null, enabled = shown, role = Role.Button) {
                        haptics.performHapticFeedback(
                            when (g) { Grade.AGAIN -> HapticFeedbackType.Reject; Grade.HARD -> HapticFeedbackType.SegmentTick; else -> HapticFeedbackType.Confirm },
                        )
                        onGrade(g)
                    }
                    .semantics { contentDescription = g.label },
                contentAlignment = Alignment.Center,
            ) { Text(g.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Done(s: RecallSession, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Space.xxxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(if (s.graded.isEmpty()) "🌱" else "✓", style = MaterialTheme.typography.displaySmall, color = Glass.Primary)
        Text(
            if (s.graded.isEmpty()) "Nothing is due. Come back tomorrow." else if (s.graded.size == 1) "1 card reviewed." else "${s.graded.size} cards reviewed.",
            style = MaterialTheme.typography.titleMedium, color = Glass.Ink, textAlign = TextAlign.Center,
        )
        MintButton("Back to Notebook", TabIcons.Notebook, Modifier.fillMaxWidth(), onClick = onBack)
    }
}
