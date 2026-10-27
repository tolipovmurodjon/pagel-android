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
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
