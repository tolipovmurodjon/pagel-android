package org.pagel.ai.ui

import android.media.AudioAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.ui.today.Ding
import org.robolectric.annotation.Config

/** Boss 2026-10-31: the tick was silent on a silent phone. It must use the media stream; "Silent mode" in More is the only mute. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TickSoundTest {
    @Test fun `the tick and the day-cleared chord play on the media stream`() {
        val a = Ding.attributes()
        assertEquals(AudioAttributes.USAGE_MEDIA, a.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, a.contentType)
    }
}
