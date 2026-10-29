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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.compass.CompassDatabase
import org.robolectric.annotation.Config
import java.io.File

/**
 * compass.db v1 (its own file, apart from pagel.db, so rounds C and D never renumber it): the database built from the
 * exported schema (app/schemas/…CompassDatabase/1.json, the shape phones will keep) opens in the app with its rows,
 * and no table has a column that could hold a word in the clear.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompassSchemaTest {
    private val schemaFile = File("schemas/org.pagel.ai.data.compass.CompassDatabase/1.json")

    @Test fun versionOneFromItsExportedSchemaOpensWithItsRows() = runBlocking {
        assertTrue("Room did not export the Compass schema to ${schemaFile.path}", schemaFile.exists())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "compass-v1-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        assertEquals(1, schema["version"]!!.jsonPrimitive.content.toInt())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val t = e.jsonObject
                val table = t["tableName"]!!.jsonPrimitive.content
                old.execSQL(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                t["indices"]?.jsonArray?.forEach { i -> old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO compass_items (id, kind, parent, blob, starts, ends, sort, deleted, syncStatus, lastModified, serverVersion) VALUES ('g_abcdefgh', 'purpose', NULL, 'AQID', NULL, NULL, 1, 0, 'synced', 5, 1)")
            old.version = 1
        }
        val db = Room.databaseBuilder(ctx, CompassDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val row = db.dao().items().first().single()
            assertEquals("g_abcdefgh", row.id)
            assertEquals("AQID", row.blob)
            assertEquals(1, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /**
     * compass.db v1 shipped in 0.3.30 (app-v0.12.1): its schema file is committed and its identity is pinned here. A
     * changed entity makes Room write a new 1.json with another hash and this fails: bump to v2 with a Migration
     * (re-check N4).
     */
    @Test fun versionOneIsTheShippedSchema() {
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        assertEquals(SHIPPED_V1, schema["identityHash"]!!.jsonPrimitive.content)
    }

    private companion object {
        const val SHIPPED_V1 = "d7f4df5af55768ec5edef270d5a41dbc"
    }

    @Test fun noColumnCanHoldAGoalsWords() {
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        val words = setOf("title", "text", "why", "mypart", "my_part", "outcome", "note", "answer", "words", "body")
        for (e in schema["entities"]!!.jsonArray) {
            val t = e.jsonObject
            for (f in t["fields"]!!.jsonArray) {
                val col = f.jsonObject["columnName"]!!.jsonPrimitive.content.lowercase()
                assertTrue("compass.db column ${t["tableName"]}.$col could hold words in the clear", col !in words)
            }
        }
    }
}
