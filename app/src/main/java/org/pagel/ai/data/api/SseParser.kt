package org.pagel.ai.data.api

/** One server-sent event: `event:` name, `id:` (the event id the stream reached) and its `data:`. */
data class SseEvent(val event: String, val id: String?, val data: String)

/**
 * Turns the lines of a `text/event-stream` into events (the WHATWG rules we need: `event`, `id`, `data`, comments,
 * a blank line ends an event). Pure, so it is tested without a socket.
 */
class SseParser {
    private var event = ""
    private var id: String? = null
    private val data = StringBuilder()

    /** Feed one line without its newline; returns the finished event when [line] is the blank line that ends it. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (event.isEmpty() && data.isEmpty()) return null
            val out = SseEvent(event.ifEmpty { "message" }, id, data.toString())
            event = ""
            data.setLength(0)
            return out
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "id" -> id = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}
