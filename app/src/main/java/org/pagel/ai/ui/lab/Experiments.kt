package org.pagel.ai.ui.lab

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One experiment on the Laboratory shelf. Add a card by adding one entry to [Experiments.all]. */
data class Experiment(val id: String, val title: String, val line: String, val defaultOn: Boolean)

private val Context.experimentsStore by preferencesDataStore(name = "pagel_experiments")

/**
 * The Laboratory's registry and its switches, in one small DataStore file. The app asks [isOn] before it uses an
 * experiment; the card's switch calls [setOn]; the card's quiet bottom line is the experiment's [note].
 */
object Experiments {
    const val BLACK_HOLE = "black_hole"

    val all: List<Experiment> = listOf(
        Experiment(
            BLACK_HOLE, "Black hole",
            "Delete one task in 1.3 s or clear all in 1.9 s: the camera opens a hole and the list is pulled into it.",
            defaultOn = true,
        ),
    )

    /** The one function everything reads an experiment's switch through. */
    fun isOn(context: Context, id: String): Flow<Boolean> {
        val default = all.firstOrNull { it.id == id }?.defaultOn ?: false
        return context.experimentsStore.data.map { it[booleanPreferencesKey("on_$id")] ?: default }
    }

    suspend fun setOn(context: Context, id: String, on: Boolean) {
        context.experimentsStore.edit { it[booleanPreferencesKey("on_$id")] = on }
    }

    /** A short line an experiment keeps for its card (the black hole's last janky % and first frame). */
    fun note(context: Context, id: String): Flow<String?> = context.experimentsStore.data.map { it[stringPreferencesKey("note_$id")] }

    suspend fun setNote(context: Context, id: String, text: String) {
        context.experimentsStore.edit { it[stringPreferencesKey("note_$id")] = text }
    }
}
