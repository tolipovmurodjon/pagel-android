package org.pagel.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * AnchorService and WakeService are both foreground services that run simultaneously when
 * hands-free is armed. If they share a notification id, `NotificationManager.notify()` from one
 * clobbers the other's ongoing notification (see WakeService.updateNotice()). Reads the private
 * `NOTIF_ID` constants via reflection rather than opening a real notification channel — neither
 * class's methods are invoked, so this needs no Robolectric/instrumentation.
 */
class NotificationIdTest {

    @Test
    fun `AnchorService and WakeService use different foreground notification ids`() {
        val anchorId = notifId(AnchorService::class.java)
        val wakeId = notifId(WakeService::class.java)
        assertNotEquals(
            "AnchorService and WakeService both run as foreground services when hands-free is " +
                "armed; a shared notification id means one service's notify() clobbers the other's.",
            anchorId,
            wakeId,
        )
        assertEquals(4201, anchorId)
        assertEquals(4202, wakeId)
    }

    private fun notifId(clazz: Class<*>): Int =
        clazz.getDeclaredField("NOTIF_ID").apply { isAccessible = true }.getInt(null)
}
