package org.pagel.ai.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.rituals.MintButton

/** Opens the guide on one card; the shell provides it, so a screen's "?" is one line and carries no navigation. */
val LocalGuideOpener: CompositionLocal<(Int) -> Unit> = compositionLocalOf { { } }

/** The small "?" in a screen's header: opens the guide on the card that explains that screen. */
@Composable
fun GuideHelpButton(card: Int) {
    val open = LocalGuideOpener.current
    GlassIconButton(TabIcons.CircleHelp, "How it works: ${GuideCards.all[card].title}", plain = true) { open(card) }
}

/** Ten cards, one idea each, swiped like pages. Try it leaves for the real screen. */
@Composable
fun GuideScreen(card: Int, padding: PaddingValues, onTry: (GuideDestination) -> Unit) {
    val cards = GuideCards.all
    val pager = rememberPagerState(initialPage = card.coerceIn(0, cards.lastIndex)) { cards.size }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(start = Space.textEdge, end = Space.textEdge, top = Space.headerTop, bottom = Space.sm)) {
            Text("Swipe through ${cards.size} cards", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
            Text("How it works", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, maxLines = 1, modifier = Modifier.semantics { heading() })
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top, key = { cards[it].id }) { i ->
            CardPage(cards[i], i, cards.size, onTry)
        }
        Dots(pager.currentPage, cards.size)
    }
}

@Composable
private fun CardPage(card: GuideCard, index: Int, total: Int, onTry: (GuideDestination) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)
            .semantics(mergeDescendants = false) { contentDescription = "Card ${index + 1} of $total" },
    ) {
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).background(Brush.linearGradient(listOf(card.tint.copy(alpha = 0.85f), card.tint)), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(card.icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.width(Space.md))
                    Text("${index + 1}/$total", style = Glass.NumberText, color = Glass.Muted)
                }
                Text(card.title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                Text(card.line, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
                Fact("Example", card.example, Glass.PrimaryInk)
                Fact("Don’t", card.dont, Glass.Risk)
            }
        }
        Spacer(Modifier.height(Space.paneGap))
        MintButton("Try it", TabIcons.ArrowRight, Modifier.fillMaxWidth(), description = "Try it") { onTry(card.goes) }
        Text(card.tryLabel, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.sm), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A small label over one line: the example in mint ink, the don't in red, so the two read apart at a glance. */
@Composable
private fun Fact(label: String, text: String, colour: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
    }
}

/** Ten dots, the current one longer and mint: where you are in the cards. */
@Composable
private fun Dots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val on = i == current
            Box(Modifier.width(if (on) Space.xxl else Space.sm).height(Space.sm).background(if (on) Glass.Primary else Glass.Primary.copy(alpha = 0.25f), CircleShape))
        }
    }
}
