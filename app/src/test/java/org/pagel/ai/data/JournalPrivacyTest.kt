package org.pagel.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.crash.CrashLog
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.compass.CompassCrypto
import org.pagel.ai.data.compass.CompassKey
import org.pagel.ai.data.compass.NeedsUnlock
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.journal.JournalApi
import org.pagel.ai.data.journal.JournalDatabase
import org.pagel.ai.data.journal.JournalRepository
import org.pagel.ai.data.notebook.NotebookApi
import org.pagel.ai.data.notebook.NotebookDatabase
import org.pagel.ai.data.notebook.NotebookRepository
import org.pagel.ai.data.today.SendResult
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE PROOF for the locked journal (Boss 2026-10-28: "closed, nobody reads it… I don't want AI to know my feelings").
 * Entries written with marked words go through the real repository, Room on a real file and the real outbox against a
 * fake pagel.uz: the marked words (and the day, and the mood) appear in no request body, no log line and no file on the
 * phone; only inside ciphertext that opens with the journal's own key. The search box never finds them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class JournalPrivacyTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var putCode = 200
    private val logs = CopyOnWriteArrayList<String>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val opened = mutableListOf<androidx.room.RoomDatabase>()
    private lateinit var main: PagelDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = Instant.parse("2026-11-03T16:00:00Z").toEpochMilli()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        ShadowLog.clear()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logs += message }
        })
        main = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path ${request.body.readUtf8()}"
                return when {
                    putCode != 200 -> MockResponse().setResponseCode(putCode).setBody("""{"error":"Unknown path."}""")
                    request.method == "PUT" && path.startsWith("/api/v1/journal/entries/") -> MockResponse().setBody("""{"id":"x","version":1}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        Timber.uprootAll()
        server.shutdown()
        opened.forEach { it.close() }
        main.close()
        scope.cancel()
    }

    private val account by lazy { Account(server.url("/").toString().trimEnd('/'), "d_1", "Phone", "tok") }

    private fun journal(keys: CompassTestKeys = CompassTestKeys()): Pair<JournalRepository, CompassKey> {
        val db = Room.databaseBuilder(ctx, JournalDatabase::class.java, "journal-${System.nanoTime()}.db").allowMainThreadQueries().build().also { opened += it }
        val key = CompassKey(scope)
        return JournalRepository(db.dao(), JournalApi(OkHttpClient(), Dispatchers.IO), keys, key, { }, Dispatchers.IO, clock = { now }) to key
    }

    /** Every marked phrase has "zebra " with a space: base64 ciphertext can never hold a space, so a hit is real. */
    private fun hasMarker(s: String) = s.lowercase().contains("zebra ")

    @Test fun journalWordsLeaveThePhoneOnlyAsCiphertext() = runBlocking {
        val (repo, key) = journal()
        assertEquals(VaultState.NEW, repo.state.first())
        repo.create(repo.cipherForUnlock())
        assertEquals(VaultState.OPEN, repo.state.first())
        val a = repo.save("", "2026-11-03", "zebra feeling one: a hard day, and a good talk after", "😔")
        val b = repo.save("", "2026-11-02", "zebra feeling two: grateful for the walk", "🙏")
        repo.save(a, "2026-11-03", "zebra feeling one, edited: a hard day, then a good talk", "🙂")
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))

        // 1. Every request: no marked word, no day, no mood; only {blob, deleted, base}.
        val puts = requests.filter { it.startsWith("PUT /api/v1/journal/entries/") }
        assertEquals("one PUT per entry (the edit replaced the waiting write)", 2, puts.size)
        for (r in requests) {
            assertFalse("journal words in a request: $r", hasMarker(r))
            assertFalse("the day in a request: $r", r.contains("2026-11-0"))
            assertFalse("the mood in a request: $r", r.contains("🙂") || r.contains("🙏") || r.contains("\\ud83d"))
        }
        val k = key.key.value!!
        for (r in puts) {
            val id = r.substringAfter("/entries/").substringBefore(' ')
            assertTrue(id == a || id == b)
            val body = json.parseToJsonElement(r.substringAfter(' ').substringAfter(' ')).jsonObject
            assertEquals(setOf("blob", "deleted", "base"), body.keys)
            val blob = body["blob"]!!.jsonPrimitive.content
            val plain = CompassCrypto.open(k, JournalRepository.aad(id), blob)
            assertNotNull("a blob that does not open with the journal's key", plain)
            assertTrue(hasMarker(String(plain!!)))
            // Bound to the journal and its own id: under another id or as a Compass item it does not open.
            assertNull(CompassCrypto.open(k, JournalRepository.aad("j_someoneelse"), blob))
            assertNull(CompassCrypto.open(k, CompassCrypto.aad(id, "purpose", null, null, null), blob))
        }

        // 2. No log line (Timber or android.util.Log) carries the words.
        for (l in logs + ShadowLog.getLogs().map { it.msg.orEmpty() }) assertFalse("journal words in a log: $l", hasMarker(l))

        // 3. No file on the phone (databases, WAL, files, caches) holds the words.
        val files = ctx.filesDir.parentFile!!.walkTopDown().filter(File::isFile).toList()
        assertTrue("the journal database file was not written", files.any { it.name.startsWith("journal-") })
        for (f in files) assertFalse("journal words in ${f.name}", hasMarker(String(f.readBytes(), Charsets.ISO_8859_1)))

        // 4. Unlocked: the entries, newest day first, the edit kept. Locked: nothing left to show; writing needs the key.
        val open = repo.opened.first().entries
        assertEquals(listOf("2026-11-03", "2026-11-02"), open.map { it.body.day })
        assertEquals("🙂", open.first().body.mood)
        assertTrue(open.first().body.text.contains("edited"))
        repo.lock()
        assertEquals(VaultState.LOCKED, repo.state.first())
        assertTrue(repo.opened.first().entries.isEmpty())
        assertTrue(runCatching { repo.save("", "2026-11-03", "zebra x", null) }.exceptionOrNull() is NeedsUnlock)
    }

    @Test fun theJournalHasItsOwnKeyApartFromTheCompass() = runBlocking {
        val (repo, key) = journal()
        repo.create(repo.cipherForUnlock())
        val compassKey = CompassKey(scope)
        assertNull("the journal's open key is not the Compass's", compassKey.key.value)
        assertNotNull(key.key.value)
        assertEquals("pagel_journal_guard_v1", JournalRepository.GUARD)
        repo.lock()
        assertNull(key.key.value)
    }

    @Test fun theSearchBoxNeverFindsTheJournal() = runBlocking {
        val (repo, _) = journal()
        repo.create(repo.cipherForUnlock())
        repo.save("", "2026-11-03", "zebra feeling for search", null)
        val nb = Room.inMemoryDatabaseBuilder(ctx, NotebookDatabase::class.java).allowMainThreadQueries().build().also { opened += it }
        val notebook = NotebookRepository(nb.dao(), NotebookApi(OkHttpClient(), Dispatchers.IO), main.dao(), { }, Dispatchers.IO, clock = { now })
        assertTrue(notebook.searchHere("zebra feeling").isEmpty())
    }

    @Test fun aServerWithoutTheJournalKeepsEverythingWaiting() = runBlocking {
        val (repo, _) = journal()
        repo.create(repo.cipherForUnlock())
        repo.save("", "2026-11-03", "zebra waiting", null)
        putCode = 405
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        putCode = 200
        requests.clear()
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        assertEquals(1, requests.count { it.startsWith("PUT /api/v1/journal/entries/") })
    }

    @Test fun aCrashThroughTheJournalKeepsItsFramesButNoMessage() {
        val e = IllegalStateException("zebra words in a message").apply {
            stackTrace = arrayOf(StackTraceElement("org.pagel.ai.data.journal.JournalRepository", "save", "JournalRepository.kt", 10))
        }
        val r = CrashLog.report(Thread.currentThread(), e, "0.3.31", 331, "test", 35, 1L)
        assertEquals(CrashLog.HIDDEN, r.message)
        assertFalse(r.stack.contains("zebra"))
        assertTrue(r.stack.contains("org.pagel.ai.data.journal.JournalRepository.save"))
    }
}
