package org.pagel.ai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.pagel.ai.MainActivity
import org.pagel.ai.R
import org.pagel.ai.ui.glass.Emoji
import timber.log.Timber

// Phase 8's home-screen widgets (Boss 2026-10-28: "widgets better, more"): Habits rings, Namoz, Calories today, Next
// task, Quick add, beside "Today's 3" (TodayWidget.kt). Each draws one model from WidgetModels.kt in the white-mint
// glass (WidgetLook.kt), at its exact size (SizeMode.Exact, skill glance-canonical-layouts): a 1x1 shows one number,
// a short 2x1 strip one line, anything bigger the whole widget. Dark mode swaps the drawables (drawable-night) and the
// day/night colours. Images are bundled resources only (no bitmaps in provideContent, skill
// glance-appwidget-updates-and-memory), so nothing counts against Android 17's widget bitmap cap.

/** How much room a widget has: 1x1, a short strip (2x1, 3x1, 4x1), or the full layout. */
enum class Fit { Tiny, Strip, Full }

fun fitOf(size: DpSize): Fit = when {
    size.width < 110.dp -> Fit.Tiny
    size.height < 100.dp -> Fit.Strip
    else -> Fit.Full
}

/**
 * The widgets' shared base: exact sizes, and a plain fallback (no data in it, one tap opens PAGEL) if a composition
 * ever fails, so a home screen never shows "Can't load widget" for long.
 */
abstract class PagelWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        Timber.w("widget %s failed: %s", javaClass.simpleName, throwable.javaClass.simpleName)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallbackViews(context))
    }
}

internal fun fallbackViews(context: Context): RemoteViews =
    RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
        setTextViewText(android.R.id.text1, context.getString(R.string.widget_fallback))
        setOnClickPendingIntent(
            android.R.id.text1,
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

@Composable
private fun Header(title: String, count: String?, onCount: Action? = null) {
    Row(GlanceModifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, GlanceModifier.defaultWeight(), style = textStyle(TitleSize, bold = true), maxLines = 1)
        if (count != null) {
            val m = if (onCount != null) GlanceModifier.clickable(onCount) else GlanceModifier
            Text(count, m, style = textStyle(TitleSize, WidgetInk.Mint, bold = true), maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Habits rings

/** One habit's ring; a tap ticks it (a count habit: one tap of its times), a full ring opens Habits. */
@Composable
private fun HabitCell(r: HabitRing, ring: Dp, label: Boolean) {
    val tap = if (r.done) WidgetOpen.habits else actionRunCallback<TapHabitFromWidget>(actionParametersOf(TapHabitFromWidget.HabitId to r.id))
    Column(GlanceModifier.width(ring + 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(r.twelfths, ring, GlanceModifier.clickable(tap)) {
            Image(ImageProvider(Emoji.res(r.emoji)), contentDescription = "${r.title}, ${r.taps} of ${r.times}", modifier = GlanceModifier.size(ring * 0.55f))
        }
        if (label) Text(r.title, style = textStyle(SmallSize, WidgetInk.Muted, center = true), maxLines = 1)
    }
}

@Composable
fun HabitsContent(g: HabitsGlance) {
    val size = LocalSize.current
    when (fitOf(size)) {
        Fit.Tiny -> GlassFrame(onClick = WidgetOpen.habits) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Ring(twelfthsOf(g.done, g.total), 56.dp) { Text(g.count, style = textStyle(BodySize, bold = true, center = true)) }
            }
        }
        Fit.Strip -> GlassFrame(padding = 8.dp) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val fit = ((size.width - 64.dp) / 52.dp).toInt().coerceIn(1, 6)
                g.rings.take(fit).forEach { HabitCell(it, 40.dp, label = false) }
                Spacer(GlanceModifier.defaultWeight())
                Text(g.count, GlanceModifier.clickable(WidgetOpen.habits), style = textStyle(TitleSize, WidgetInk.Mint, bold = true))
            }
        }
        Fit.Full -> GlassFrame {
            Header(LocalContextString(R.string.widget_habits_title), g.count, WidgetOpen.habits)
            Spacer(GlanceModifier.height(8.dp))
            if (g.rings.isEmpty()) {
                Text(LocalContextString(R.string.widget_habits_empty), GlanceModifier.clickable(WidgetOpen.habits), style = textStyle(BodySize, WidgetInk.Muted))
            } else {
                val perRow = ((size.width - 24.dp) / 60.dp).toInt().coerceIn(1, 6)
                val rows = ((size.height - 56.dp) / 72.dp).toInt().coerceIn(1, 3)
                g.rings.take(perRow * rows).chunked(perRow).forEach { line ->
                    Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.Start) {
                        line.forEach { HabitCell(it, 44.dp, label = true) }
                    }
                }
            }
        }
    }
}

class HabitsWidget : PagelWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val src = parts(context).habits()
        val first = withContext(Dispatchers.IO) { src.habits().first() }
        provideContent {
            val g by remember { src.habits() }.collectAsState(first)
            HabitsContent(g)
        }
    }
}

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitsWidget()
}

/** A ring's or a prayer's tap: the Habits tab's own tick (Room, then the outbox), the finish feel, both widgets redrawn. */
class TapHabitFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[HabitId] ?: return
        if (parts(context).habits().tap(id)) WidgetHaptics.finish(context)
        HabitsWidget().updateAll(context)
        NamozWidget().updateAll(context)
    }

    companion object {
        val HabitId = ActionParameters.Key<String>("habitId")
    }
}

// ---------------------------------------------------------------- Namoz

@Composable
private fun PrayerLine(p: PrayerRow, next: Boolean, counting: Boolean, height: Dp, times: Boolean) {
    val m = GlanceModifier.fillMaxWidth().height(height).padding(horizontal = 8.dp)
    Row(if (next) m.background(ImageProvider(R.drawable.widget_pill)) else m, verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(Emoji.res(p.emoji)), contentDescription = null, modifier = GlanceModifier.size(20.dp))
        Spacer(GlanceModifier.width(8.dp))
        Text(p.name, GlanceModifier.defaultWeight(), style = textStyle(BodySize, if (next) WidgetInk.OnPill else WidgetInk.Text, bold = next), maxLines = 1)
        if (times) Text(p.time, style = textStyle(SmallSize, if (next) WidgetInk.OnPill else WidgetInk.Muted), maxLines = 1)
        if (counting && p.habitId != null) {
            Spacer(GlanceModifier.width(8.dp))
            val icon = if (p.done) R.drawable.sample_checked_circle_icon else R.drawable.sample_circle_icon
            val tap = if (p.done) WidgetOpen.habits else actionRunCallback<TapHabitFromWidget>(actionParametersOf(TapHabitFromWidget.HabitId to p.habitId))
            Image(
                ImageProvider(icon), contentDescription = if (p.done) "${p.name}, prayed" else "${p.name}, tick",
                modifier = GlanceModifier.size(24.dp).clickable(tap), colorFilter = androidx.glance.ColorFilter.tint(WidgetInk.Mint),
            )
        }
    }
}

@Composable
fun NamozContent(g: NamozGlance) {
    val size = LocalSize.current
    val next = g.next?.let { g.rows[it] }
    val right = when {
        !g.counting -> LocalContextString(R.string.widget_namoz_from)
        !g.tracked -> LocalContextString(R.string.widget_namoz_set_up)
        else -> g.count
    }
    when (fitOf(size)) {
        Fit.Tiny, Fit.Strip -> GlassFrame(padding = 8.dp, onClick = WidgetOpen.habits) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (next != null) {
                    Image(ImageProvider(Emoji.res(next.emoji)), contentDescription = null, modifier = GlanceModifier.size(28.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(next.name, style = textStyle(BodySize, bold = true), maxLines = 1)
                        Text(next.from, style = textStyle(SmallSize, WidgetInk.Muted), maxLines = 1)
                    }
                } else {
                    Text(g.count, GlanceModifier.defaultWeight(), style = textStyle(BigNumberSize, WidgetInk.Mint, bold = true))
                }
                if (fitOf(size) == Fit.Strip && g.counting && g.tracked) Text(g.count, style = textStyle(TitleSize, WidgetInk.Mint, bold = true))
            }
        }
        Fit.Full -> GlassFrame {
            Header(LocalContextString(R.string.widget_namoz_title), right, if (g.tracked) null else WidgetOpen.habits)
            Spacer(GlanceModifier.height(4.dp))
            val line = ((size.height - 52.dp) / 5).coerceIn(20.dp, 36.dp)
            g.rows.forEachIndexed { i, p -> PrayerLine(p, next = i == g.next, counting = g.counting, height = line, times = size.width >= 150.dp) }
        }
    }
}

class NamozWidget : PagelWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val src = parts(context).habits()
        val first = withContext(Dispatchers.IO) { src.namoz().first() }
        provideContent {
            val g by remember { src.namoz() }.collectAsState(first)
            NamozContent(g)
        }
    }
}

class NamozWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamozWidget()
}

// ---------------------------------------------------------------- Calories today

@Composable
fun CaloriesContent(g: CaloriesGlance?) {
    val size = LocalSize.current
    val camera = LocalContextString(R.string.widget_calories_log)
    when (fitOf(size)) {
        Fit.Tiny -> GlassFrame(onClick = WidgetOpen.food) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Ring(g?.twelfths ?: 0, 60.dp) {
                    if (g != null) Text(CaloriesGlance.grouped(g.eaten), style = textStyle(SmallSize, bold = true, center = true), maxLines = 1)
                    else Image(ImageProvider(R.drawable.widget_icon_camera), contentDescription = camera, modifier = GlanceModifier.size(24.dp), colorFilter = androidx.glance.ColorFilter.tint(WidgetInk.Mint))
                }
            }
        }
        Fit.Strip -> GlassFrame(padding = 8.dp) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Ring(g?.twelfths ?: 0, 40.dp)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight().clickable(WidgetOpen.habits)) {
                    Text(g?.text ?: LocalContextString(R.string.widget_calories_none), style = textStyle(BodySize, bold = true), maxLines = 1)
                    g?.left?.let { Text(it, style = textStyle(SmallSize, WidgetInk.Muted), maxLines = 1) }
                }
                RoundButton(R.drawable.widget_icon_camera, camera, WidgetOpen.food)
            }
        }
        Fit.Full -> GlassFrame {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(LocalContextString(R.string.widget_calories_title), GlanceModifier.defaultWeight(), style = textStyle(TitleSize, bold = true), maxLines = 1)
                RoundButton(R.drawable.widget_icon_camera, camera, WidgetOpen.food, size = 40.dp)
            }
            Box(GlanceModifier.fillMaxWidth().defaultWeight().clickable(WidgetOpen.habits), contentAlignment = Alignment.Center) {
                val ring = minOf(size.width - 48.dp, size.height - 104.dp).coerceIn(48.dp, 96.dp)
                Ring(g?.twelfths ?: 0, ring) {
                    Text(g?.let { CaloriesGlance.grouped(it.eaten) } ?: "–", style = textStyle(if (ring >= 72.dp) BigNumberSize else BodySize, bold = true, center = true), maxLines = 1)
                }
            }
            Text(g?.text ?: LocalContextString(R.string.widget_calories_none), GlanceModifier.fillMaxWidth(), style = textStyle(BodySize, bold = true, center = true), maxLines = 1)
            Text(g?.left ?: LocalContextString(R.string.widget_calories_hint), GlanceModifier.fillMaxWidth(), style = textStyle(SmallSize, WidgetInk.Muted, center = true), maxLines = 1)
        }
    }
}

class CaloriesWidget : PagelWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val src = parts(context).calories()
        val first = withContext(Dispatchers.IO) { src.today().first() }
        provideContent {
            val g by remember { src.today() }.collectAsState(first)
            // Draw the saved copy first, then ask pagel.uz (offline: the saved copy stays, no spinner).
            LaunchedEffect(Unit) { src.refresh() }
            CaloriesContent(g)
        }
    }
}

class CaloriesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaloriesWidget()
}

// ---------------------------------------------------------------- Next task

@Composable
fun NextTaskContent(g: NextTaskGlance?) {
    val size = LocalSize.current
    GlassFrame(onClick = g?.let { WidgetOpen.task(it.id) } ?: WidgetOpen.today) {
        val fit = fitOf(size)
        if (g == null) {
            Text(LocalContextString(R.string.widget_next_none), style = textStyle(BodySize, WidgetInk.Muted), maxLines = 2)
        } else {
            if (fit != Fit.Tiny) Text(g.project, style = textStyle(SmallSize, WidgetInk.Mint, bold = true), maxLines = 1)
            Text(g.title, style = textStyle(if (fit == Fit.Tiny) SmallSize else BodySize, bold = true), maxLines = if (fit == Fit.Full) 2 else 1)
            val step = g.step
            if (step != null && fit != Fit.Tiny) Text(LocalContextString(R.string.widget_next_step, step), style = textStyle(SmallSize, WidgetInk.Muted), maxLines = 1)
        }
    }
}

class NextTaskWidget : PagelWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val p = parts(context)
        val flow = nextTaskFlow(p.dao())
        val first = withContext(Dispatchers.IO) { flow.first() }
        provideContent {
            val g by remember { flow }.collectAsState(first)
            NextTaskContent(g)
        }
    }
}

class NextTaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextTaskWidget()
}

// ---------------------------------------------------------------- Quick add

@Composable
fun QuickAddContent() {
    val size = LocalSize.current
    val add = LocalContextString(R.string.widget_add_task)
    val voice = LocalContextString(R.string.widget_add_voice)
    if (fitOf(size) == Fit.Tiny) {
        GlassFrame(padding = 8.dp, onClick = WidgetOpen.add) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { RoundButton(R.drawable.widget_icon_plus, add, WidgetOpen.add) }
        }
    } else GlassFrame(padding = 8.dp) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                GlanceModifier.defaultWeight().height(48.dp).background(ImageProvider(R.drawable.widget_field)).clickable(WidgetOpen.add).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(ImageProvider(R.drawable.widget_icon_plus), contentDescription = null, modifier = GlanceModifier.size(20.dp), colorFilter = androidx.glance.ColorFilter.tint(WidgetInk.Mint))
                Spacer(GlanceModifier.width(8.dp))
                Text(add, style = textStyle(BodySize, WidgetInk.Muted), maxLines = 1)
            }
            Spacer(GlanceModifier.width(8.dp))
            RoundButton(R.drawable.widget_icon_mic, voice, WidgetOpen.voice)
        }
    }
}

class QuickAddWidget : PagelWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuickAddContent() }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

/** A string resource inside a Glance composition (Glance's own LocalContext). */
@Composable
internal fun LocalContextString(id: Int, vararg args: Any): String = androidx.glance.LocalContext.current.getString(id, *args)
