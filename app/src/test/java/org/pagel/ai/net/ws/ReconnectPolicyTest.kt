package org.pagel.ai.net.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure, coroutine-free, clock-free unit tests — [ReconnectPolicy] is a plain function of `attempt`. */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun `first attempt is 1 second`() {
        assertEquals(1_000L, policy.nextDelayMs(0))
    }

    @Test
    fun `doubles each attempt`() {
        assertEquals(1_000L, policy.nextDelayMs(0))
        assertEquals(2_000L, policy.nextDelayMs(1))
        assertEquals(4_000L, policy.nextDelayMs(2))
        assertEquals(8_000L, policy.nextDelayMs(3))
        assertEquals(16_000L, policy.nextDelayMs(4))
    }

    @Test
    fun `caps at 30 seconds and stays capped`() {
        assertEquals(30_000L, policy.nextDelayMs(5)) // 1000 * 2^5 = 32000, capped
        assertEquals(30_000L, policy.nextDelayMs(6))
        assertEquals(30_000L, policy.nextDelayMs(100))
    }

    @Test
    fun `is pure - same attempt always yields the same delay regardless of call order or history`() {
        assertEquals(policy.nextDelayMs(3), policy.nextDelayMs(3))
        policy.nextDelayMs(50) // exercise a large attempt first
        assertEquals(1_000L, policy.nextDelayMs(0)) // no hidden state carried over from the call above
    }

    @Test
    fun `rejects a negative attempt`() {
        assertThrows(IllegalArgumentException::class.java) { policy.nextDelayMs(-1) }
    }

    @Test
    fun `custom initial and max delays are honoured`() {
        val custom = ReconnectPolicy(initialDelayMs = 500, maxDelayMs = 4_000)
        assertEquals(500L, custom.nextDelayMs(0))
        assertEquals(1_000L, custom.nextDelayMs(1))
        assertEquals(2_000L, custom.nextDelayMs(2))
        assertEquals(4_000L, custom.nextDelayMs(3)) // exactly at the cap
        assertEquals(4_000L, custom.nextDelayMs(4)) // stays capped
    }
}
