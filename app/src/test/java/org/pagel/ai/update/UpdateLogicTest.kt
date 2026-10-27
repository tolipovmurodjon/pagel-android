package org.pagel.ai.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateLogicTest {
    private val good = """{"versionCode":12,"versionName":"0.2.12","apk":"pagel-12.apk","sha256":"ABC123","builtAt":"2026-10-05","notes":"x","extra":1}"""

    @Test fun `parses a manifest and ignores unknown fields`() {
        val m = UpdateLogic.parse(good)
        assertNotNull(m)
        assertEquals(12, m!!.versionCode)
        assertEquals("pagel-12.apk", m.apk)
    }

    @Test fun `garbage is null, never a crash`() {
        assertNull(UpdateLogic.parse("<html>502</html>"))
        assertNull(UpdateLogic.parse(""))
        assertNull(UpdateLogic.parse("""{"versionCode":"x"}"""))
    }

    @Test fun `only a higher version code is newer`() {
        val m = UpdateLogic.parse(good)!!
        assertTrue(UpdateLogic.isNewer(11, m))
        assertFalse(UpdateLogic.isNewer(12, m))
        assertFalse("a stale host must never downgrade", UpdateLogic.isNewer(13, m))
    }

    @Test fun `apk names that could escape the folder are refused`() {
        assertTrue(UpdateLogic.safeApkName("pagel-12.apk"))
        assertFalse(UpdateLogic.safeApkName("../pagel.apk"))
        assertFalse(UpdateLogic.safeApkName("a/b.apk"))
        assertFalse(UpdateLogic.safeApkName("a\\b.apk"))
        assertFalse(UpdateLogic.safeApkName("pagel.sh"))
        assertFalse(UpdateLogic.safeApkName(""))
    }

    @Test fun `sha256 matches the known digest and is case blind`() {
        val f = File.createTempFile("pagel", ".bin").apply { writeText("abc"); deleteOnExit() }
        val h = UpdateLogic.sha256Hex(f)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
        assertTrue(UpdateLogic.sameHash(h.uppercase(), h))
        assertFalse(UpdateLogic.sameHash(h, h.replaceFirst('b', 'c')))
    }

    @Test fun `a download counts as ready only when its bytes match the published hash`() {
        val dir = createTempDirFor("ready")
        val m = UpdateManifest(13, "0.3.13", "pagel-13.apk", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertNull("nothing downloaded yet", UpdateLogic.verifiedApk(dir, m))
        File(dir, "pagel-13.apk").writeText("abd")
        assertNull("a corrupt or swapped file is never installed", UpdateLogic.verifiedApk(dir, m))
        File(dir, "pagel-13.apk").writeText("abc")
        assertEquals(File(dir, "pagel-13.apk"), UpdateLogic.verifiedApk(dir, m))
        assertNull("an unsafe name is refused even when the file exists", UpdateLogic.verifiedApk(dir, m.copy(apk = "../pagel-13.apk")))
    }

    @Test fun `the ready manifest round-trips through its file text`() {
        val m = UpdateManifest(13, "0.3.13", "pagel-13.apk", "ff", "2026-10-27", "notes")
        assertEquals(m, UpdateLogic.parse(UpdateLogic.encode(m)))
    }

    private fun createTempDirFor(name: String): File =
        File.createTempFile("pagel-$name", "").let { it.delete(); it.mkdirs(); it.deleteOnExit(); it }
}
