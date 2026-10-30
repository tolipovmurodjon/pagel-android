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
 * Today's ritual pills, as the web's Today shows them: Close the day in the evening (a ✓ once it is closed tonight), the
 * weekly review only while it is due (the web's today-note). At most two pills; at 1.5x font on a narrow phone the second
 * wraps to a line of its own instead of being cut at the edge ("Revie…", checker round 2 #12). Empty your head is the
 * sparkle beside the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RitualPillsRow(pills: RitualPills, links: RitualLinks, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pills.evening) {
            if (pills.shutTonight) GlassChip(TabIcons.Check, "Day closed", set = true, description = "Close the day: closed tonight", onClick = links.shutdown)
            else GlassChip(TabIcons.Moon, "Close the day", set = false, tint = Color(0xFF5B4BC4), description = "Close the day", onClick = links.shutdown)
        }
        if (pills.reviewDue) GlassChip(TabIcons.CalendarCheck, "Review", set = false, tint = Glass.Amber, description = "Weekly review is due", onClick = links.review)
    }
}
