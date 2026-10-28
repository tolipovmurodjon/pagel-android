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
    /** From the screen edge to header text and free hints. */
    val textEdge = xl
    /** Inside a pane, left and right (spacing audit #15: StarCard 20, RitualPane 18 and EveningRow 16 became one). */
    val paneInset = lg
    /** Inside a pane, top and bottom. */
    val paneInsetV = md
    /** Below the status bar, above a screen's overline. */
    val headerTop = md
    /** Overline → title → subtitle. */
    val titleToSub = xs
    /** An icon and its words; chip to chip, button to button. */
    val iconText = sm
    val chipGap = sm
    /** A list row's leading column (the ring) and where its text and divider start. */
    val rowLead = 6.dp
    val rowText = 54.dp
    /** Touch target and a list row's least height. */
    val touch = 48.dp
    val row = 56.dp
    /** A sheet's title row, with or without its Done pill (spacing audit #18). */
    val sheetTitle = 48.dp
    /** A reserved one-line message slot (an error, a nudge, a word counter): 6 + 16. */
    val messageSlot = 22.dp
}
