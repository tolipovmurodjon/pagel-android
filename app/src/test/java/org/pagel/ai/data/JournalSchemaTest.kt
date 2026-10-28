package org.pagel.ai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * journal.db v1 (its own file; Room exports its schema to app/schemas when CI compiles): no column can hold a word,
 * a day or a mood in the clear; and notebook.db, the second brain's file, has no journal or Compass table at all.
 */
class JournalSchemaTest {
    private fun schema(name: String) = File("schemas/$name/1.json").also { assertTrue("Room did not export ${it.path}", it.exists()) }
        .let { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }

    @Test fun noJournalColumnCanHoldWordsADayOrAMood() {
        val words = setOf("title", "text", "body", "words", "day", "date", "mood", "feeling", "note", "answer")
        for (e in schema("org.pagel.ai.data.journal.JournalDatabase")["entities"]!!.jsonArray) {
            val t = e.jsonObject
            for (f in t["fields"]!!.jsonArray) {
                val col = f.jsonObject["columnName"]!!.jsonPrimitive.content.lowercase()
                assertTrue("journal.db column ${t["tableName"]}.$col could hold words in the clear", col !in words)
            }
        }
    }

    @Test fun theNotebookDatabaseHasNoJournalOrCompassTable() {
        val tables = schema("org.pagel.ai.data.notebook.NotebookDatabase")["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        assertTrue(tables.toString(), tables.none { it.contains("journal") || it.contains("compass") })
    }
}
