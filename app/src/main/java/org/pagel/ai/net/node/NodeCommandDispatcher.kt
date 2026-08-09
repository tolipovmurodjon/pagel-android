package org.pagel.ai.net.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One parsed `node.invoke.request` event (protocol reference §1.1), ready for
 * [NodeCommandDispatcher.handle]. [params] is already decoded from the wire's `paramsJSON` string —
 * see [NodeCommand]'s doc for why that decode happens before this type exists rather than inside
 * [handle]. [timeoutMs] is `null` when the request payload carried none; [handle] then falls back to
 * [NodeCommandDispatcher]'s own default, mirroring the gateway's `resolveTimerTimeoutMs(params.timeoutMs,
 * 3e4, 0)` fallback (protocol reference §1.4) — `0` is a legal, deliberately-immediate value, not a
 * sentinel for "no timeout."
 */
data class InvokeRequest(
    val id: String,
    val nodeId: String,
    val command: String,
    val params: JsonObject,
    val timeoutMs: Long?,
)

/**
 * The outcome of [NodeCommandDispatcher.handle], carrying [id]/[nodeId] copied verbatim from the
 * originating [InvokeRequest]. Echoing them into the wire reply is then structural for whatever
 * builds that reply, not a discipline it has to remember to apply — protocol reference §1.3: the
 * gateway rejects a reply whose `nodeId` doesn't match the caller's own session, and silently
 * discards one whose `id` isn't the exact `payload.id` it sent.
 */
sealed interface InvokeResult {
    val id: String
    val nodeId: String

    data class Success(override val id: String, override val nodeId: String, val payload: JsonElement) : InvokeResult
    data class Error(override val id: String, override val nodeId: String, val code: String, val message: String) : InvokeResult
}

/**
 * Routes one [InvokeRequest] to the [NodeCommand] named by [InvokeRequest.command] and always
 * resolves to an [InvokeResult] — never throws, never leaves a request unanswered. There is no
 * "unhandled" path on the wire: not replying at all just means the operator sits out the full
 * timeout and sees a bare `TIMEOUT`, so an unknown command, a throwing command, and a command that
 * outruns its budget are all reply-worthy outcomes here, not failures to propagate (protocol
 * reference §6.3).
 *
 * **Constructor enforces "declaring and serving must not drift apart."** Every [commands] entry's
 * [NodeCommand.name] must already be in [NodeCommands.DECLARED] — the gateway's own two-gate
 * allowlist (protocol reference §3.4) means a command implemented here but never declared could
 * never be reached by a real invoke anyway, so shipping one is either dead code or a bug in
 * [NodeCommands.DECLARED]. Failing fast at construction (a unit test, or DI wiring at app start)
 * catches that immediately instead of as a command silently never called.
 */
class NodeCommandDispatcher(commands: List<NodeCommand>) {
    private val byName: Map<String, NodeCommand> = commands.associateBy { it.name }

    init {
        val undeclared = commands.map { it.name }.filterNot { it in NodeCommands.DECLARED }
        require(undeclared.isEmpty()) {
            "NodeCommand(s) not in NodeCommands.DECLARED: $undeclared — declaring and serving must not drift apart"
        }
        require(byName.size == commands.size) {
            "duplicate NodeCommand names: ${commands.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys}"
        }
    }

    suspend fun handle(request: InvokeRequest): InvokeResult {
        val command = byName[request.command]
            ?: return InvokeResult.Error(
                id = request.id,
                nodeId = request.nodeId,
                code = "INVALID_REQUEST",
                message = "unknown command \"${request.command}\"",
            )

        val timeoutMs = request.timeoutMs ?: DEFAULT_TIMEOUT_MS
        return try {
            val payload = withTimeout(timeoutMs) { command.invoke(request.params) }
            InvokeResult.Success(id = request.id, nodeId = request.nodeId, payload = payload)
        } catch (e: TimeoutCancellationException) {
            // Caught ahead of the bare CancellationException rethrow below — it's a subclass, and
            // withTimeout's own expiry is exactly the case this branch exists to turn into a reply
            // instead of leaving the operator to discover it via its own 15-30s RPC timeout instead.
            InvokeResult.Error(
                id = request.id,
                nodeId = request.nodeId,
                code = "TIMEOUT",
                message = "command \"${request.command}\" exceeded ${timeoutMs}ms",
            )
        } catch (e: CancellationException) {
            throw e // real cancellation (the owning scope shutting down) — not this command's outcome to report
        } catch (t: Throwable) {
            // No code for "a command threw" is named anywhere in the protocol reference (only
            // INVALID_REQUEST and TIMEOUT are). COMMAND_ERROR is this dispatcher's own choice,
            // distinct from both so a caller can tell "the command ran and failed" apart from
            // "the command was never found" or "the command ran out of time."
            InvokeResult.Error(
                id = request.id,
                nodeId = request.nodeId,
                code = "COMMAND_ERROR",
                message = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private companion object {
        /** Mirrors the gateway's own `resolveTimerTimeoutMs(params.timeoutMs, 3e4, 0)` fallback
         *  (protocol reference §1.4) — used only when the request payload's `timeoutMs` is absent. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
