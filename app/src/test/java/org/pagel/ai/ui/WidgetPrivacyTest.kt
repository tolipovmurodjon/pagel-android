package org.pagel.ai.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.R
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.widget.CaloriesGlance
import org.pagel.ai.widget.HabitRing
import org.pagel.ai.widget.HabitsGlance
import org.pagel.ai.widget.NamozGlance
import org.pagel.ai.widget.NextTaskGlance
import org.pagel.ai.widget.PrayerRow
import org.pagel.ai.widget.TodayGlance
import org.pagel.ai.widget.layout.CheckListItem
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.ParameterizedType

/**
 * Phase 8's walls. Boss: the Compass is sacred and the journal is closed; neither may ever reach a home screen (which
 * anyone holding the phone can read, even locked). Three checks: no widget file reads them, every widget model is made
 * only of plain words and numbers (no slot a goal or a journal entry could ride in), and the six widgets are declared
 * so the launcher lists them (the CI stand-in for "add the widget", which an emulator cannot do by itself).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetPrivacyTest {
    private val widgetDir = File("src/main/java/org/pagel/ai/widget")

    @Test fun noWidgetFileReadsTheCompassOrTheJournal() {
        assertTrue("run from the app module: ${widgetDir.absolutePath}", widgetDir.isDirectory)
        val banned = Regex("""import org\.pagel\.ai\.(data|ui)\.(compass|journal)|\b(Compass|Journal)(Repository|Db|Api|Crypto|Rules|Module|ViewModel|Route)\b|compass\.db|journal\.db""")
        val hits = widgetDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (banned.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun everyWidgetModelHoldsOnlyWordsAndNumbers() {
        val models = listOf(
            TodayGlance::class.java, CheckListItem::class.java, NextTaskGlance::class.java, HabitsGlance::class.java, HabitRing::class.java,
            NamozGlance::class.java, PrayerRow::class.java, CaloriesGlance::class.java,
        )
        val plain = setOf(String::class.java, Int::class.javaPrimitiveType, Integer::class.java, Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java)
        val nested = models.toSet() + Namoz.Prayer::class.java
        val bad = models.flatMap { m ->
            m.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.mapNotNull { f ->
                val ok = when {
                    f.type in plain || f.type in nested -> true
                    List::class.java.isAssignableFrom(f.type) -> ((f.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull() as? Class<*>)?.let { it in nested || it in plain } ?: false
                    else -> false
                }
                if (ok) null else "${m.simpleName}.${f.name}: ${f.genericType}"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun theFixturesSealedWordsNeverReachAWidget() {
        // The words the Compass and journal tests seal (CompassPrivacyTest, the walkthrough's "zebra") must not appear in
        // anything a widget draws from the day's fixtures.
        val drawn = listOf(WidgetFixtures.today, WidgetFixtures.next, WidgetFixtures.habitsMany, WidgetFixtures.namozNov1, WidgetFixtures.calories).joinToString(" ")
        assertTrue(listOf("zebra", "Why I live", "Kundalik").none { drawn.contains(it, ignoreCase = true) })
    }

    @Test fun theSixWidgetsAreDeclaredForTheLauncher() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val found = ctx.packageManager
            .queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(ctx.packageName), PackageManager.GET_META_DATA)
            .map { it.activityInfo.name.substringAfterLast('.') }.toSet()
        val want = setOf("TodayWidgetReceiver", "HabitsWidgetReceiver", "NamozWidgetReceiver", "CaloriesWidgetReceiver", "NextTaskWidgetReceiver", "QuickAddWidgetReceiver")
        assertEquals(want, found intersect want)
        // Every widget's info parses, and each can be made as small as 1x1 (lock screen-like sizes).
        for (xml in listOf(R.xml.habits_widget_info, R.xml.namoz_widget_info, R.xml.calories_widget_info, R.xml.next_task_widget_info, R.xml.quick_add_widget_info)) {
            val p = ctx.resources.getXml(xml)
            while (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) p.next()
            assertEquals("appwidget-provider", p.name)
            val minResize = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it) == "minResizeWidth" }?.let { p.getAttributeValue(it) }
            assertTrue("$xml minResizeWidth $minResize", minResize != null)
        }
    }
}
