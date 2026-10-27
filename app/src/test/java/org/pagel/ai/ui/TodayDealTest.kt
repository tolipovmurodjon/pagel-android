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
        assertEquals("Call the bank", headline("Call the bank. They close at 5."))
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
}
