package org.pagel.ai.net.node

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One command this node can serve for a `node.invoke.request` (protocol reference §1.1). [name]
 * must be an exact-match entry in [NodeCommands.DECLARED] — [NodeCommandDispatcher]'s constructor
 * enforces that at wiring time, not just at dispatch time, so a command implemented here but never
 * declared (or vice versa) fails fast instead of shipping a command that's dead code, or one the
 * gateway's own allowlist gate could never route to us in the first place (`connect.commands` is the
 * ceiling — protocol reference §3.4).
 *
 * [invoke] receives the request's params already decoded from the wire's `paramsJSON` string (an
 * empty object when the request carried `null`/none — [GatewayConnection][org.pagel.ai.net.ws.GatewayConnection]
 * owns that decode, since a malformed `paramsJSON` is a reply-worthy error that never reaches a
 * command at all) and returns the raw success payload; [NodeCommandDispatcher] is what turns it into
 * the reply's `payloadJSON` string. Throwing is a normal, expected outcome — [NodeCommandDispatcher.handle]
 * converts any [Throwable] into a structured error result rather than letting it escape into the
 * connection's coroutine scope.
 */
interface NodeCommand {
    val name: String
    suspend fun invoke(params: JsonObject): JsonElement
}

/**
 * Thrown by a [NodeCommand] that has its own named, stable `error.code` vocabulary — e.g.
 * `location.get`'s `LOCATION_DISABLED` / `LOCATION_PERMISSION_REQUIRED` /
 * `LOCATION_BACKGROUND_UNAVAILABLE` / `LOCATION_TIMEOUT` / `LOCATION_UNAVAILABLE` (protocol
 * reference §4.2) — instead of letting [NodeCommandDispatcher.handle]'s generic `Throwable` catch
 * collapse it to `COMMAND_ERROR`. Boss's server-side skills and watchers already branch on these
 * exact strings, so a command that has them must be able to report them precisely; encoding an error
 * inside the *successful* return payload isn't an option — [NodeCommandDispatcher] would wire it back
 * as `ok:true`, which is a lie the caller can't detect. Any other thrown [Throwable] is unaffected and
 * still becomes `COMMAND_ERROR`.
 */
class NodeCommandError(val code: String, errorMessage: String) : Exception(errorMessage)
