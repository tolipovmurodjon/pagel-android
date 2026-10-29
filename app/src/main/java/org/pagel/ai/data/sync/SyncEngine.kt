package org.pagel.ai.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.api.SyncResponse
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.SyncStateEntity
import org.pagel.ai.data.db.TaskEntity
import kotlin.random.Random

/**
 * "Offline" only after 2 failed calls in a row (round B: on 4G one slow handshake showed "Offline" for 1.5 h of
 * otherwise working sync); a single miss stays "Syncing" while the retry runs.
 */
fun statusAfter(failuresInARow: Int, error: DataError, retryInMs: Long): LiveStatus =
    if (failuresInARow >= 2) LiveStatus.Offline(error, retryInMs) else LiveStatus.Syncing

/** What the sync loop is doing, for the screen's small status line. */
sealed interface LiveStatus {
    data object Idle : LiveStatus
    data object Syncing : LiveStatus
    data class Live(val lastSyncAt: Long) : LiveStatus
    data class Offline(val error: DataError, val retryInMs: Long) : LiveStatus
}

/**
 * Keeps Room equal to pagel.uz (NATIVE-PLAN Phase 1). Cold start: one pull from the saved cursor (0 = a full copy).
 * Then the live stream: every `change` event pulls the delta since the cursor, so a tick on the web shows on the phone
 * in about 2 s (the server polls its events table every 2 s). A broken stream reconnects with exponential backoff
 * and jitter, bounded at a minute. Conflict rule: the server wins by event order (Phase 1 only reads).
 */
class SyncEngine(
    private val api: PagelApi,
    private val dao: PagelDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<LiveStatus>(LiveStatus.Idle)
    val status: StateFlow<LiveStatus> = _status.asStateFlow()
    private var loop: Job? = null
    private var again: (() -> Unit)? = null

    /** Pulls everything since the saved cursor and applies it in one transaction. */
    suspend fun syncOnce(account: Account): Outcome<SyncResponse> = lock.withLock {
        val since = dao.syncState()?.cursor ?: 0L
        val out = api.sync(account.serverUrl, account.token, since)
        if (out is Outcome.Ok) {
            apply(out.value)
            // Today's order comes from pagel.uz (the web's own dealing), so phone and web never disagree. If only
            // this call fails, the last saved order stays: Today still works offline.
            (api.today(account.serverUrl, account.token) as? Outcome.Ok)?.let { t -> dao.replaceTodayOrder(t.value.tasks.map { it.id }) }
        }
        out
    }

    /** Starts the pull-then-listen loop in [scope]; a second call while it runs does nothing. */
    fun start(scope: CoroutineScope, account: Account, onUnauthorized: suspend () -> Unit) {
        if (loop?.isActive == true) return
        again = { loop?.cancel(); loop = null; start(scope, account, onUnauthorized) }
        loop = scope.launch {
            var failures = 0
            while (isActive) {
                _status.value = LiveStatus.Syncing
                val pulled = syncOnce(account)
                val error = when (pulled) {
                    is Outcome.Failed -> pulled.error
                    is Outcome.Ok -> {
                        failures = 0
                        _status.value = LiveStatus.Live(clock())
                        api.stream(account.serverUrl, account.token, dao.syncState()?.cursor ?: 0L) { e ->
                            if (e.event == "change") {
                                if (syncOnce(account) is Outcome.Ok) _status.value = LiveStatus.Live(clock())
                            }
                        }
                    }
                }
                if (error == DataError.Unauthorized) {
                    _status.value = LiveStatus.Offline(error, 0)
                    onUnauthorized()
                    return@launch
                }
                val wait = backoffMs(failures++)
                _status.value = statusAfter(failures, error, wait)
                delay(wait)
            }
        }
    }

    /**
     * The phone has a network again (Session's network callback): a loop waiting out its backoff, which can grow to a
     * minute, tries now (round B: "a retry within 5 s when the network changes"). A live loop is left alone.
     */
    fun networkBack() {
        if (loop?.isActive == true && _status.value !is LiveStatus.Live) again?.invoke()
    }

    fun stop() {
        again = null
        loop?.cancel()
        loop = null
        _status.value = LiveStatus.Idle
    }

    /** 1 s, 2 s, 4 s … capped at 60 s, each with ±20 % jitter so phones never retry in step. */
    internal fun backoffMs(failures: Int): Long {
        val base = (1_000L shl failures.coerceIn(0, 6)).coerceAtMost(60_000L)
        return (base * (0.8 + random.nextDouble() * 0.4)).toLong()
    }

    private suspend fun apply(r: SyncResponse) {
        val now = clock()
        dao.applySync(
            full = r.full,
            projects = r.projects.map { ProjectEntity(it.name, it.title, it.color, it.sort, it.open, it.icon, it.area, it.parent, it.archived, it.finishedAt, it.shipped, it.goals.toString()) },
            tasks = r.tasks.map { it.toEntity(now) },
            steps = r.tasks.flatMap { it.stepEntities() },
            removed = r.removed,
            state = SyncStateEntity(cursor = r.cursor, lastSyncAt = now),
        )
    }
}

/** A task as pagel.uz sends it (/sync, /completed), as Room keeps it. */
fun org.pagel.ai.data.api.SyncTaskDto.toEntity(now: Long) = TaskEntity(
    id = id, project = project, phase = phase, sort = sort, text = text, title = title,
    status = status, priority = priority, star = star, due = due, every = every,
    doneWhen = doneWhen, doneAt = doneAt, updatedAt = updatedAt, serverVersion = version, lastModified = now,
    phaseSort = phaseSort, phaseNow = phaseNow, times = times.coerceIn(1, 12), habitGroup = group, milestone = milestone,
)

fun org.pagel.ai.data.api.SyncTaskDto.stepEntities() = steps.mapIndexed { i, s -> StepEntity(id, i, s.text, s.title, s.done) }
