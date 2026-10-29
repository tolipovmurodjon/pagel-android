package org.pagel.ai.widget

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.pagel.ai.MainActivity
import org.pagel.ai.R
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.shell.OpenRequests

// The widgets' white-mint "glass" (Phase 8). Glance draws RemoteViews, so no blur or lens: the glass is a drawable —
// a white card, an aurora glow lit from the top left, a 1.25 dp mint rim (res/drawable/widget_glass.xml, and a deep
// ink card in drawable-night for dark mode). Text stays ink on white (≥ 4.5:1); mint is only the line and the ring.

/** Day/night colours (RULES.md: mint thin line #1C7E69, label on mint #0B5C45; ink text). */
internal object WidgetInk {
    val Text: ColorProvider = androidx.glance.color.ColorProvider(day = Glass.Ink, night = Color.White)
    val Muted: ColorProvider = androidx.glance.color.ColorProvider(day = Glass.Muted, night = Color(0xFFB8C0D6))
    val Mint: ColorProvider = androidx.glance.color.ColorProvider(day = Glass.Primary, night = Glass.Aurora1)
    val OnPill: ColorProvider = androidx.glance.color.ColorProvider(day = Glass.PrimaryInk, night = Glass.Aurora2)
}

internal fun textStyle(size: TextUnit, color: ColorProvider = WidgetInk.Text, bold: Boolean = false, center: Boolean = false) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = if (center) TextAlign.Center else TextAlign.Start,
)

internal val TitleSize = 14.sp
internal val BodySize = 14.sp
internal val SmallSize = 12.sp
internal val BigNumberSize = 24.sp

/** The widget's whole surface: the glass card, the launcher's corner radius on Android 12+, the inside padding. */
@Composable
internal fun GlassFrame(
    modifier: GlanceModifier = GlanceModifier,
    padding: Dp = 12.dp,
    onClick: Action? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier.fillMaxSize()
        .background(ImageProvider(R.drawable.widget_glass), contentScale = ContentScale.FillBounds)
        .appWidgetBackground()
    if (Build.VERSION.SDK_INT >= 31) m = m.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    if (onClick != null) m = m.clickable(onClick)
    Box(m.padding(padding)) {
        Column(GlanceModifier.fillMaxSize()) { content() }
    }
}

/**
 * A ring as Glance can draw it: a pale track and a mint arc of [twelfths] (bundled vector arcs, res/drawable/widget_ring_*),
 * with [center] on top (an emoji, a number). Resource images only: nothing counts against the launcher's bitmap cap.
 */
@Composable
internal fun Ring(twelfths: Int, size: Dp, modifier: GlanceModifier = GlanceModifier, center: @Composable () -> Unit = {}) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.widget_ring_track), contentDescription = null, modifier = GlanceModifier.size(size))
        if (twelfths > 0) Image(ImageProvider(ringArc(twelfths)), contentDescription = null, modifier = GlanceModifier.size(size))
        center()
    }
}

@DrawableRes
internal fun ringArc(twelfths: Int): Int = RING_ARCS[twelfths.coerceIn(1, 12) - 1]

private val RING_ARCS = intArrayOf(
    R.drawable.widget_ring_1, R.drawable.widget_ring_2, R.drawable.widget_ring_3, R.drawable.widget_ring_4,
    R.drawable.widget_ring_5, R.drawable.widget_ring_6, R.drawable.widget_ring_7, R.drawable.widget_ring_8,
    R.drawable.widget_ring_9, R.drawable.widget_ring_10, R.drawable.widget_ring_11, R.drawable.widget_ring_12,
)

/** A round mint-soft button with a lucide icon (48 dp target; RULES.md). */
@Composable
internal fun RoundButton(@DrawableRes icon: Int, label: String, onClick: Action, size: Dp = 48.dp) {
    Box(
        GlanceModifier.size(size).background(ImageProvider(R.drawable.widget_button)).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), contentDescription = label, modifier = GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(WidgetInk.Mint))
    }
}

/** What a widget tap opens in the app (MainActivity → OpenRequests → the shell). */
object WidgetOpen {
    val Key = ActionParameters.Key<String>(OpenRequests.EXTRA)

    fun action(name: String): Action = actionStartActivity<MainActivity>(actionParametersOf(Key to name))
    fun task(id: String): Action = action(OpenRequests.TASK_PREFIX + id)
    val add: Action get() = action(OpenRequests.ADD)
    val voice: Action get() = action(OpenRequests.VOICE)
    val food: Action get() = action(OpenRequests.FOOD)
    val habits: Action get() = action(OpenRequests.HABITS)
    val today: Action get() = action(OpenRequests.TODAY)
}

/**
 * The finish feel from the home screen (the app's tick plays HapticFeedbackType.Confirm). The widget has no view to
 * vibrate, so the callback asks the Vibrator for a short click. Android may stay silent for an app in the background;
 * the tick itself never depends on it.
 */
internal object WidgetHaptics {
    fun finish(ctx: Context) {
        runCatching {
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }
}
