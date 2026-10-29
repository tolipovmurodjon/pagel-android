package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.SyncStateEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.projects.Kpi
import org.pagel.ai.data.projects.Milestone
import org.pagel.ai.data.projects.ProjectGoal
import org.pagel.ai.data.projects.ProjectRepository
import org.pagel.ai.data.projects.goalsOf
import org.pagel.ai.data.projects.slugOf
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.tasks.UNDO_WINDOW_MS
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.projects.ListLine
import org.pagel.ai.ui.projects.dropIndex
import org.pagel.ai.ui.projects.milestoneProgress
import org.pagel.ai.ui.projects.projectGroups
import org.pagel.ai.ui.projects.projectLines
import org.pagel.ai.ui.projects.reordered
import org.pagel.ai.ui.projects.sortAfterMove
import org.robolectric.annotation.Config

/**
 * Projects you shape (core round): every change answers in Room at once and waits in the outbox as one project op;
 * a delete and a merge hold for Undo; Undo inside the window costs pagel.uz nothing; a sync never overwrites a
 * project with a change still waiting.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectRepositoryTest {
    private lateinit var db: PagelDatabase
    private var now = 1_000_000L
    private lateinit var tasks: TaskRepository
    private lateinit var repo: ProjectRepository

    private fun task(id: String, project: String, sort: Double, status: String = "open", every: String? = null) =
        TaskEntity(id, project, "Now", sort, "Task $id", null, status, 2, false, null, every, null, null, "", 1, lastModified = 0, phaseNow = true)

    @Before fun setUp() = runBlocking {
        db = inMemoryDb()
        tasks = TaskRepository(db.dao(), { }, { now })
        repo = ProjectRepository(db.dao(), tasks, { }, { now })
        db.dao().upsertProjects(listOf(ProjectEntity("alpha", "Alpha", "mint", 0, 2), ProjectEntity("beta", "Beta", null, 1, 1)))
        db.dao().upsertTasks(listOf(task("a1", "alpha", 1.0), task("a2", "alpha", 2.0), task("b1", "beta", 1.0)))
    }

    @After fun tearDown() = db.close()

    private suspend fun ops() = db.dao().waitingOutbox().filter { it.op == OutboxEntity.PROJECT }
    private fun body(op: OutboxEntity) = Json.parseToJsonElement(op.body!!).jsonObject

    @Test fun aNewProjectIsOnThePhoneAtOnceAndOnePostWaits() = runBlocking {
        val name = repo.create("Learn Unity")
        assertEquals("learn-unity", name)
        val p = db.dao().projectOnce(name)!!
        assertEquals("Learn Unity", p.title)
        assertEquals("blue", p.color) // the next swatch after 2 projects: mint, blue…
        val op = ops().single()
        assertEquals("POST", body(op)["method"]!!.jsonPrimitive.content)
        assertEquals("learn-unity", body(op)["body"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("learn-unity-2", slugOf("Learn Unity", listOf("learn-unity")))
        assertEquals("p-42", slugOf("42", emptyList()))
    }

    @Test fun renameColourIconAreaParentAndGoalsSaveInRoomAndQueue() = runBlocking {
        repo.rename("alpha", "Alpha one")
        repo.color("alpha", "h212")
        repo.icon("alpha", "emoji:🎮")
        repo.area("alpha", "Games")
        repo.parent("alpha", "beta")
        repo.goals("alpha", listOf(ProjectGoal("g1", "Ship v1", listOf(Milestone("m1", "Beta out", "2027-03-01")))))
        val p = db.dao().projectOnce("alpha")!!
        assertEquals(listOf("Alpha one", "h212", "emoji:🎮", "Games", "beta"), listOf(p.title, p.color, p.icon, p.area, p.parent))
        assertEquals("Beta out", goalsOf(p.goals).single().milestones.single().title)
        assertEquals(6, ops().size)
        assertTrue(ops().all { it.taskId == "project:alpha" && body(it)["method"]!!.jsonPrimitive.content == "PATCH" })
    }

    @Test fun aSyncKeepsAProjectWithAChangeStillWaiting() = runBlocking {
        repo.color("alpha", "violet")
        db.dao().applySync(true, listOf(ProjectEntity("alpha", "Alpha", "mint", 0, 2), ProjectEntity("beta", "Beta", null, 1, 1)), emptyList(), emptyList(), emptyList(), SyncStateEntity(cursor = 9, lastSyncAt = 0))
        assertEquals("violet", db.dao().projectOnce("alpha")!!.color)
        // Once sent (no op left), pagel.uz's copy wins again.
        db.dao().takeBackOp(ops().single().id)
        db.dao().applySync(true, listOf(ProjectEntity("alpha", "Alpha", "mint", 0, 2), ProjectEntity("beta", "Beta", null, 1, 1)), emptyList(), emptyList(), emptyList(), SyncStateEntity(cursor = 10, lastSyncAt = 0))
        assertEquals("mint", db.dao().projectOnce("alpha")!!.color)
    }

    @Test fun mergeMovesEveryTaskHidesTheOldOneAndUndoInsideTheWindowSendsNothing() = runBlocking {
        val op = repo.merge("alpha", "beta")
        assertNotNull(op)
        assertEquals(listOf("a1", "a2", "b1").sorted(), db.dao().taskIdsOf("beta").sorted())
        assertTrue(db.dao().projectOnce("alpha")!!.hidden)
        assertTrue(body(ops().single())["hold"]!!.jsonPrimitive.content == "true")
        repo.undoMerge("alpha", "beta", op)
        assertEquals(listOf("a1", "a2"), db.dao().taskIdsOf("alpha"))
        assertFalse(db.dao().projectOnce("alpha")!!.hidden)
        assertTrue(ops().isEmpty())
    }

    @Test fun deleteHoldsForUndoAndUndoBringsItBack() = runBlocking {
        val op = repo.delete("alpha", dropTasks = false)
        assertTrue(db.dao().projectOnce("alpha")!!.hidden)
        assertEquals("inbox", body(ops().single())["body"]!!.jsonObject["tasks"]!!.jsonPrimitive.content)
        repo.undoDelete("alpha", op)
        assertFalse(db.dao().projectOnce("alpha")!!.hidden)
        assertTrue(ops().isEmpty())
        // After the window it already left: Undo sends a restore.
        val op2 = repo.delete("alpha", dropTasks = true)
        db.dao().takeBackOp(op2!!)
        now += UNDO_WINDOW_MS + 1
        repo.undoDelete("alpha", op2)
        assertEquals("false", body(ops().single())["body"]!!.jsonObject["deleted"]!!.jsonPrimitive.content)
    }

    @Test fun finishSavesTheShippedLineAndReopenClearsIt() = runBlocking {
        repo.finish("alpha", "  Shipped the alpha ")
        assertEquals("Shipped the alpha", db.dao().projectOnce("alpha")!!.shipped)
        assertNotNull(db.dao().projectOnce("alpha")!!.finishedAt)
        repo.reopen("alpha")
        assertNull(db.dao().projectOnce("alpha")!!.finishedAt)
    }

    @Test fun projectOrderSendsOnlyTheMovedOnes() = runBlocking {
        repo.order(listOf("beta", "alpha"))
        assertEquals(listOf(1, 0), listOf(db.dao().projectOnce("alpha")!!.sort, db.dao().projectOnce("beta")!!.sort))
        assertEquals(2, ops().size)
    }

    @Test fun aTaskMovesSortsAndLinksThroughThePanelOutbox() = runBlocking {
        tasks.move("a1", "beta")
        tasks.sort("a2", 0.5)
        tasks.milestone("a2", "m1")
        assertEquals("beta", db.dao().taskOnce("a1")!!.project)
        assertEquals(0.5, db.dao().taskOnce("a2")!!.sort, 0.0)
        assertEquals("m1", db.dao().taskOnce("a2")!!.milestone)
        val sets = db.dao().waitingOutbox().filter { it.op == OutboxEntity.PATCH }.map { Json.parseToJsonElement(it.body!!).jsonObject["set"]!!.jsonObject.keys.single() }
        assertEquals(listOf("project", "sort", "milestone"), sets)
    }

    @Test fun turnIntoProjectMakesTheOpenStepsTasksAndDropsTheTask() = runBlocking {
        db.dao().upsertSteps(listOf(StepEntity("a1", 0, "Open Unity", null, false), StepEntity("a1", 1, "Make a scene", null, false), StepEntity("a1", 2, "Old", null, true)))
        val added = mutableListOf<Pair<String, String>>()
        val name = repo.turnIntoProject("a1") { text, project -> added += text to project; "local_x" }
        assertEquals("task-a1", name)
        assertEquals(listOf("Open Unity" to "task-a1", "Make a scene" to "task-a1"), added)
        assertEquals("dropped", db.dao().taskOnce("a1")!!.status)
        // A habit stays a habit.
        db.dao().upsertTasks(listOf(task("h1", "alpha", 3.0, every = "day")))
        assertNull(repo.turnIntoProject("h1") { _, _ -> "" })
    }

    @Test fun theFirstTickIsCountedOncePerDay() {
        Kpi.opened(1_000)
        assertEquals(4_000L, Kpi.firstTick(5_000, "2026-10-29"))
        assertNull(Kpi.firstTick(6_000, "2026-10-29"))
        assertEquals(9_000L, Kpi.firstTick(10_000, "2026-10-30"))
        Kpi.reset()
    }

    @Test fun dragMathAndListShape() {
        // Rows 100 px high: 160 px down passes one centre and a half → slot 2; up from 3 by 60 px → slot 2.
        assertEquals(2, dropIndex(listOf(100, 100, 100, 100), 0, 160f))
        assertEquals(2, dropIndex(listOf(100, 100, 100, 100), 3, -60f))
        assertEquals(2.5, sortAfterMove(listOf(1.0, 2.0, 3.0), 0, 1), 0.0)
        assertEquals(0.0, sortAfterMove(listOf(1.0, 2.0, 3.0), 2, 0), 0.0)
        assertEquals(4.0, sortAfterMove(listOf(1.0, 2.0, 3.0), 0, 2), 0.0)
        val all = listOf(
            ProjectEntity("a", "A", null, 0, 1), ProjectEntity("b", "B", null, 1, 1, area = "Games"),
            ProjectEntity("c", "C", null, 2, 1, area = "Games", parent = "b"), ProjectEntity("d", "D", null, 3, 0, finishedAt = "2026-10-29T00:00:00Z"),
        )
        val lines = projectLines(all)
        assertEquals(listOf("a", "area:Games", "b", "c>"), lines.map { if (it is ListLine.Item) it.p.name + if (it.child) ">" else "" else "area:" + (it as ListLine.Area).title })
        assertEquals(listOf("b", "a", "c"), reordered(lines, 0, 2))
    }

    @Test fun aCustomHueUsesTheSwatchRecipe() {
        assertEquals("h212", TileColors.nameFor("x", "h212"))
        assertEquals("slate", TileColors.nameFor("x", "h999"))
        val (a, b) = TileColors.stops("x", "h212")
        assertEquals(TileColors.oklch(0.8, 0.13, 212.0), a)
        assertEquals(TileColors.oklch(0.6, 0.16, 222.0), b)
    }

    @Test fun milestoneProgressCountsLinkedTasks() {
        val g = projectGroups(listOf(task("x", "p", 1.0).copy(milestone = "m1"), task("y", "p", 2.0, status = "done").copy(milestone = "m1"), task("z", "p", 3.0)))
        assertEquals(1 to 2, milestoneProgress(g, "m1"))
    }
}
