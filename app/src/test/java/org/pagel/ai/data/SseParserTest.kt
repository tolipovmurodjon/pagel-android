package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.api.SseEvent
import org.pagel.ai.data.api.SseParser

class SseParserTest {
    private fun parse(text: String): List<SseEvent> {
        val p = SseParser()
        return text.split("\n").mapNotNull { p.feed(it) }
    }

    @Test fun `reads pagel's stream exactly as the server writes it`() {
        val events = parse("retry: 5000\nevent: hello\ndata: 275\n\n: ping\n\nevent: change\nid: 276\ndata: {\"id\":276,\"actors\":[\"e2e\"]}\n\n")
        assertEquals(listOf(SseEvent("hello", null, "275"), SseEvent("change", "276", "{\"id\":276,\"actors\":[\"e2e\"]}")), events)
    }

    @Test fun `a comment or a lone blank line is not an event`() {
        assertEquals(emptyList<SseEvent>(), parse(": ping\n\n\n"))
        assertNull(SseParser().feed(""))
    }

    @Test fun `data lines join with newlines and an unnamed event is a message`() {
        assertEquals(listOf(SseEvent("message", null, "a\nb")), parse("data: a\ndata: b\n\n"))
    }
}
