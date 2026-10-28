package org.pagel.ai.ui.rituals

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.TabIcons

/** Where Today's ritual pills lead (the shell pushes the screen onto Today's own stack). */
class RitualLinks(
    val sweep: () -> Unit = {},
    val shutdown: () -> Unit = {},
    val review: () -> Unit = {},
    /** The second brain's one search box (Phase 6b), reached from Today's header; null hides the button. */
    val search: (() -> Unit)? = null,
)

/**
 * Today's ritual pills, as the web's Today shows them: Close the day in the evening (a ✓ once it is closed tonight), the
 * weekly review only while it is due (the web's today-note). One row that never wraps (three stacked rows pushed the
 * stars under the + on a 320 dp phone, checker P5 #4); Empty your head is the sparkle beside the title.
 */
@Composable
fun RitualPillsRow(pills: RitualPills, links: RitualLinks, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pills.evening) {
            if (pills.shutTonight) GlassChip(TabIcons.Check, "Day closed", set = true, description = "Close the day: closed tonight", onClick = links.shutdown)
            else GlassChip(TabIcons.Moon, "Close the day", set = false, tint = Color(0xFF5B4BC4), description = "Close the day", onClick = links.shutdown)
        }
        if (pills.reviewDue) GlassChip(TabIcons.CalendarCheck, "Review", set = false, tint = Color(0xFFBE123C), description = "Weekly review is due", onClick = links.review)
    }
}
