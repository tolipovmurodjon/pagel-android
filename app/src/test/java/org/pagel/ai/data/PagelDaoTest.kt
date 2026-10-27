package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.SyncStateEntity
import org.pagel.ai.data.db.TaskEntity
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PagelDaoTest {
    private val db = inMemoryDb()
    private val dao = db.dao()

    @After fun tearDown() = db.close()

    private fun t(id: String, status: String = "open") = TaskEntity(id, "pagel", "Now", 1.0, "task $id", null, status, 2, false, null, null, null, null, "", 1, lastModified = 0)
    private val projects = listOf(ProjectEntity("pagel", "PAGEL", null, 0, 1))

    @Test fun `a full copy replaces everything, even more tasks than SQLite takes ids in one query`() = runBlocking {
        val many = (1..1_200).map { t("t_$it") }
        dao.applySync(true, projects, many, many.map { StepEntity(it.id, 0, "s", null, false) }, emptyList(), SyncStateEntity(cursor = 5, lastSyncAt = 1))
        assertEquals(1_200, dao.taskCount())
        dao.applySync(true, projects, listOf(t("t_new")), emptyList(), emptyList(), SyncStateEntity(cursor = 6, lastSyncAt = 2))
        assertEquals(1, dao.taskCount())
        assertEquals(emptyList<StepEntity>(), dao.steps("t_1").first())
    }

    @Test fun `a delta touches only the tasks it names and removes what the server lost`() = runBlocking {
        dao.applySync(true, projects, listOf(t("a"), t("b"), t("c")), listOf(StepEntity("a", 0, "old", null, false)), emptyList(), SyncStateEntity(cursor = 1, lastSyncAt = 1))
        dao.applySync(false, projects, listOf(t("a", "done")), listOf(StepEntity("a", 0, "new", null, true)), listOf("c"), SyncStateEntity(cursor = 2, lastSyncAt = 2))
        assertEquals("done", dao.task("a").first()!!.status)
        assertEquals("open", dao.task("b").first()!!.status)
        assertNull(dao.task("c").first())
        assertEquals(listOf("new"), dao.steps("a").first().map { it.text })
        assertEquals(2L, dao.syncState()!!.cursor)
    }

    @Test fun `a project the server dropped leaves the phone too`() = runBlocking {
        dao.applySync(true, projects + ProjectEntity("old", "Old", null, 1, 0), emptyList(), emptyList(), emptyList(), SyncStateEntity(cursor = 1, lastSyncAt = 1))
        dao.applySync(false, projects, emptyList(), emptyList(), emptyList(), SyncStateEntity(cursor = 2, lastSyncAt = 2))
        assertEquals(listOf("pagel"), dao.projects().first().map { it.name })
    }
}
