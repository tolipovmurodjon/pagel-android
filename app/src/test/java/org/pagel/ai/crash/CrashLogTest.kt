package org.pagel.ai.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashLogTest {
    private fun tempDir(): File = File.createTempFile("pagel-crash", "").let { it.delete(); it.mkdirs(); it.deleteOnExit(); it }

    @Test fun `a crash becomes a file with its type, message and stack`() {
        val dir = tempDir()
        val e = IllegalStateException("PAGEL test crash")
        val f = CrashLog.write(dir, CrashLog.report(Thread.currentThread(), e, "0.3.1", 31, "samsung SM-A057F", 34, 1_000L))
        val back = CrashLog.read(f)
        assertNotNull(back)
        assertEquals("java.lang.IllegalStateException", back!!.type)
        assertEquals("PAGEL test crash", back.message)
        assertTrue(back.stack.contains("CrashLogTest"))
        assertEquals(31, back.versionCode)
    }

    @Test fun `a crash loop keeps only the newest files`() {
        val dir = tempDir()
        val e = RuntimeException("loop")
        repeat(CrashLog.KEEP + 5) { CrashLog.write(dir, CrashLog.report(Thread.currentThread(), e, "x", 1, "d", 34, 100L + it)) }
        val left = CrashLog.pending(dir)
        assertEquals(CrashLog.KEEP, left.size)
        assertEquals("crash-${100L + CrashLog.KEEP + 4}.json", left.first().name)
    }

    @Test fun `a huge stack is cut so one crash stays small`() {
        val e = RuntimeException("x".repeat(2_000))
        val r = CrashLog.report(Thread.currentThread(), e, "x", 1, "d", 34, 1L)
        assertEquals(500, r.message.length)
        assertTrue(r.stack.length <= 16_000)
    }

    @Test fun `an unreadable file is null, never a crash`() {
        val f = File(tempDir(), "crash-1.json").apply { writeText("{not json") }
        assertEquals(null, CrashLog.read(f))
    }
}
