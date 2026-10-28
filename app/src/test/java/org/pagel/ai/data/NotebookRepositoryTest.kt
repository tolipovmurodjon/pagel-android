package org.pagel.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.notebook.Grade
import org.pagel.ai.data.notebook.HitKind
import org.pagel.ai.data.notebook.NotebookApi
import org.pagel.ai.data.notebook.NotebookDatabase
import org.pagel.ai.data.notebook.NotebookRepository
import org.pagel.ai.data.notebook.PENDING
import org.pagel.ai.data.notebook.RecallSession
import org.pagel.ai.data.notebook.SYNCED
import org.pagel.ai.data.today.SendResult
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The second brain against a fake pagel.uz (dashboard vault-api.ts, search.ts, recall-store.ts, quick-notes-api.ts):
 * the pull fills Room, a folder switch / a note / a grade is in Room at once and reaches pagel.uz from the outbox, a
 * stale note is kept as a second copy, Kundalik never opens. Fixture notes only; never Boss's vault.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotebookRepositoryTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var down = false
    @Volatile private var conflict = false
    private lateinit var main: PagelDatabase
    private lateinit var nb: NotebookDatabase
    private lateinit var repo: NotebookRepository
    private var kicks = 0
    private val now = Instant.parse("2026-10-29T10:00:00Z").toEpochMilli()

    private val folders = """{"folders":[
        {"path":"Kundalik","allowed":false,"set":false,"locked":true,"notes":0},
        {"path":"Notes","allowed":true,"set":true,"locked":false,"notes":2},
        {"path":"Sources","allowed":false,"set":false,"locked":false,"notes":0},
        {"path":"Sources/Books","allowed":false,"set":false,"locked":false,"notes":0}],"locked":["Kundalik"]}"""

    @Before fun setUp() {
        main = inMemoryDb()
        nb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NotebookDatabase::class.java).allowMainThreadQueries().build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                requests += "${request.method} $path $body"
                if (down) return MockResponse().setResponseCode(503)
                return when {
                    request.method == "GET" && path == "/api/v1/vault/folders" -> MockResponse().setBody(folders)
                    request.method == "PUT" && path == "/api/v1/vault/folders" ->
                        if (body.contains("Kundalik")) MockResponse().setResponseCode(400).setBody("""{"error":"Kundalik is closed for good."}""")
                        else MockResponse().setBody(folders.replace(""""path":"Sources","allowed":false,"set":false""", """"path":"Sources","allowed":true,"set":true"""))
                    request.method == "GET" && path.startsWith("/api/v1/vault/notes?") -> MockResponse().setBody(
                        """{"notes":[{"path":"Notes/Spaced recall.md","title":"Spaced recall","hash":"h1","version":1,"updated_at":"2026-10-20T00:00:00Z"},
                        {"path":"Notes/Ideas.md","title":"Ideas","hash":"h2","version":3,"updated_at":"2026-10-21T00:00:00Z"}]}""",
                    )
                    request.method == "GET" && path == "/api/v1/vault/notes/Notes/Spaced%20recall.md" -> MockResponse().setBody(
                        """{"path":"Notes/Spaced recall.md","title":"Spaced recall","body":"# Spaced recall\nSee [[Ideas]].","hash":"h1","version":1,"updated_at":"2026-10-20T00:00:00Z",
                        "links":[{"target":"ideas","path":"Notes/Ideas.md"}],"backlinks":[{"path":"Notes/Ideas.md","title":"Ideas"}]}""",
                    )
                    request.method == "GET" && path == "/api/v1/vault/manifest" -> MockResponse().setBody("""{"notes":[],"files":[{"path":"Notes/cover.png","hash":"x","size":10}]}""")
                    request.method == "GET" && path.startsWith("/api/v1/recall/due") -> MockResponse().setBody(
                        """{"due":2,"cards":[{"id":"c_1","question":"What makes recall stick?","answer":"Spacing","due":"2026-10-28T00:00:00.000Z","source_kind":"vault_note","source_id":"Notes/Spaced recall.md"},
                        {"id":"c_2","question":"Q2","answer":"A2","due":"2026-10-29T00:00:00.000Z"}]}""",
                    )
                    request.method == "POST" && path.startsWith("/api/v1/recall/") -> MockResponse().setBody("{}")
                    request.method == "GET" && path == "/api/v1/notes" -> MockResponse().setBody("""{"projects":[{"project":"humopedia","count":2,"last":"2026-10-20"}]}""")
                    request.method == "GET" && path == "/api/v1/notes?project=humopedia" -> MockResponse().setBody(
                        """{"project":"humopedia","notes":[{"text":"newest sample idea","day":"2026-10-20"},{"text":"older sample idea","day":"2026-10-01"}]}""",
                    )
                    request.method == "PUT" && path == "/api/v1/vault/notes" -> {
                        val p = Regex(""""path":"([^"]+)"""").find(body)!!.groupValues[1]
                        if (conflict) MockResponse().setResponseCode(201).setBody("""{"path":"${p.removeSuffix(".md")} (phone).md","hash":"h9","version":1,"conflict":true,"kept":"$p"}""")
                        else MockResponse().setBody("""{"path":"$p","hash":"h5","version":2}""")
                    }
                    request.method == "GET" && path.startsWith("/api/v1/search?") -> MockResponse().setBody(
                        """{"q":"x","results":[{"kind":"note","path":"Notes/Ideas.md","title":"Ideas","snippet":"**x**"},{"kind":"note","path":"Kundalik/a.md","title":"no","snippet":""},
                        {"kind":"resource","id":"r_1","title":"A tool","snippet":"a **x** tool"},{"kind":"task","id":"t_1","title":"T","snippet":""},{"kind":"journal","id":"j_1","title":"no","snippet":""}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        repo = NotebookRepository(nb.dao(), NotebookApi(OkHttpClient(), Dispatchers.IO), main.dao(), { kicks++ }, Dispatchers.IO, clock = { now })
    }

    @After fun tearDown() {
        server.shutdown()
        nb.close()
        main.close()
    }

    private val account by lazy { Account(server.url("/").toString().trimEnd('/'), "d_1", "Phone", "tok") }

    @Test fun thePullFillsRoomAndKundalikComesLocked() = runBlocking {
        repo.refresh(account)
        val f = repo.folders.first()
        assertEquals(listOf("Kundalik", "Notes", "Sources", "Sources/Books"), f.map { it.path })
        assertTrue(f.first().locked)
        assertFalse(f.first().allowed)
        assertEquals(setOf("Notes/Spaced recall.md", "Notes/Ideas.md"), repo.notes.first().map { it.path }.toSet())
        assertNull("the list carries titles only; the words come when a note is opened", repo.notes.first().first().body)
        assertEquals(listOf("c_1", "c_2"), repo.cards.first().map { it.id })
        assertEquals(listOf("Notes/cover.png"), repo.files())
        // The newest quick note is n = 2 (counted from the oldest, as pagel.uz's search refs).
        assertEquals("newest sample idea", repo.quickNote("humopedia", 2)?.text)
    }

    @Test fun openingANoteBringsItsWordsAndBacklinks() = runBlocking {
        repo.refresh(account)
        repo.open(account, "Notes/Spaced recall.md")
        val n = repo.note("Notes/Spaced recall.md").first()!!
        assertEquals("# Spaced recall\nSee [[Ideas]].", n.body)
        assertEquals("Notes/Ideas.md", n.links.single().path)
        assertEquals("Ideas", n.backlinks.single().title)
        // Offline: the note opens from Room.
        down = true
        repo.open(account, "Notes/Spaced recall.md")
        assertEquals("# Spaced recall\nSee [[Ideas]].", repo.note("Notes/Spaced recall.md").first()!!.body)
    }

    @Test fun aFolderSwitchIsOnScreenAtOnceAndKundalikNeverOpens() = runBlocking {
        repo.refresh(account)
        assertFalse(repo.setAllowed("Kundalik", true))
        assertTrue(repo.setAllowed("Sources", true))
        val f = repo.folders.first().associateBy { it.path }
        assertTrue(f.getValue("Sources").allowed)
        assertEquals(PENDING, f.getValue("Sources").syncStatus)
        assertTrue("the subfolder that follows it opens with it", f.getValue("Sources/Books").allowed)
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        val puts = requests.filter { it.startsWith("PUT /api/v1/vault/folders") }
        assertEquals(listOf("""PUT /api/v1/vault/folders {"folders":[{"path":"Sources","allowed":true}]}"""), puts)
        assertTrue(requests.none { it.contains("Kundalik") && it.startsWith("PUT") })
        assertEquals(SYNCED, repo.folders.first().single { it.path == "Sources" }.syncStatus)
    }

    @Test fun aNoteWrittenOfflineIsSavedHereThenSentWithItsBase() = runBlocking {
        repo.refresh(account)
        repo.open(account, "Notes/Spaced recall.md")
        down = true
        repo.save("Notes/Spaced recall.md", "# Spaced recall\nEdited on the phone.")
        repo.save("Notes/New idea.md", "# New idea\nA sample line.")
        assertEquals(SendResult.TRY_LATER, repo.sendAll(account))
        val edited = repo.note("Notes/Spaced recall.md").first()!!
        assertTrue(edited.pending)
        assertEquals("# Spaced recall\nEdited on the phone.", edited.body)
        // A pull while it waits never takes the phone's words away.
        down = false
        repo.refresh(account)
        assertEquals("# Spaced recall\nEdited on the phone.", repo.note("Notes/Spaced recall.md").first()!!.body)
        requests.clear()
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        val puts = requests.filter { it.startsWith("PUT /api/v1/vault/notes") }
        assertEquals(2, puts.size)
        assertTrue(puts[0], puts[0].endsWith(""""path":"Notes/Spaced recall.md","body":"# Spaced recall\nEdited on the phone.","base":"h1"}"""))
        assertFalse("a new note sends no base (a null base would make pagel.uz keep two copies)", puts[1].contains("base"))
        assertFalse(repo.note("Notes/New idea.md").first()!!.pending)
    }

    @Test fun aStaleNoteIsKeptAsTheSecondCopyNeverLost() = runBlocking {
        repo.refresh(account)
        repo.open(account, "Notes/Spaced recall.md")
        repo.save("Notes/Spaced recall.md", "# Spaced recall\nMy phone words.")
        conflict = true
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        val mine = repo.note("Notes/Spaced recall (phone).md").first()!!
        assertEquals("# Spaced recall\nMy phone words.", mine.body)
        assertNull("the original is fetched again from pagel.uz", repo.note("Notes/Spaced recall.md").first()!!.body)
    }

    @Test fun aGradeLeavesTheReviewAtOnceAndWaitsOffline() = runBlocking {
        repo.refresh(account)
        val session = RecallSession.start(repo.cards.first(), "2026-10-29T10:00:00.000Z")
        assertEquals(listOf("c_1", "c_2"), session.cards.map { it.id })
        down = true
        repo.grade("c_1", Grade.GOOD)
        assertEquals(listOf("c_2"), repo.cards.first().map { it.id })
        assertEquals(SendResult.TRY_LATER, repo.sendAll(account))
        // A pull meanwhile does not bring the graded card back.
        down = false
        repo.refresh(account)
        assertEquals(listOf("c_2"), repo.cards.first().map { it.id })
        requests.clear()
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        assertEquals(listOf("""POST /api/v1/recall/c_1/grade {"grade":"good"}"""), requests.filter { it.startsWith("POST") })
        assertTrue(kicks >= 1)
    }

    @Test fun searchMergesPagelUzAndThePhoneAndNeverShowsKundalikOrAnUnknownKind() = runBlocking {
        val there = repo.searchThere(account, "x")!!
        assertEquals(listOf(HitKind.NOTE, HitKind.RESOURCE, HitKind.TASK), there.map { it.kind })
        assertTrue(there.none { it.ref.startsWith("Kundalik") || it.ref.startsWith("j_") })
        down = true
        assertNull("offline: only the phone's own hits show", repo.searchThere(account, "x"))
    }
}
