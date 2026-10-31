package org.pagel.ai.ui.habits

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pagel.ai.data.habits.BadgeKind
import org.pagel.ai.data.habits.HabitBadge
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.pressScale
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val EARNED_ON = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

/**
 * Achievements (Boss's reference, in our own drawing): a mint summary card with the three latest badges, then one card
 * per ladder (perfect days, streaks, habits finished) of six badges. A locked badge is the same drawing in grey. A tap
 * opens the badge with the day it was earned. Computed from the logs; a day off counts as done; no badge for prayers.
 */
@Composable
internal fun AchievementsPage(badges: List<HabitBadge>) {
    var open by remember { mutableStateOf<HabitBadge?>(null) }
    val earned = badges.filter { it.earned }
    val recent = earned.sortedWith(compareByDescending<HabitBadge> { it.earnedOn }.thenByDescending { it.n }).take(3)
    // The mint card keeps its dark end light enough for ink words (4.5:1 all across).
    val (mintA, mintB) = org.pagel.ai.ui.glass.TileColors.stops("", "mint")
    val look = CardColours(mintA, androidx.compose.ui.graphics.lerp(mintB, mintA, 0.5f), Glass.Ink)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xxl)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xxl).clip(RoundedCornerShape(Space.xxl))
                .background(Brush.linearGradient(listOf(look.a, look.b), start = Offset.Zero, end = Offset.Infinite))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = 220f))
                .padding(Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("My achievements", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = look.content, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        if (badges.isEmpty() || earned.isEmpty()) "Your first badge is one finished habit away." else "You have earned ${earned.size * 100 / badges.size}% of all achievements.",
                        style = MaterialTheme.typography.bodyMedium, color = look.content.copy(alpha = 0.88f),
                    )
                }
                Spacer(Modifier.size(Space.md))
                Box(Modifier.size(48.dp).border(2.dp, look.content, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${earned.size}", style = Glass.NumberText.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = look.content)
                }
            }
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(Space.lg))
                Box(Modifier.fillMaxWidth().height(1.dp).background(look.content.copy(alpha = 0.22f)))
                Spacer(Modifier.height(Space.md))
                Text("Recent", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = look.content)
                Spacer(Modifier.height(Space.md))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    for (b in recent) BadgeCell(b, Modifier.weight(1f), art = 72.dp, textColour = look.content) { open = b }
                    repeat(3 - recent.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        for (kind in BadgeKind.entries) {
            val of = badges.filter { it.kind == kind }
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.xxl)) {
                Column(Modifier.fillMaxWidth().padding(Space.xxl)) {
                    Text(
                        when (kind) { BadgeKind.PERFECT_DAYS -> "Perfect days"; BadgeKind.STREAK -> "Streaks"; BadgeKind.FINISHED -> "Habits finished" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Glass.Ink, modifier = Modifier.semantics { heading() },
                    )
                    Text("${of.count { it.earned }}/${of.size} earned", style = Glass.NumberText, color = Glass.Muted)
                    Spacer(Modifier.height(Space.lg))
                    for (row in of.chunked(3)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                            for (b in row) BadgeCell(b, Modifier.weight(1f), art = 64.dp, textColour = if (b.earned) Glass.Ink else Glass.Muted) { open = b }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(Space.lg))
                    }
                }
            }
        }
    }
    // A won badge is a full-screen moment with Share and Save; a locked one stays a small sheet that says how to earn it.
    open?.let { b -> if (b.earned) BadgeMoment(b) { open = null } else BadgeSheet(b) { open = null } }
}

@Composable
private fun BadgeCell(b: HabitBadge, modifier: Modifier, art: Dp, textColour: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${b.title}, ${if (b.earned) "earned" else "not yet"}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            BadgeArt(b.kind, b.n, b.earned, art, Modifier.graphicsLayer { alpha = if (b.earned) 1f else 0.55f })
            if (!b.earned) androidx.compose.material3.Icon(org.pagel.ai.ui.glass.TabIcons.Lock, null, tint = Glass.Muted, modifier = Modifier.align(Alignment.BottomEnd).size(16.dp))
        }
        Spacer(Modifier.height(Space.xs))
        Text(b.title, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = textColour, textAlign = TextAlign.Center, minLines = 2, maxLines = 3)
    }
}

@Composable
private fun BadgeSheet(b: HabitBadge, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        BadgeDetail(b)
        Spacer(Modifier.height(SheetBottom))
    }
}

/** The badge opened: big, its name, the day it was earned (or "Not yet") and how to earn it. */
@Composable
internal fun BadgeDetail(b: HabitBadge) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Space.md))
        BadgeArt(b.kind, b.n, b.earned, 148.dp)
        Spacer(Modifier.height(Space.lg))
        Text(b.title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.xs))
        Text(
            b.earnedOn?.let { "Achieved on ${it.format(EARNED_ON)}" } ?: "Not yet",
            style = MaterialTheme.typography.titleSmall, color = if (b.earned) Glass.PrimaryInk else Glass.Muted,
        )
        Spacer(Modifier.height(Space.sm))
        Text(b.how, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, textAlign = TextAlign.Center)
    }
}

private val EARNED_HEX = listOf(Color(0xFF8BE5C0), Color(0xFF1FA383))
private val EARNED_GOLD = listOf(Color(0xFFFFD66E), Color(0xFFF0A22A))
private val LOCKED = listOf(Color(0xFFE3E7ED), Color(0xFFC6CCD6))

/**
 * One badge, drawn: perfect days = a mint hexagon holding a calendar with the number; streak = a gold checklist card on a
 * ribbon with the number; finished = a target with an arrow on a ribbon. Locked = the same shape in grey.
 */
@Composable
internal fun BadgeArt(kind: BadgeKind, n: Int, earned: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val ink = if (earned) Glass.Ink else Glass.Muted
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            when (kind) {
                BadgeKind.PERFECT_DAYS -> drawHexCalendar(earned)
                BadgeKind.STREAK -> drawChecklist(earned)
                BadgeKind.FINISHED -> drawTarget(earned)
            }
        }
        val px = with(LocalDensity.current) { (size * (if (kind == BadgeKind.PERFECT_DAYS) 0.2f else 0.17f)).toSp() }
        val y = size * (if (kind == BadgeKind.PERFECT_DAYS) 0.04f else 0.31f)
        Text("$n", style = Glass.NumberText.copy(fontSize = px, lineHeight = px, fontWeight = FontWeight.Bold), color = ink, modifier = Modifier.offset(y = y))
    }
}

private fun DrawScope.ribbon(s: Float, top: Float, earned: Boolean) {
    val p = Path().apply {
        moveTo(s * 0.10f, s * top); lineTo(s * 0.90f, s * top); lineTo(s * 0.82f, s * (top + 0.10f)); lineTo(s * 0.90f, s * (top + 0.20f))
        lineTo(s * 0.10f, s * (top + 0.20f)); lineTo(s * 0.18f, s * (top + 0.10f)); close()
    }
    drawPath(p, if (earned) Color.White else Color(0xFFF3F5F8))
    drawPath(p, Color.Black.copy(alpha = 0.10f), style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
}

private fun DrawScope.drawHexCalendar(earned: Boolean) {
    val s = size.minDimension
    val c = Offset(s / 2, s / 2)
    val r = s * 0.44f
    val hex = Path().apply {
        for (k in 0 until 6) {
            val a = Math.toRadians(-90.0 + 60.0 * k)
            val x = c.x + r * cos(a).toFloat()
            val y = c.y + r * sin(a).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    val brush = Brush.linearGradient(if (earned) EARNED_HEX else LOCKED, start = Offset.Zero, end = Offset(s, s))
    drawPath(hex, brush)
    drawPath(hex, brush, style = Stroke(s * 0.07f, join = StrokeJoin.Round))
    drawPath(hex, Color.White.copy(alpha = 0.5f), style = Stroke(s * 0.02f, join = StrokeJoin.Round))
    val accent = if (earned) Color(0xFF0E7A62) else Color(0xFFAEB5C1)
    drawRoundRect(Color.White, Offset(s * 0.27f, s * 0.30f), Size(s * 0.46f, s * 0.42f), CornerRadius(s * 0.06f))
    for (x in listOf(0.38f, 0.60f)) drawRoundRect(accent, Offset(s * x, s * 0.25f), Size(s * 0.04f, s * 0.09f), CornerRadius(s * 0.02f))
    if (earned) {
        val dot = Offset(s * 0.70f, s * 0.70f)
        drawCircle(Color(0xFFF5B83D), s * 0.085f, dot)
        drawLine(Color.White, Offset(s * 0.665f, s * 0.70f), Offset(s * 0.69f, s * 0.725f), s * 0.025f, StrokeCap.Round)
        drawLine(Color.White, Offset(s * 0.69f, s * 0.725f), Offset(s * 0.738f, s * 0.67f), s * 0.025f, StrokeCap.Round)
    }
}

private fun DrawScope.drawChecklist(earned: Boolean) {
    val s = size.minDimension
    val brush = Brush.linearGradient(if (earned) EARNED_GOLD else LOCKED, start = Offset.Zero, end = Offset(s, s))
    drawRoundRect(brush, Offset(s * 0.20f, s * 0.08f), Size(s * 0.60f, s * 0.66f), CornerRadius(s * 0.10f))
    drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(s * 0.20f, s * 0.08f), Size(s * 0.60f, s * 0.66f), CornerRadius(s * 0.10f), style = Stroke(s * 0.02f))
    val tick = if (earned) Color(0xFF0E7A62) else Color(0xFFAEB5C1)
    for (row in 0 until 3) {
        val y = s * (0.24f + 0.15f * row)
        drawLine(tick, Offset(s * 0.30f, y), Offset(s * 0.335f, y + s * 0.035f), s * 0.035f, StrokeCap.Round)
        drawLine(tick, Offset(s * 0.335f, y + s * 0.035f), Offset(s * 0.40f, y - s * 0.035f), s * 0.035f, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.95f), Offset(s * 0.47f, y), Offset(s * 0.68f, y), s * 0.04f, StrokeCap.Round)
    }
    ribbon(s, 0.68f, earned)
}

private fun DrawScope.drawTarget(earned: Boolean) {
    val s = size.minDimension
    val c = Offset(s * 0.5f, s * 0.38f)
    val ring = if (earned) Color(0xFF1FA383) else Color(0xFFC6CCD6)
    drawCircle(ring, s * 0.34f, c)
    drawCircle(Color.White, s * 0.26f, c)
    drawCircle(ring, s * 0.18f, c)
    drawCircle(Color.White, s * 0.10f, c)
    drawCircle(if (earned) Color(0xFF0E7A62) else Color(0xFFAEB5C1), s * 0.04f, c)
    val gold = if (earned) Color(0xFFF0A22A) else Color(0xFFB3BAC6)
    drawLine(Color(0xFF5B4636).copy(alpha = if (earned) 1f else 0.5f), c, Offset(s * 0.76f, s * 0.10f), s * 0.035f, StrokeCap.Round)
    val tail = Path().apply { moveTo(s * 0.76f, s * 0.10f); lineTo(s * 0.90f, s * 0.04f); lineTo(s * 0.84f, s * 0.18f); close() }
    drawPath(tail, gold)
    ribbon(s, 0.64f, earned)
}
