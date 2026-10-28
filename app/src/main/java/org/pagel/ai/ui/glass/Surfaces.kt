package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kyant.shapes.RoundedRectangle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.Backdrop
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The page behind everything: near-white lit by soft mint light from the corners, as the web's body background
 * (four radial gradients). Drawn once per size change in the draw phase, so it costs nothing while scrolling.
 */
/** The page's colour clouds, captured on their own layer so glass panes can bend them (a pane never bends itself). */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val page = rememberLayerBackdrop()
    Box(modifier) {
        Box(
            Modifier.matchParentSize()
                .layerBackdrop(page)
                .background(Glass.Page)
                .drawBehind {
                fun glow(x: Float, y: Float, r: Float, c: Color) = drawRect(
                    Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(size.width * x, size.height * y), radius = size.maxDimension * r),
                )
                glow(0.06f, 0.04f, 0.34f, Color(0xFF3DC98C).copy(alpha = 0.20f))
                glow(0.92f, 0.06f, 0.30f, Glass.Aurora2.copy(alpha = 0.45f))
                // The two bottom clouds sit under the glass bar: it always has colour to bend, on every screen
                // (colour theory #5: 3-4 clouds of different hues; the bottom-left one turns to teal).
                glow(0.96f, 0.96f, 0.42f, Glass.Aurora1.copy(alpha = 0.52f))
                glow(0.10f, 0.98f, 0.34f, Color(0xFF2BB5A8).copy(alpha = 0.40f))
                },
        )
        val scope = this
        CompositionLocalProvider(LocalPageBackdrop provides page) { scope.content() }
    }
}

/**
 * Every pane is liquid glass (Boss 2026-10-27: "everything liquid glass, our colour theory mint"): it bends the page's
 * mint clouds, carries the web card Boss scored 8/10 (a mint glow in the top-left corner, a white-to-mint rim), and
 * keeps text ink-dark on a light surface (contrast ≥ 4.5:1). Off a glass page (tests, previews) it falls back to a pane.
 */
@Composable
fun Pane(modifier: Modifier = Modifier, radius: Dp = 24.dp, content: @Composable BoxScope.() -> Unit) {
    val page = LocalPageBackdrop.current
    val shape = RoundedCornerShape(radius)
    val surface = if (page == null) {
        Modifier.shadow(18.dp, shape, ambientColor = Glass.Ink.copy(alpha = 0.10f), spotColor = Glass.Ink.copy(alpha = 0.16f))
            .clip(shape).background(Color.White.copy(alpha = 0.78f)).background(Glass.PaneTint)
    } else if (!GlassStage.allows(GlassStage.PANES)) {
        // A cold start's first frame: the same surface without the backdrop effects (GlassStage).
        Modifier.clip(shape).drawBehind { paneSurface() }
    } else {
        Modifier.drawBackdrop(
            backdrop = page,
            shape = { RoundedRectangle(radius) },
            // No blur on panes: it changes nothing visible over smooth clouds but costs a full pass per frame (audit #10;
            // the first frame passed 5 s on CI's software GPU = ANR). Bar and capsules keep their blur.
            effects = { vibrancy(); lens(10f.dp.toPx(), 18f.dp.toPx()) },
            highlight = { Highlight.Default },
            shadow = { Shadow(alpha = 0.45f) },
            onDrawSurface = { paneSurface() },
        )
    }
    Box(modifier.then(surface).border(1.25.dp, PaneRim, shape), content = content)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.paneSurface() {
    // Clear glass, not a white card (checker 2026-10-27): ~30 % white lets the clouds through; the rim and the
    // shadow carry the edge. Muted text stays ≥ 4.5:1 on the darkest cloud (checked with cr.py on CI shots).
    drawRect(Color.White.copy(alpha = 0.32f))
    drawRect(Brush.radialGradient(listOf(Glass.Aurora1.copy(alpha = 0.30f), Color.Transparent), center = Offset.Zero, radius = size.minDimension * 0.9f))
    // A second, softer light from the bottom-right: over the page's white middle a tall pane's foot read as
    // plain white card (checker 2026-10-28, today.png, project-folds-open.png). Light mint only, text stays ink.
    drawRect(Brush.radialGradient(listOf(Glass.Aurora2.copy(alpha = 0.34f), Color.Transparent), center = Offset(size.width, size.height), radius = size.minDimension * 1.1f))
}

/**
 * The web's pane rim (globals.css `.pane::after`): a conic light from 315°: bright white at the top-left, fading, a
 * mint band at 45 %, bright again at the end. Compose's sweep starts at 3 o'clock, so it is turned 225° to match.
 */
val PaneRim: Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val c = Offset(size.width / 2f, size.height / 2f)
        return SweepGradientShader(
            c,
            listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.25f), Glass.Aurora1.copy(alpha = 0.55f), Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.95f)),
            listOf(0f, 0.22f, 0.45f, 0.70f, 1f),
        ).apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(225f, c.x, c.y) }) }
    }
}

/**
 * Rows of soft grey bars in the shape of what is coming, for the very first start only (Room still empty): never a
 * spinner (Boss 2026-10-27). They breathe gently so the screen reads as alive, not stuck.
 */
@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier) {
    val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.45f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe",
    )
    androidx.compose.foundation.layout.Column(modifier.clearAndSetSemantics {}) {
        repeat(count) { i ->
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).graphicsLayer { alpha = breathe }.background(Glass.Ink.copy(alpha = 0.07f), RoundedCornerShape(9.dp)))
                androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
                Box(Modifier.height(14.dp).fillMaxWidth(if (i % 2 == 0) 0.62f else 0.44f).graphicsLayer { alpha = breathe }.background(Glass.Ink.copy(alpha = 0.07f), RoundedCornerShape(7.dp)))
            }
        }
    }
}

/** A project's lit gradient tile with its white line icon (brain rules/gradient-project-icons, colour theory #19). */
@Composable
fun ProjectTile(project: String, color: String?, title: String, size: Dp = 36.dp) {
    val (a, b) = TileColors.stops(project, color)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(a, b)))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(TabIcons.forProject(project), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
