package org.pagel.ai.data.inbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.InboxItemDto
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.api.SortBody
import org.pagel.ai.data.db.InboxDao
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.InboxStateEntity
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.tasks.UNDO_WINDOW_MS
import org.pagel.ai.data.today.OutboxScheduler
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** The six choices, with the web's keys (learn-client.tsx CHOICES). "now" (Do now) leaves the inbox like a drop. */
enum class Choice(val wire: String, val key: String) {
    ACT("act", "A"), NOTE("note", "N"), KEEP("keep", "K"), NOW("now", "2"), LATER("later", "L"), DROP("drop", "D");

    companion object {
        /** The AI's guess per kind (learn-client.tsx GUESS): someday is an action with "Someday" set. */
        fun guessOf(kind: String): Choice = when (kind) {
            "action", "someday" -> ACT
            "note" -> NOTE
            "drop" -> DROP
            else -> KEEP
        }
    }
}

private val wire = Json { ignoreUnknownKeys = true }
internal fun tashkentDay(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Tashkent")).toLocalDate().toString()
/** Midnight in Tashkent at the start of the day [ms] falls in, as epoch ms. */
internal fun tashkentDayStart(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Tashkent")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Tashkent")).toInstant().toEpochMilli()

/**
 * The Inbox (Phase 2): the deck and the day's counts live in Room, so sorting works with no network; each choice
 * waits in the outbox and is sent in order. Refresh pulls the next cards from pagel.uz when the screen opens or the
 * deck runs low.
 */
class InboxRepository(
    private val api: PagelApi,
    private val dao: InboxDao,
    private val scheduler: OutboxScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val deck: Flow<List<InboxItemEntity>> = dao.deck()
    val state: Flow<InboxStateEntity?> = dao.state()
    val leaving: Flow<Int> = dao.leavingCount()

    /** One shuffle per app run, as the web gives each visit a new seed: the deck keeps its order while PAGEL is open. */
    private val seed = 1 + (Math.random() * 1e9).toLong()

    suspend fun refresh(account: Account): Outcome<Unit> {
        val started = clock()
        return when (val r = api.inbox(account.serverUrl, account.token, DECK, seed)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> {
                val b = r.value
                dao.refresh(b.items.map(::entity), InboxStateEntity(day = b.day.ifEmpty { tashkentDay(clock()) }, goal = b.goal, sorted = b.sorted, keptToday = b.keptToday,
                    cap = b.cap, minWords = b.minWords, total = b.total, fetchedAt = clock()), startedAt = started)
                Outcome.Ok(Unit)
            }
        }
    }

    /**
     * Asks pagel.uz's AI to read [files] (the next cards with no read yet), as the web does after 8 s on /learn. It runs
     * the web's own triage on the server; its reads come with the next [refresh]. True when pagel.uz took the ask.
     */
    suspend fun askToRead(account: Account, files: List<String>): Boolean =
        api.triage(account.serverUrl, account.token, files) is Outcome.Ok

    /** Boss's choice: Room first (the card leaves now), the outbox after. Returns the outbox row (Drop's Undo needs it). */
    suspend fun choose(file: String, choice: Choice, sentence: String? = null, project: String? = null, someday: Boolean? = null): Long {
        val body = wire.encodeToString(SortBody.serializer(), SortBody(choice.wire, file, sentence?.trim()?.takeIf(String::isNotEmpty), project, someday))
        val now = clock()
        val id = dao.choose(file, choice.wire, body, tashkentDay(now), keep = choice == Choice.KEEP, at = now, dayStart = tashkentDayStart(now))
        scheduler.kick()
        return id
    }

    /** Undo (Drop's 5 s): true if the choice had not left the phone and the card is back. */
    suspend fun undo(opId: Long, file: String, choice: Choice): Boolean = dao.undo(opId, file, choice == Choice.KEEP)

    private fun entity(i: InboxItemDto) = InboxItemEntity(
        file = i.file, position = 0, saved = i.saved, source = i.source, text = i.text, claim = i.claim, why = i.why, project = i.project,
        kind = i.kind, next = i.next, note = i.note, guessed = i.guessed, author = i.author, posted = i.posted, later = i.later, hasPicture = i.hasPicture,
    )

    companion object {
        /** Cards kept on the phone: enough for a subway ride offline (the web loads 30). */
        const val DECK = 40
        /** Below this many cards the deck asks pagel.uz for more (the web's 8). */
        const val LOW = 8
    }
}

/** What sending one Inbox choice ended with. */
enum class SortStep { SENT, HOLD, SIGNED_OUT, TRY_LATER }

/**
 * Sends one Inbox choice from the outbox. A Drop waits out its 5 s Undo first (the web's flushDrop). Sent → the card
 * is gone (a Later stays at the back); refused → the card is back on top and the row stays so the shell says why in red.
 */
class InboxSender(private val api: PagelApi, private val dao: InboxDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun send(account: Account, op: OutboxEntity): SortStep {
        val body = op.body?.let { runCatching { wire.decodeFromString(SortBody.serializer(), it) }.getOrNull() }
            ?: run { dao.removeFromOutbox(op.id); return SortStep.SENT }
        if (body.action == Choice.DROP.wire && clock() - op.createdAt < UNDO_WINDOW_MS) return SortStep.HOLD
        dao.countAttempt(op.id)
        return when (val out = api.sort(account.serverUrl, account.token, body)) {
            is Outcome.Ok -> { dao.sent(op.id, body.file, body.action, out.value.sorted, out.value.keptToday, clock()); SortStep.SENT }
            is Outcome.Failed -> when (val e = out.error) {
                is DataError.Refused -> { dao.refused(op.id, body.file, body.action, e.message); SortStep.SENT }
                DataError.Unauthorized -> SortStep.SIGNED_OUT
                else -> SortStep.TRY_LATER
            }
        }
    }
}

/** A card's picture: shown, the post has none (asked once, never again), or not reachable now (asked again later). */
sealed interface Picture {
    data class Shown(val bitmap: Bitmap) : Picture
    data object None : Picture
    data object Unavailable : Picture
}

/**
 * The saves' own pictures, kept on disk so the next cards show theirs offline. A post with none is remembered as such
 * (an empty marker file) and never asked for again; one that could not be fetched (offline) is never remembered, so
 * it is asked for again when the link is back (checker 2026-10-28 #4). Decoded off Main, scaled to the phone's width.
 */
class InboxPictures(private val api: PagelApi, private val dir: File, private val io: CoroutineDispatcher) {
    private val memory = LruCache<String, Bitmap>(6)

    private fun key(file: String) = MessageDigest.getInstance("SHA-1").digest(file.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    suspend fun load(account: Account, file: String, maxWidth: Int = 1080): Picture = withContext(io) {
        val k = key(file)
        memory.get(k)?.let { return@withContext Picture.Shown(it) }
        dir.mkdirs()
        val img = File(dir, "$k.img")
        val none = File(dir, "$k.none")
        if (none.exists()) return@withContext Picture.None
        if (!img.exists()) {
            when (val r = api.picture(account.serverUrl, account.token, file)) {
                is Outcome.Ok -> r.value?.let { img.writeBytes(it) } ?: run { none.createNewFile(); return@withContext Picture.None }
                is Outcome.Failed -> return@withContext Picture.Unavailable
            }
        }
        decode(img, maxWidth)?.also { memory.put(k, it) }?.let { Picture.Shown(it) } ?: Picture.None
    }

    private fun decode(f: File, maxWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
