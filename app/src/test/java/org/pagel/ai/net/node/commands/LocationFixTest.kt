package org.pagel.ai.net.node.commands

import org.junit.Assert.assertFalse
import org.junit.Test

/** [LocationFix.toString] must never leak Boss's coordinates — see that class's doc and `CLAUDE.md`
 *  §8. A default `data class` `toString()` would print every field, including [LocationFix.lat] and
 *  [LocationFix.lon]; this pins the override that prevents it. */
class LocationFixTest {

    @Test
    fun `toString never contains the coordinate values`() {
        val fix = LocationFix(
            lat = 48.20849, lon = 16.37208, accuracyMeters = 12.5f, altitudeMeters = 182.0,
            speedMps = 0f, headingDeg = 270f, timestampMs = 1_700_000_000_000L,
            isPrecise = true, fixSource = LocationFixSource.GPS,
        )

        val rendered = fix.toString()

        assertFalse(rendered.contains("48.20849"))
        assertFalse(rendered.contains("16.37208"))
    }
}
