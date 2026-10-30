package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.ui.motion.blackhole.BlackHole
import org.pagel.ai.ui.motion.blackhole.BlackHoleTiming
import org.pagel.ai.ui.motion.blackhole.BudgetWatch
import org.pagel.ai.ui.motion.blackhole.FlowTable
import org.pagel.ai.ui.motion.blackhole.FrameStep
import org.pagel.ai.ui.motion.blackhole.HoleMesh
import org.pagel.ai.ui.motion.blackhole.HoleMode
import org.pagel.ai.ui.motion.blackhole.PieceRect
import org.pagel.ai.ui.motion.blackhole.chooseMode
import org.pagel.ai.ui.motion.blackhole.holeAt
import org.pagel.ai.ui.motion.blackhole.shadeOf

/**
 * The black hole's maths (black-hole brief, "Prove it"): the flow table is monotonic and the farthest point reaches the
 * horizon exactly at the swallow's end; a frame step never passes 1/30 s; reduced motion picks the fade; two missed
 * budgets in a row give up; the two lengths are 1.3 s and ~1.9 s.
 */
class BlackHoleTimelineTest {
    private val radius = 135f // 45 dp at xxhdpi
    private val far = 2100f
    private val flow = FlowTable.build(far, radius, BlackHoleTiming.ClearAll.swallow)

    @Test fun theTableFallsWithTimeAndGrowsWithTheStartDistance() {
        for (i in 0 until flow.n) for (j in 1 until flow.n) {
            assertTrue("row $i col $j rises in time", flow.at(i, j) <= flow.at(i, j - 1) + 1e-3f)
        }
        for (j in 0 until flow.n) for (i in 1 until flow.n) {
            assertTrue("col $j row $i: a farther start is never nearer", flow.at(i, j) >= flow.at(i - 1, j) - 1e-3f)
        }
        // At τ = 0 every point is where it started.
        assertEquals(far, flow.at(flow.n - 1, 0), 0.5f)
        assertEquals(far / 2f, flow.distance(far / 2f, 0f), 2f)
    }

    @Test fun theFarthestPointReachesTheHorizonExactlyAtTheEnd() {
        val end = BlackHoleTiming.ClearAll.swallow
        val horizon = BlackHole.VANISH * radius
        assertEquals(horizon, flow.distance(far, end), 0.5f)
        // Not before: a moment earlier it is still outside.
        assertTrue(flow.distance(far, end * 0.95f) > horizon + 1f)
        // A nearer row (upper rows) is already gone by then: upper rows land first.
        assertTrue(flow.distance(far / 3f, end) < horizon)
        assertTrue(flow.distance(far / 3f, end * 0.8f) <= horizon + 1f)
    }

    @Test fun nearTheHoleAPointMovesFasterSoARowStretches() {
        // Two points of one row, 60 px apart: the gap grows as the front one nears the hole.
        val front = 600f
        val back = 660f
        val gaps = (0..40).map { k -> val t = BlackHoleTiming.ClearAll.swallow * 0.5f * k / 40f; flow.distance(back, t) - flow.distance(front, t) }
        assertEquals(60f, gaps.first(), 2f)
        assertTrue("the gap grows: $gaps", gaps.max() > 75f)
    }

    @Test fun aFrameStepNeverPassesOneThirtiethOfASecond() {
        assertEquals(1f / 30f, FrameStep.step(0.25f), 1e-6f)
        assertEquals(1f / 30f, FrameStep.step(1f / 30f), 1e-6f)
        assertEquals(0.016f, FrameStep.step(0.016f), 1e-6f)
        assertEquals(0f, FrameStep.step(-0.01f), 1e-6f)
    }

    @Test fun reducedMotionPicksTheFade() {
        assertEquals(HoleMode.Fade, chooseMode(0f))
        assertEquals(HoleMode.Full, chooseMode(1f))
        assertEquals(HoleMode.Full, chooseMode(0.5f))
    }

    @Test fun twoMissedBudgetsInARowGiveUpButTheWarmupAndOneSlowFrameDoNot() {
        val w = BudgetWatch()
        assertFalse(w.frame(0.2f))
        assertFalse(w.frame(0.2f))
        assertFalse(w.frame(0.016f))
        assertFalse(w.frame(0.04f))
        assertFalse(w.frame(0.016f))
        assertFalse(w.frame(0.04f))
        assertTrue(w.frame(0.05f))
    }

    @Test fun theTwoLengthsAreOnePointThreeAndAboutOnePointNineSeconds() {
        assertEquals(1.3f, BlackHoleTiming.Single.total, 0.01f)
        assertTrue(BlackHoleTiming.ClearAll.total in 1.85f..1.95f)
        // The swallow starts at 10 % of the opening.
        assertEquals(0.08f, BlackHoleTiming.ClearAll.swallowStart, 1e-4f)
    }

    @Test fun theHoleOpensAsACapsuleBecomesACircleAndOnlyBulgesOutward() {
        val t = BlackHoleTiming.ClearAll
        val start = holeAt(0.02f, t, radius)
        assertTrue("a capsule first", start.width > start.height * 2f)
        val open = holeAt(t.open, t, radius)
        assertEquals(2f * radius, open.width, 1f)
        assertEquals(2f * radius, open.height, 1f)
        val closed = holeAt(t.closeEnd, t, radius)
        for (k in 1..10) {
            val b = holeAt(t.closeEnd + t.bulge * k / 10f, t, radius)
            assertTrue("bulge $k is outward", b.width >= closed.width - 0.01f)
        }
        assertEquals(0f, holeAt(t.total, t, radius).alpha, 1e-4f)
    }

    @Test fun theShadeLeavesWithTheRingSoTheClosedHoleSitsInNoGreyCloud() {
        val t = BlackHoleTiming.ClearAll
        val open = holeAt(t.open, t, radius)
        val closed = holeAt(t.closeEnd + t.bulge / 2f, t, radius)
        assertTrue(shadeOf(closed) < 0.5f * shadeOf(open))
    }

    @Test fun theMeshRestsUntilTheSwallowAndLeavesNothingVisibleAfterIt() {
        val pieces = listOf(PieceRect(0f, 400f, 1080f, 616f), PieceRect(0f, 640f, 1080f, 856f))
        val mesh = HoleMesh(pieces, 540f, 180f, 36f)
        val table = FlowTable.build(mesh.maxStart, radius, BlackHoleTiming.ClearAll.swallow)
        mesh.bind(table)
        // 1080 x 216 px at a 36 px cell: 30 x 6 cells, 31 x 7 points per piece.
        assertEquals(2 * 31 * 7, mesh.vertexCount)
        mesh.update(-0.01f, table, radius)
        for (v in 0 until mesh.vertexCount) {
            assertEquals(mesh.tex[2 * v], mesh.pos[2 * v], 1e-3f)
            assertEquals(255, mesh.sharp[v] ushr 24)
        }
        assertEquals("no blurred triangles at rest", 0, mesh.softCount)
        // Mid-swallow the blurred copy draws only the heated triangles near the horizon, not the whole mesh.
        mesh.update(BlackHoleTiming.ClearAll.swallow * 0.6f, table, radius)
        assertTrue("some blurred triangles: ${mesh.softCount}", mesh.softCount > 0)
        assertTrue("only some: ${mesh.softCount} of ${mesh.indices.size}", mesh.softCount < mesh.indices.size)
        assertEquals(0, mesh.softCount % 3)
        mesh.update(BlackHoleTiming.ClearAll.swallow + 0.01f, table, radius)
        for (v in 0 until mesh.vertexCount) {
            assertEquals("point $v is gone", 0, mesh.sharp[v] ushr 24)
            assertEquals(0, mesh.soft[v] ushr 24)
        }
    }
}
