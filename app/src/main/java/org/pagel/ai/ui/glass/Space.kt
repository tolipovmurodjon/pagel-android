package org.pagel.ai.ui.glass

import androidx.compose.ui.unit.dp

/**
 * The spacing scale (docs/RULES.md "Layout", Boss 2026-10-28: "don't make that trash thing ever again"). New code uses
 * these, never a raw off-scale dp; the roles name the gaps every screen shares.
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** The screen's side gutter (panes sit this far from the edge). */
    val gutter = lg
    /** From one pane to the next. */
    val paneGap = md
    /** From a header or a counter row to its first card. */
    val headerToCard = md
}
