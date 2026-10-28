package org.pagel.ai.ui.rituals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.TabIcons

/** Where Today's ritual pills lead (the shell pushes the screen onto Today's own stack). */
class RitualLinks(val sweep: () -> Unit = {}, val shutdown: () -> Unit = {}, val review: () -> Unit = {})

/**
 * Today's entry points to the rituals, as the web's loop (gtd/page.tsx): Empty your head, Close the day (a ✓ once it is
 * closed tonight), and the weekly review only while it is due (the web's today-note). Glass pills, an icon each, one
 * row that wraps at 1.5×.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RitualPillsRow(pills: RitualPills, links: RitualLinks, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassChip(TabIcons.Sparkles, "Empty your head", set = false, tint = Glass.Primary, description = "Empty your head", onClick = links.sweep)
        if (pills.shutTonight) GlassChip(TabIcons.Check, "Day closed", set = true, description = "Close the day: closed tonight", onClick = links.shutdown)
        else GlassChip(TabIcons.Moon, "Close the day", set = false, tint = Color(0xFF5B4BC4), description = "Close the day", onClick = links.shutdown)
        if (pills.reviewDue) GlassChip(TabIcons.CalendarCheck, "Weekly review", set = false, tint = Color(0xFFD9620B), description = "Weekly review is due", onClick = links.review)
    }
}
