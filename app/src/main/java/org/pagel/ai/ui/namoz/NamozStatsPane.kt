package org.pagel.ai.ui.namoz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.NamozStats
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * His own counts, plain: full days this week, each prayer over the last 30 days, the month as five dots a day (swipe
 * for other months), prayers in the mosque this month. No score, no judgement words; an empty day is empty dots.
 */
@Composable
internal fun StatsPane(ui: NamozUi) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            val (full, of) = ui.week
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$full/$of", style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = Glass.Ink)
                Text("full days this week", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(start = Space.sm))
            }
            // The name column is as wide as the longest prayer name at this font size, so at 1.5× it never touches its bar.
            val nameStyle = MaterialTheme.typography.bodyMedium
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val nameWidth = remember(nameStyle, density) { with(density) { Namoz.Prayer.entries.maxOf { measurer.measure(it.uz, nameStyle).size.width }.toDp() } }
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Last 30 days", style = MaterialTheme.typography.labelLarge, color = Glass.Muted, modifier = Modifier.semantics { heading() })
                for ((p, n, days) in ui.perPrayer) Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = "${p.uz}: $n of $days days" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.uz, style = nameStyle, color = Glass.Ink, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = Space.md).width(nameWidth))
                    Box(Modifier.weight(1f).height(8.dp).background(Glass.Ink.copy(alpha = 0.07f), CircleShape)) {
                        if (days > 0 && n > 0) Box(Modifier.fillMaxWidth(n / days.toFloat()).height(8.dp).background(Glass.Primary, CircleShape))
                    }
                    Text("$n/$days", style = Glass.NumberText, color = Glass.Muted, maxLines = 1, softWrap = false,
                        textAlign = TextAlign.End, modifier = Modifier.padding(start = Space.sm).width(60.dp))
                }
            }
            MonthGrid(ui)
            Text("${ui.mosque} in the mosque this month", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
    }
}

/** The month: 7 columns, always 6 week rows (no jump between months), each day five dots, filled = ticked. */
@Composable
private fun MonthGrid(ui: NamozUi) {
    val months = ui.months
    val pager = rememberPagerState(initialPage = months.lastIndex) { months.size }
    val scope = rememberCoroutineScope()
    val shown = months[pager.currentPage.coerceIn(0, months.lastIndex)]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${shown.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${shown.year}", style = MaterialTheme.typography.titleSmall,
            color = Glass.Ink, modifier = Modifier.weight(1f).semantics { heading() })
        val canBack = pager.currentPage > 0
        val canNext = pager.currentPage < months.lastIndex
        // The app's secondary ink: readable even when there is no other month yet (checker 2026-10-30: "very faint").
        GlassIconButton(TabIcons.ChevronLeft, "Previous month", size = Space.touch, plain = true, tint = if (canBack) Glass.Ink else Glass.Muted) {
            if (canBack) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        }
        GlassIconButton(TabIcons.ChevronRight, "Next month", size = Space.touch, plain = true, tint = if (canNext) Glass.Ink else Glass.Muted) {
            if (canNext) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        for (d in listOf("M", "T", "W", "T", "F", "S", "S")) Text(d, style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
    HorizontalPager(pager, Modifier.fillMaxWidth(), key = { months[it].toString() }) { page -> MonthPage(months[page], ui) }
}

@Composable
private fun MonthPage(month: YearMonth, ui: NamozUi) {
    val days = NamozStats.month(ui.done, month)
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells: List<Pair<java.time.LocalDate, Set<Namoz.Prayer>>?> = List(lead) { null } + days + List(42 - lead - days.size) { null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        for (week in cells.chunked(7)) Row(Modifier.fillMaxWidth()) {
            for (cell in week) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (cell != null) DayCell(cell.first, cell.second, ui) }
        }
    }
}

@Composable
private fun DayCell(day: java.time.LocalDate, set: Set<Namoz.Prayer>, ui: NamozUi) {
    val counted = Namoz.counts(day.toString()) && day <= ui.today
    val isToday = day == ui.today
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = if (counted) "${day.dayOfMonth}: ${set.size} of 5" else "${day.dayOfMonth}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
            color = if (isToday) Glass.PrimaryInk else if (counted) Glass.Ink else Glass.Muted.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (p in Namoz.Prayer.entries) Box(
                Modifier.size(4.dp).background(
                    // A day still to come has no dots (kept transparent, so the grid never changes height).
                    when { p in set -> Glass.Primary; counted -> Glass.Ink.copy(alpha = 0.12f); day > ui.today -> Color.Transparent; else -> Glass.Ink.copy(alpha = 0.05f) }, CircleShape,
                ),
            )
        }
    }
}
