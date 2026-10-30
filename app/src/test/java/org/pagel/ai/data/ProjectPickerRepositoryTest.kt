package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.projects.ProjectRepository
import org.pagel.ai.data.projects.SORT_GAP
import org.pagel.ai.data.projects.sortBetween
import org.pagel.ai.data.tasks.TaskRepository
import org.robolectric.annotation.Config

/** The picker's data rules: create once and select, no twin by name, Undo is a real write, a drag is one PATCH. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectPickerRepositoryTest {
    private lateinit var db: PagelDatabase
    private var now = 1_000_000L
    private lateinit var repo: ProjectRepository

    @Before fun setUp() = runBlocking {
        db = inMemoryDb()
        repo = ProjectRepository(db.dao(), TaskRepository(db.dao(), { }, { now }), { }, { now })
        db.dao().upsertProjects(listOf(ProjectEntity("alpha", "Alpha", "mint", 0, 0), ProjectEntity("beta", "Beta", null, 1, 0), ProjectEntity("gamma", "Gamma", null, 2, 0)))
    }

    @After fun tearDown() = db.close()

    private suspend fun ops() = db.dao().waitingOutbox().filter { it.op == OutboxEntity.PROJECT }
    private fun body(op: OutboxEntity) = Json.parseToJsonElement(op.body!!).jsonObject
    private fun method(op: OutboxEntity) = body(op)["method"]!!.jsonPrimitive.content

    @Test fun createFromThePickerGivesTheNameAndTheOpThatSendsIt() = runBlocking {
        val made = repo.createUndoable("CI Picker")
        assertEquals("ci-picker", made.name)
        assertNotNull(db.dao().projectOnce("ci-picker"))
        val op = ops().single()
        assertEquals(made.op, op.id)
        assertEquals("POST", method(op))
        assertEquals("ci-picker", body(op)["body"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun aNameThatExistsIsFoundNotDuplicated() = runBlocking {
        assertEquals("beta", repo.existing("  BETA ")?.name)
        assertEquals("alpha", repo.existing("alpha")?.name)
        assertNull(repo.existing("Delta"))
        repo.create("Delta")
        assertEquals("delta", repo.existing("delta")?.name)
    }

    @Test fun undoBeforeItLeftTakesTheProjectAndItsPostBackSoPagelNeverHearsOfIt() = runBlocking {
        val made = repo.createUndoable("CI Picker")
        repo.undoCreate(made)
        assertNull(db.dao().projectOnce("ci-picker"))
        assertTrue(ops().isEmpty())
    }

    @Test fun undoAfterItLeftSendsARealDeleteAndSendsItsTasksToInbox() = runBlocking {
        val made = repo.createUndoable("CI Picker")
        db.dao().upsertTasks(listOf(TaskEntity("t1", "ci-picker", "Now", 1.0, "Read", null, "open", 2, false, null, null, null, null, "", 1, lastModified = 0, phaseNow = true)))
        db.dao().takeBackOp(made.op) // pagel.uz has the POST now: its outbox row is gone
        repo.undoCreate(made)
        val del = ops().single()
        assertEquals("PATCH", method(del))
        assertEquals("/api/v1/projects/ci-picker", body(del)["path"]!!.jsonPrimitive.content)
        assertEquals("true", body(del)["body"]!!.jsonObject["deleted"]!!.jsonPrimitive.content)
        assertEquals("inbox", body(del)["body"]!!.jsonObject["tasks"]!!.jsonPrimitive.content)
        assertEquals("inbox", db.dao().taskOnce("t1")!!.project)
    }

    @Test fun sortBetweenFindsRoomOrSaysTheyTouch() {
        assertEquals(0, sortBetween(null, null))
        assertEquals(5 - SORT_GAP, sortBetween(null, 5))
        assertEquals(5 + SORT_GAP, sortBetween(5, null))
        assertEquals(1030, sortBetween(1024, 1036))
        assertNull(sortBetween(1, 2))
        assertNull(sortBetween(2, 2))
    }

    @Test fun aDragToTheTopIsOnePatchOfOneRow() = runBlocking {
        repo.place("gamma", after = null, before = "alpha")
        assertEquals("/api/v1/projects/gamma", body(ops().single())["path"]!!.jsonPrimitive.content)
        assertEquals(listOf("gamma", "alpha", "beta"), db.dao().projectsOnce().sortedBy { it.sort }.map { it.name })
    }

    @Test fun neighboursThatTouchSpaceTheListOnceThenTheNextDragIsOnePatch() = runBlocking {
        repo.place("alpha", after = "beta", before = "gamma")
        val first = ops()
        assertEquals(3, first.size)
        assertEquals(listOf("beta", "alpha", "gamma"), db.dao().projectsOnce().sortedBy { it.sort }.map { it.name })
        first.forEach { db.dao().takeBackOp(it.id) }
        repo.place("gamma", after = "beta", before = "alpha")
        val second = ops().single()
        assertEquals("/api/v1/projects/gamma", body(second)["path"]!!.jsonPrimitive.content)
        assertEquals(listOf("beta", "gamma", "alpha"), db.dao().projectsOnce().sortedBy { it.sort }.map { it.name })
    }

    @Test fun placeLastGoesAfterTheLastWithoutTouchingTheRest() = runBlocking {
        repo.place("alpha", after = "gamma")
        assertEquals(listOf("beta", "gamma", "alpha"), db.dao().projectsOnce().sortedBy { it.sort }.map { it.name })
        assertEquals(1, ops().size)
    }
}
