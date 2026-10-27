package org.pagel.ai.net.ws

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayFramesTest {

    @Test
    fun `Req frame serializes with exactly the documented keys`() {
        val json = GatewayJson.encodeToString(
            GatewayFrame.Req.serializer(),
            GatewayFrame.Req(id = "1", method = "connect", params = JsonPrimitive("x")),
        )
        val obj = GatewayJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "method", "params", "type"), obj.keys)
        assertEquals("req", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Req frame omits a null params key entirely rather than writing it as JSON null`() {
        val json = GatewayJson.encodeToString(GatewayFrame.Req.serializer(), GatewayFrame.Req(id = "1", method = "ping"))
        val obj = GatewayJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "method", "type"), obj.keys)
    }

    @Test
    fun `Res frame serializes with exactly the documented keys and round-trips`() {
        val res = GatewayFrame.Res(id = "1", ok = true, payload = JsonPrimitive("hi"))
        val json = GatewayJson.encodeToString(GatewayFrame.Res.serializer(), res)
        val obj = GatewayJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "ok", "payload", "type"), obj.keys)
        assertEquals(res, parseGatewayFrame(json))
    }

    @Test
    fun `Res frame with an error carries no payload key and round-trips`() {
        val res = GatewayFrame.Res(id = "1", ok = false, error = GatewayError(code = "NOT_PAIRED", message = "wait"))
        val json = GatewayJson.encodeToString(GatewayFrame.Res.serializer(), res)
        val obj = GatewayJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "ok", "error", "type"), obj.keys)
        assertEquals(res, parseGatewayFrame(json))
    }

    @Test
    fun `Event frame serializes with exactly the documented keys and round-trips`() {
        val payload = buildJsonObject { put("nonce", "abc"); put("ts", 1737264000000L) }
        val event = GatewayFrame.Event(event = "connect.challenge", payload = payload)
        val json = GatewayJson.encodeToString(GatewayFrame.Event.serializer(), event)
        val obj = GatewayJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("event", "payload", "type"), obj.keys)
        assertEquals(event, parseGatewayFrame(json))
    }

    @Test
    fun `unknown frame type is ignored rather than crashing`() {
        assertNull(parseGatewayFrame("""{"type":"mystery","foo":"bar"}"""))
    }

    @Test
    fun `frame with a missing type is ignored rather than crashing`() {
        assertNull(parseGatewayFrame("""{"id":"1","ok":true}"""))
    }

    @Test
    fun `malformed json is ignored rather than crashing`() {
        assertNull(parseGatewayFrame("not json"))
    }

    @Test
    fun `a well-typed frame that fails its own schema is ignored rather than crashing`() {
        // "req" with no "method" — valid outer type tag, invalid Req shape.
        assertNull(parseGatewayFrame("""{"type":"req","id":"1"}"""))
    }
}
