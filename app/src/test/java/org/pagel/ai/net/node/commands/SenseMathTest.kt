package org.pagel.ai.net.node.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseMathTest {
    private val t0 = 1_000_000L

    @Test fun `first read has nothing to compare so the state is unknown`() {
        assertEquals("unknown", MotionMath.classify(null, null, 5000, t0).state)
    }

    @Test fun `no steps over ten minutes is still`() {
        val r = MotionMath.classify(5000, t0, 5000, t0 + 600_000)
        assertEquals("still", r.state)
        assertEquals(0.0, r.stepsPerMinute!!, 0.001)
    }

    @Test fun `a steady stroll is walking`() {
        // 1000 steps in 10 minutes = 100 steps per minute
        val r = MotionMath.classify(5000, t0, 6000, t0 + 600_000)
        assertEquals("walking", r.state)
        assertEquals(100.0, r.stepsPerMinute!!, 0.001)
    }

    @Test fun `a short walk inside a long window reads as light`() {
        val r = MotionMath.classify(5000, t0, 5200, t0 + 600_000) // 20 spm
        assertEquals("light", r.state)
    }

    @Test fun `running cadence`() {
        assertEquals("running", MotionMath.classify(0, t0, 1700, t0 + 600_000).state)
    }

    @Test fun `a reboot between reads is unknown, not negative steps`() {
        assertEquals("unknown", MotionMath.classify(90_000, t0, 40, t0 + 600_000).state)
    }

    @Test fun `windows that are too short or too long are unknown`() {
        assertEquals("unknown", MotionMath.classify(5000, t0, 5100, t0 + 5_000).state)
        val old = MotionMath.classify(5000, t0, 6000, t0 + 3 * 3_600_000)
        assertEquals("unknown", old.state)
        assertNull(old.stepsPerMinute)
    }

    @Test fun `usage sums resume to pause per package`() {
        val ev = listOf(
            UsageEvent("a", UsageMath.RESUMED, 1_000), UsageEvent("a", UsageMath.PAUSED, 4_000),
            UsageEvent("b", UsageMath.RESUMED, 4_000), UsageEvent("b", UsageMath.PAUSED, 5_000),
            UsageEvent("a", UsageMath.RESUMED, 6_000), UsageEvent("a", UsageMath.PAUSED, 7_500),
        )
        val m = UsageMath.foregroundMs(ev, 0, 10_000)
        assertEquals(4_500L, m["a"])
        assertEquals(1_000L, m["b"])
    }

    @Test fun `an app still in front at the end counts until now`() {
        val m = UsageMath.foregroundMs(listOf(UsageEvent("a", UsageMath.RESUMED, 8_000)), 0, 10_000)
        assertEquals(2_000L, m["a"])
    }

    @Test fun `a pause with no resume means it was open at the window start`() {
        val m = UsageMath.foregroundMs(listOf(UsageEvent("a", UsageMath.PAUSED, 3_000)), 1_000, 10_000)
        assertEquals(2_000L, m["a"])
    }

    @Test fun `duplicate resumes do not double count and excluded packages are dropped`() {
        val ev = listOf(
            UsageEvent("a", UsageMath.RESUMED, 1_000), UsageEvent("a", UsageMath.RESUMED, 2_000),
            UsageEvent("a", UsageMath.PAUSED, 3_000), UsageEvent("me", UsageMath.RESUMED, 0),
            UsageEvent("me", UsageMath.PAUSED, 9_000),
        )
        val m = UsageMath.foregroundMs(ev, 0, 10_000, exclude = setOf("me"))
        assertEquals(2_000L, m["a"])
        assertNull(m["me"])
    }

    @Test fun `events outside the window are clipped`() {
        val m = UsageMath.foregroundMs(
            listOf(UsageEvent("a", UsageMath.RESUMED, 500), UsageEvent("a", UsageMath.PAUSED, 20_000)), 1_000, 10_000,
        )
        assertEquals(9_000L, m["a"])
    }

    @Test fun `a new app in front closes the previous one even with no pause event`() {
        val ev = listOf(
            UsageEvent("a", UsageMath.RESUMED, 1_000),
            UsageEvent("b", UsageMath.RESUMED, 2_000),
            UsageEvent("b", UsageMath.PAUSED, 5_000),
        )
        val m = UsageMath.foregroundMs(ev, 0, 10_000)
        assertEquals(1_000L, m["a"])
        assertEquals(3_000L, m["b"])
        assertTrue("the total can never exceed the window", m.values.sum() <= 10_000)
    }

    @Test fun `screen off closes whatever was open`() {
        val ev = listOf(UsageEvent("a", UsageMath.RESUMED, 1_000), UsageEvent("", UsageMath.SCREEN_OFF, 4_000))
        val m = UsageMath.foregroundMs(ev, 0, 10_000)
        assertEquals(3_000L, m["a"])
    }

    @Test fun `own app in front stops the previous app but is not counted`() {
        val ev = listOf(
            UsageEvent("a", UsageMath.RESUMED, 1_000),
            UsageEvent("me", UsageMath.RESUMED, 3_000),
            UsageEvent("me", UsageMath.PAUSED, 6_000),
        )
        val m = UsageMath.foregroundMs(ev, 0, 10_000, exclude = setOf("me"))
        assertEquals(2_000L, m["a"])
        assertNull(m["me"])
    }

    @Test fun `a messy day never adds up to more than the day`() {
        val ev = listOf(
            UsageEvent("a", UsageMath.RESUMED, 0), UsageEvent("b", UsageMath.RESUMED, 100),
            UsageEvent("c", UsageMath.RESUMED, 150), UsageEvent("a", UsageMath.RESUMED, 400),
            UsageEvent("b", UsageMath.RESUMED, 450), UsageEvent("", UsageMath.SCREEN_OFF, 700),
            UsageEvent("c", UsageMath.RESUMED, 800), UsageEvent("c", UsageMath.PAUSED, 900),
        )
        assertTrue(UsageMath.foregroundMs(ev, 0, 1_000).values.sum() <= 1_000)
    }
}
