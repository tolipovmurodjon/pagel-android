package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.today.DueTone
import org.pagel.ai.ui.today.dealToday
import org.pagel.ai.ui.today.dueLabel
import org.pagel.ai.ui.today.headline
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.widget.rows
import java.time.LocalDate

/** Today's rules, the same as the web's today-list.tsx and lib/headline.ts, so phone and pagel.uz never disagree. */
class TodayDealTest {
    private fun task(id: String, status: String = "open") = TaskEntity(
        id = id, project = "pagel", phase = "Now", sort = 1.0, text = "Task $id", title = null, status = status, priority = 1,
        star = true, due = null, every = null, doneWhen = null, doneAt = null, updatedAt = "", serverVersion = 1, lastModified = 0,
    )

    @Test fun showsThreeAtATimeAndCountsTheRest() {
        val d = dealToday(listOf("a", "b", "c", "d", "e").map { task(it) }, finishing = emptySet())
        assertEquals(listOf("a", "b", "c"), d.visible.map { it.id })
        assertEquals(2, d.waiting)
        assertFalse(d.allDone)
    }

    @Test fun aFinishingTaskKeepsItsSlotUntilItsFoldEnds() {
        val rows = listOf(task("a", "done"), task("b"), task("c"), task("d"))
        assertEquals(listOf("a", "b", "c"), dealToday(rows, finishing = setOf("a")).visible.map { it.id })
        // The fold ended: the next task is dealt into the free slot.
        assertEquals(listOf("b", "c", "d"), dealToday(rows, finishing = emptySet()).visible.map { it.id })
    }

    @Test fun everythingDoneIsAllDoneNotEmpty() {
        val d = dealToday(listOf(task("a", "done"), task("b", "done")), finishing = emptySet())
        assertTrue(d.visible.isEmpty())
        assertTrue(d.allDone)
        assertFalse(dealToday(emptyList(), emptySet()).allDone)
    }

    @Test fun headlineIsTheFirstSentenceLikeTheWeb() {
        // The web keeps the sentence's own full stop (checked against lib/headline.ts in node, 2026-10-27).
        assertEquals("Call the bank.", headline("Call the bank. They close at 5."))
        assertEquals("Read the paper", headline("Read the [[library/all/karpathy-explain-ladder|paper]] → notes"))
        assertEquals("Fix the bar", headline("**Fix** the bar (Boss said so)"))
        val long = "x".repeat(120)
        assertEquals("x".repeat(88) + "…", headline(long))
    }

    @Test fun dueLabelsMatchTheWeb() {
        val today = LocalDate.of(2026, 10, 27) // a Tuesday
        assertEquals("Today 15:00" to DueTone.TODAY, dueLabel("2026-10-27 15:00", today)!!.let { it.text to it.tone })
        assertEquals("Tomorrow" to DueTone.SOON, dueLabel("2026-10-28", today)!!.let { it.text to it.tone })
        assertEquals(DueTone.LATE, dueLabel("2026-10-20", today)!!.tone)
        assertEquals("Friday 30 Oct", dueLabel("2026-10-30", today)!!.text)
        assertEquals("20 Nov" to DueTone.NONE, dueLabel("2026-11-20", today)!!.let { it.text to it.tone })
        assertEquals(null, dueLabel(null, today))
    }

    @Test fun todayRowsSayNothingForAFutureDaySinceForAMissedOneAndTheTimeForToday() {
        val today = LocalDate.of(2026, 10, 27) // a Tuesday
        assertEquals(null, org.pagel.ai.ui.today.todayMeta("2026-10-28", today))
        assertEquals(null, org.pagel.ai.ui.today.todayMeta("2026-10-27", today))
        assertEquals("since Sat", org.pagel.ai.ui.today.todayMeta("2026-10-24", today)!!.text)
        assertEquals("since 12 Oct", org.pagel.ai.ui.today.sinceWord("2026-10-12", today))
        assertEquals("15:00", org.pagel.ai.ui.today.todayMeta("2026-10-27 15:00", today)!!.text)
    }

    @Test fun theNextTimedThingIsTheFirstOpenOneWithATimeToday() {
        val today = LocalDate.of(2026, 10, 27)
        fun row(id: String, due: String?) = org.pagel.ai.ui.today.TodayRow(id, "T$id", 1, due, null, "p", "P", null, false)
        val all = listOf(row("a", null), row("b", "2026-10-28 09:00"), row("c", "2026-10-27 14:00"), row("d", "2026-10-27 16:00"))
        val (r, time) = org.pagel.ai.ui.today.nextTimed(all, today)!!
        assertEquals("c", r.id)
        assertEquals("14:00", time)
        assertEquals(null, org.pagel.ai.ui.today.nextTimed(all.take(2), today))
    }

    @Test fun theWidgetShowsTheSameThreeWithWhenAndProject() {
        val tasks = listOf("a", "b", "c", "d").map { task(it) }.let { l -> listOf(l[0].copy(due = "2026-10-27 15:00", title = "Ship it")) + l.drop(1) }
        val items = rows(tasks, listOf(ProjectEntity("pagel", "PAGEL — the plan", null, 0, 4)), LocalDate.of(2026, 10, 27))
        assertEquals(listOf("a", "b", "c"), items.map { it.key })
        assertEquals("Ship it", items[0].title)
        assertEquals("Today 15:00 · PAGEL", items[0].supportingText)
        assertEquals("PAGEL", items[1].supportingText)
    }
}
