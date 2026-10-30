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

/**
 * The choices, with the web's keys (learn-client.tsx CHOICES). "now" (Do now) leaves the inbox like a drop. The four
 * GTD additions (Boss 2026-10-30) reach pagel.uz as an "act" with the fields it already has: Someday is a P3 task,
 * Waiting and Calendar carry a due date, Project is a new project plus its first step. [log] is what "Sorted today" shows.
 */
enum class Choice(val wire: String, val key: String, val log: String = wire) {
    ACT("act", "A"), NOTE("note", "N"), KEEP("keep", "K"), NOW("now", "2"), LATER("later", "L"), DROP("drop", "D"),
    SOMEDAY("act", "S", "someday"), WAITING("act", "W", "waiting"), PROJECT("act", "P", "project"), CALENDAR("act", "C", "calendar");

    companion object {
        /** The AI's guess per kind (learn-client.tsx GUESS): a someday kind is the one-tap Someday. */
        fun guessOf(kind: String): Choice = when (kind) {
            "someday" -> SOMEDAY
            "action" -> ACT
            "note" -> NOTE
            "drop" -> DROP
            else -> KEEP
        }
    }
}

private val wire = Json { ignoreUnknownKeys = true }

/**
 * The deck's order: the newest save first by the post's own date (908 of 945 saves carry the import day as `saved`),
 * a note of Boss's own by its `saved` day; the ones put off once go last. Stable, so pagel.uz's tie order stands.
 */
fun newestFirst(items: List<InboxItemEntity>): List<InboxItemEntity> =
    items.sortedWith(compareBy<InboxItemEntity> { it.later != null }.thenByDescending { it.posted.ifBlank { it.saved } })
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

    /**
     * Pulls the next cards, newest first (Boss 2026-10-28: "latest one is main priority than old ones"): no seed, so
     * pagel.uz deals its own order, and [newestFirst] keeps it even from an older pagel.uz that sorts by the import day.
     */
    suspend fun refresh(account: Account): Outcome<Unit> {
        val started = clock()
        return when (val r = api.inbox(account.serverUrl, account.token, DECK, seed = 0)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> {
                val b = r.value
                dao.refresh(newestFirst(b.items.map(::entity)), InboxStateEntity(day = b.day.ifEmpty { tashkentDay(clock()) }, goal = b.goal, sorted = b.sorted, keptToday = b.keptToday,
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
    suspend fun choose(file: String, choice: Choice, sentence: String? = null, project: String? = null, someday: Boolean? = null, due: String? = null): Long {
        val body = wire.encodeToString(SortBody.serializer(), SortBody(choice.wire, file, sentence?.trim()?.takeIf(String::isNotEmpty), project, someday, due))
        val now = clock()
        val id = dao.choose(file, choice.wire, body, tashkentDay(now), keep = choice == Choice.KEEP, at = now, dayStart = tashkentDayStart(now))
        scheduler.kick()
        return id
    }

    /**
     * Swipe up: the card goes back into the deck at a random later spot (at least two cards on), and nothing is sent.
     * False when there is no later spot (the last card). [spot] picks in 0 until n; tests pass their own.
     */
    suspend fun shuffle(file: String, spot: (Int) -> Int = { kotlin.random.Random.nextInt(it) }): Boolean = dao.shuffleBack(file, spot)

    /** Undo (Drop's 5 s): true if the choice had not left the phone and the card is back. */
    suspend fun undo(opId: Long, file: String, choice: Choice): Boolean = dao.undo(opId, file, choice == Choice.KEEP)

    /**
     * The saves older than [days] that pagel.uz holds, for the tidy-up. An older pagel.uz ignores `older_than` and deals
     * its newest cards, so every card is checked by its own date here and only the really old ones are returned.
     */
    suspend fun older(account: Account, days: Int = OLD_DAYS): Outcome<List<InboxItemEntity>> =
        when (val r = api.inbox(account.serverUrl, account.token, OLDER_PAGE, seed = 0, olderThan = days)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> Outcome.Ok(olderThan(r.value.items.map(::entity), days, tashkentDay(clock())))
        }

    /**
     * One decision for many saves (Someday or Drop for the old ones): each is a quiet outbox row, held for the Undo
     * window so [undoMany] stops them before pagel.uz hears of them. Returns the (outbox row, file) pairs for the Undo.
     */
    suspend fun chooseMany(items: List<InboxItemEntity>, choice: Choice, project: String): List<Pair<Long, String>> {
        val now = clock()
        val rows = items.map { i ->
            val someday = choice == Choice.SOMEDAY
            val line = if (someday) somedayLine(i) else null
            val body = wire.encodeToString(SortBody.serializer(), SortBody(choice.wire, i.file, line, if (someday) project else null, if (someday) true else null))
            dao.chooseQuiet(i.file, choice.wire, body, now) to i.file
        }
        scheduler.kick()
        return rows
    }

    /** Takes the bulk back: how many had not left the phone (all of them inside the Undo window). */
    suspend fun undoMany(rows: List<Pair<Long, String>>): Int = rows.count { (id, file) -> dao.undoQuiet(id, file) }

    private fun entity(i: InboxItemDto) = InboxItemEntity(
        file = i.file, position = 0, saved = i.saved, source = i.source, text = i.text, claim = i.claim, why = i.why, project = i.project,
        kind = i.kind, next = i.next, note = i.note, guessed = i.guessed, author = i.author, posted = i.posted, later = i.later, hasPicture = i.hasPicture,
    )

    companion object {
        /** Cards kept on the phone: enough for a subway ride offline (the web loads 30). */
        const val DECK = 40
        /** Below this many cards the deck asks pagel.uz for more (the web's 8). */
        const val LOW = 8
        /** The most pagel.uz deals in one answer. */
        const val OLDER_PAGE = 60
    }
}

/** A Drop and a Someday wait out the 5 s Undo before they leave the phone: both have an Undo toast. */
internal fun SortBody.holdsForUndo() = action == Choice.DROP.wire || someday == true

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
        if (body.holdsForUndo() && clock() - op.createdAt < UNDO_WINDOW_MS) return SortStep.HOLD
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
