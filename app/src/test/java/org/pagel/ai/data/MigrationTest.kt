package org.pagel.ai.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
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
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
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
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v5 → v6 (Rituals): a phone on 0.8.x keeps its tasks and waiting edits; the dumps and the rituals' state start empty. */
    @Test fun fiveToSixKeepsTasksAndAddsEmptyRituals() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/5.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(5, schema["version"]!!.jsonPrimitive.content.toInt())

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified, phaseSort, phaseNow)
                VALUES ('t_s', 'me', 'Now', 1.0, 'Call the bank', NULL, 'open', 1, 1, NULL, NULL, NULL, NULL, '2026-10-27T10:00:00Z', 1, 'pending', 5, 0, 1)""")
            old.execSQL("""INSERT INTO outbox (taskId, op, createdAt, attempts, refused, body, waitsForServer) VALUES ('t_s', 'patch', 7, 0, NULL, '{"set":{"star":false},"was":{"star":true}}', 0)""")
            old.execSQL("INSERT INTO habit_reminders (taskId, enabled) VALUES ('t_h', 1)")
            old.version = 5
        }

        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("Call the bank", db.dao().taskOnce("t_s")!!.text)
            assertEquals("patch", db.dao().waitingOutbox().single().op)
            assertEquals(1, db.dao().remindersOnce().size)
            assertNull(db.rituals().stateOnce())
            assertNull(db.rituals().sweepOnce("anything"))
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v7 (round B): a habit's times a day and a day's taps; an old habit is once a day and its old ticks count 1. */
    @Test fun sixToSevenKeepsHabitsAndTheirTicksAsOnceADay() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/6.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(6, schema["version"]!!.jsonPrimitive.content.toInt())

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified, phaseSort, phaseNow)
                VALUES ('t_h', 'me', 'Now', 1.0, 'Log food', NULL, 'open', 2, 0, '2026-10-28 13:00', 'day', NULL, NULL, '2026-10-27T10:00:00Z', 1, 'synced', 5, 0, 1)""")
            old.execSQL("INSERT INTO habit_ticks (day, taskId) VALUES ('2026-10-27', 't_h')")
            old.version = 6
        }

        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals(1, db.dao().taskOnce("t_h")!!.times)
            assertEquals(listOf(org.pagel.ai.data.db.HabitTickEntity("2026-10-27", "t_h", 1)), db.dao().habitTicksOf("t_h"))
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
    /** v8 (round D, Habits 2.0): a 3x habit and its taps come through; no group, no prefs, no freezes yet. */
    @Test fun sevenToEightKeepsHabitsAndAddsPrefsAndFreezes() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/7.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(7, schema["version"]!!.jsonPrimitive.content.toInt())

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified, phaseSort, phaseNow, times)
                VALUES ('t_h', 'me', 'Now', 1.0, 'Log food', NULL, 'open', 2, 0, '2026-10-28 13:00', 'day', NULL, NULL, '2026-10-27T10:00:00Z', 1, 'synced', 5, 0, 1, 3)""")
            old.execSQL("INSERT INTO habit_ticks (day, taskId, n) VALUES ('2026-10-27', 't_h', 2)")
            old.version = 7
        }

        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val t = db.dao().taskOnce("t_h")!!
            assertEquals(3, t.times)
            assertNull(t.habitGroup)
            assertEquals(listOf(org.pagel.ai.data.db.HabitTickEntity("2026-10-27", "t_h", 2)), db.dao().habitTicksOf("t_h"))
            assertNull(db.habitPrefs().prefOnce("t_h"))
            assertEquals(emptyList<org.pagel.ai.data.db.HabitFreezeEntity>(), db.habitPrefs().freezesOnce())
            db.habitPrefs().freeze(org.pagel.ai.data.db.HabitFreezeEntity("2026-10-27", "t_h"))
            assertEquals(1, db.habitPrefs().freezesOnce().size)
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
    /** v9 (round D item 2): the day's Inbox decisions and the quick notes start empty; the habits' prefs come through. */
    @Test fun eightToNineAddsTheSortedLogAndNotes() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/8.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(8, schema["version"]!!.jsonPrimitive.content.toInt())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO habit_prefs (taskId, emoji, after, identity, minutes) VALUES ('t_h', 'coffee', NULL, 'I train', 10)")
            old.version = 8
        }
        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("coffee", db.habitPrefs().prefOnce("t_h")!!.emoji)
            db.inboxLog().logSorted(org.pagel.ai.data.db.InboxSortedEntity("inbox/a.md", "2026-10-29", "note", "A claim", "ababil", "One line", null, 3, 5))
            db.inboxLog().replaceNotes("ababil", listOf(org.pagel.ai.data.db.QuickNoteEntity("ababil", 0, "RunCam", null, null, "2026-10-28")))
            assertEquals(1, db.inboxLog().sortedOn("2026-10-29").first().size)
            assertEquals("RunCam", db.inboxLog().notes("ababil").first().single().text)
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v10 (core round, projects): a project keeps its row and gets no icon, no area, no goals ("[]"), not hidden; tasks no milestone. */
    @Test fun nineToTenAddsTheProjectFields() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/9.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(9, schema["version"]!!.jsonPrimitive.content.toInt())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO projects (name, title, color, sort, openCount) VALUES ('ababil', 'ABABIL', 'red', 1, 4)")
            old.version = 9
        }
        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val p = db.dao().projectOnce("ababil")!!
            assertEquals("red", p.color)
            assertEquals("[]", p.goals)
            assertEquals(false, p.hidden)
            assertEquals(null, p.icon)
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v11 (the Namoz page): projects come through; marks, his learning list and the settings start empty (all off). */
    @Test fun tenToElevenAddsEmptyNamozTables() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/10.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(10, schema["version"]!!.jsonPrimitive.content.toInt())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO projects (name, title, color, sort, openCount) VALUES ('ababil', 'ABABIL', 'red', 1, 4)")
            old.version = 10
        }
        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("red", db.dao().projectOnce("ababil")!!.color)
            assertNull(db.namoz().settingsOnce())
            assertEquals(0, db.namoz().marks("2026-01-01").first().size)
            assertEquals(0, db.namoz().learning().first().size)
            db.namoz().setMark(org.pagel.ai.data.db.NamozMarkEntity("2026-11-01", "ASR", org.pagel.ai.data.db.NamozMarkEntity.MOSQUE))
            assertEquals("mosque", db.namoz().marks("2026-11-01").first().single().how)
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v11 → v12: the adopted-ids table appears empty and takes a row; Namoz marks stay. */
    @Test fun elevenToTwelveAddsTheAdoptedIdsTable() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/11.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(11, schema["version"]!!.jsonPrimitive.content.toInt())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO projects (name, title, color, sort, openCount) VALUES ('ababil', 'ABABIL', 'red', 1, 4)")
            old.execSQL("INSERT INTO namoz_marks (day, prayer, how) VALUES ('2026-11-01', 'ASR', 'mosque')")
            old.version = 11
        }
        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("red", db.dao().projectOnce("ababil")!!.color)
            assertEquals("mosque", db.namoz().marks("2026-11-01").first().single().how)
            assertNull(db.dao().adoptedId("local_1"))
            db.dao().recordAdopted(org.pagel.ai.data.db.AdoptedIdEntity("local_1", "t_9", 1L))
            assertEquals("t_9", db.dao().currentId("local_1"))
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** v12 → v13 (core round 4): a task keeps its fields and gets no micro-steps; Today's order and state start empty. */
    @Test fun twelveToThirteenAddsMicroStepsAndTodaysOwnOrder() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/org.pagel.ai.data.db.PagelDatabase/12.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(12, schema["version"]!!.jsonPrimitive.content.toInt())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO tasks (id, project, phase, sort, text, title, status, priority, star, due, every, doneWhen, doneAt, updatedAt, serverVersion, syncStatus, lastModified) " +
                "VALUES ('t_1', 'me', 'Now', 1.0, 'Read 2 pages', NULL, 'open', 2, 1, NULL, NULL, NULL, NULL, '2026-10-30T05:00:00Z', 1, 'synced', 0)")
            old.version = 12
        }
        val db = Room.databaseBuilder(ctx, PagelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val t = db.dao().taskOnce("t_1")!!
            assertEquals("Read 2 pages", t.text)
            assertEquals("[]", t.micro)
            assertEquals(emptyList<Any>(), db.dao().todaySortsOnce("2026-10-30"))
            assertNull(db.dao().todayStateOnce())
            db.dao().setTodaySort(org.pagel.ai.data.db.TodaySortEntity("t_1", "2026-10-30", -1.0))
            assertEquals(-1.0, db.dao().todaySortsOnce("2026-10-30").single().sort, 0.0)
            assertEquals(PagelDatabase.VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
