package org.pagel.ai.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.PagelDatabase
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room v2 → v3 on a real file: the database is built from the exported v2 schema (app/schemas/…/2.json, the shape the
 * phones run today), filled with a task and a waiting tick, then opened by the v3 app. Room checks the migrated
 * tables against v3's own schema and throws if they differ; the rows must come through with the new defaults.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    @Test fun twoToThreeKeepsTasksAndTicks() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(2, schema["version"]!!.jsonPrimitive.content.toInt())

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified)
                VALUES ('t_1', 'ababil', 'Now', 1.0, 'Measure TAU', NULL, 'open', 1, 1, NULL, NULL, NULL, NULL, '2026-10-27T10:00:00Z', 3, 'pending', 5)""")
            old.execSQL("INSERT INTO outbox (taskId, op, createdAt, attempts, refused) VALUES ('t_1', 'done', 7, 0, NULL)")
            old.version = 2
        }

        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val t = db.dao().taskOnce("t_1")!!
            assertEquals("Measure TAU", t.text)
            assertEquals(0, t.phaseSort)
            assertFalse(t.phaseNow)
            val op = db.dao().waitingOutbox().single()
            assertEquals("done", op.op)
            assertNull(op.body)
            assertFalse(op.waitsForServer)
            assertEquals(5, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v3 → v4 (the Inbox): a phone on 0.5.x keeps its tasks and its waiting panel edit; the Inbox starts empty. */
    @Test fun threeToFourKeepsTasksAndAddsAnEmptyInbox() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/3.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(3, schema["version"]!!.jsonPrimitive.content.toInt())

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified, phaseSort, phaseNow)
                VALUES ('t_1', 'ababil', 'Now', 1.0, 'Measure TAU', NULL, 'open', 1, 1, NULL, NULL, NULL, NULL, '2026-10-27T10:00:00Z', 3, 'pending', 5, 2, 1)""")
            old.execSQL("""INSERT INTO outbox (taskId, op, createdAt, attempts, refused, body, waitsForServer) VALUES ('t_1', 'patch', 7, 0, NULL, '{"set":{"priority":0}}', 0)""")
            old.version = 3
        }

        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val t = db.dao().taskOnce("t_1")!!
            assertEquals("Measure TAU", t.text)
            assertEquals(2, t.phaseSort)
            val op = db.dao().waitingOutbox().single()
            assertEquals("patch", op.op)
            assertEquals("""{"set":{"priority":0}}""", op.body)
            assertEquals(0, db.inbox().deckSize())
            assertNull(db.inbox().stateOnce())
            assertEquals(5, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v4 → v5 (Habits): a phone on 0.6.x keeps its Inbox day and deck; the habit tables start empty, sentAt at 0. */
    @Test fun fourToFiveKeepsTheInboxAndAddsEmptyHabits() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/4.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(4, schema["version"]!!.jsonPrimitive.content.toInt())

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified, phaseSort, phaseNow)
                VALUES ('t_h', 'me', 'Now', 1.0, 'Stretch', NULL, 'open', 2, 0, '2026-10-28 07:30', 'day', NULL, NULL, '2026-10-27T10:00:00Z', 1, 'synced', 5, 0, 1)""")
            old.execSQL("""INSERT INTO inbox_items (file, position, saved, source, text, claim, why, project, kind, next, note, guessed, author, posted, later, hasPicture, sortedAs)
                VALUES ('inbox/a.md', 0, '2026-10-27', '', 'post', 'A claim', '', 'me', 'drop', '', '', 1, '', '', NULL, 0, NULL)""")
            old.execSQL("INSERT INTO inbox_state (id, day, goal, sorted, keptToday, cap, minWords, total, fetchedAt) VALUES ('inbox', '2026-10-28', 20, 7, 2, 5, 6, 214, 99)")
            old.version = 4
        }

        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("day", db.dao().taskOnce("t_h")!!.every)
            assertEquals(1, db.inbox().deckSize())
            val s = db.inbox().stateOnce()!!
            assertEquals(7, s.sorted)
            assertEquals(214, s.total)
            assertEquals(0L, s.sentAt)
            assertEquals(listOf("t_h"), db.dao().habitsOnce().map { it.id })
            assertEquals(emptyList<Any>(), db.dao().habitTicksOf("t_h"))
            assertEquals(emptyList<Any>(), db.dao().remindersOnce())
            assertEquals(5, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
