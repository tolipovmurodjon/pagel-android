package org.pagel.ai.net.ws

/**
 * Computes the reconnect backoff delay: exponential from [initialDelayMs], doubling each attempt,
 * capped at [maxDelayMs]. Matches the documented WS-client curve — "Initial reconnect backoff:
 * 1000ms", "Max reconnect backoff: 30000ms" (`docs/gateway/protocol.md:754-765`,
 * `packages/gateway-client/src/client.ts`) — 1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
 *
 * The browser Control UI uses a different curve (800ms -> 15s, `docs/web/control-ui.md:293-294`);
 * this class deliberately mirrors the WS-client curve, per the same doc note telling Android to.
 *
 * **Pure and stateless.** [nextDelayMs] is a function of [attempt] alone — no mutable fields, no
 * clock, no coroutines. This is the "equivalent shape" alternative to a stateful
 * `nextDelayMs(): Long` + `reset()` pair: since the caller already has to track "which attempt
 * number am I on" to know whether to keep retrying at all, there is no independent state left for
 * this class to own. "Reset to 1s on a successful hello-ok" falls out for free on the caller's
 * side — the caller just goes back to passing `attempt = 0` on the next failure after a success,
 * rather than this class needing a `reset()` to forget history it never kept. A stateful policy
 * object would also need its own thread-safety story once shared across the operator and node
 * connections (Task 6); a pure function sidesteps that entirely.
 *
 * **No jitter.** The documented curve above is a bare exponential with no jitter term, and jitter
 * exists to avoid a thundering herd of *independent* clients retrying in lockstep — this app is a
 * single device identity opening at most two connections (operator + node) to one gateway, not a
 * fleet. Matching the documented curve exactly is more predictable to test and reason about than
 * inventing an undocumented jitter policy for a problem this client doesn't have.
 */
class ReconnectPolicy(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
) {
    /** Delay before the retry that follows the ([attempt] + 1)-th failure since the last success
     *  (or since construction) — `attempt = 0` for the first retry, `attempt = 1` for the second,
     *  and so on. Saturates at [maxDelayMs] rather than continuing to grow. */
    fun nextDelayMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0, was $attempt" }
        val shift = attempt.coerceAtMost(SHIFT_CAP) // guards against Long overflow for pathological attempt counts
        val delay = initialDelayMs shl shift
        return if (delay <= 0 || delay > maxDelayMs) maxDelayMs else delay
    }

    private companion object {
        const val SHIFT_CAP = 32
    }
}
