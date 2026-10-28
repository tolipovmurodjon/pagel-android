package org.pagel.ai.data.inbox

import kotlinx.coroutines.flow.Flow
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.InboxDao
import org.pagel.ai.data.db.InboxLogDao
import org.pagel.ai.data.db.InboxSortedEntity
import org.pagel.ai.data.db.NoteCount
import org.pagel.ai.data.db.QuickNoteEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Round D item 2, Inbox "where did it go" (Boss: "the inbox adding didn't add right… where do I add them"): every
 * decision is logged on the phone with where it went, so the toast can say it and "Sorted today" can list it; Undo
 * works while the choice still waits in the outbox. The projects' quick notes come from pagel.uz and stay offline.
 * Conflict rule: pagel.uz's notes replace the saved copy of that project on each refresh.
 */
class WhereItWent(
    private val log: InboxLogDao,
    private val inbox: InboxDao,
    private val api: PagelApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun today(): String = Instant.ofEpochMilli(clock()).atZone(ZoneId.of("Asia/Tashkent")).toLocalDate().toString()

    fun sortedToday(): Flow<List<InboxSortedEntity>> = log.sortedOn(today())

    suspend fun record(file: String, action: String, claim: String, project: String?, line: String?, due: String?, opId: Long) {
        log.forgetBefore(today())
        log.logSorted(InboxSortedEntity(file, today(), action, claim, project, line, due, opId, clock()))
    }

    /** Undo from "Sorted today": true while the choice had not left the phone (the card comes back on top). */
    suspend fun undo(s: InboxSortedEntity): Boolean {
        if (!inbox.undo(s.opId, s.file, s.action == "keep")) return false
        log.unlog(s.file)
        return true
    }

    fun notes(project: String): Flow<List<QuickNoteEntity>> = log.notes(project)
    fun noteCounts(): Flow<List<NoteCount>> = log.noteCounts()

    /** pagel.uz's notes for [project]; offline the saved ones stay and nothing is said. */
    suspend fun refreshNotes(account: Account, project: String): Outcome<Unit> =
        when (val r = api.notes(account.serverUrl, account.token, project)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> {
                log.replaceNotes(project, r.value.notes.mapIndexed { i, n -> QuickNoteEntity(project, i, n.text, n.link, n.picture, n.day) })
                Outcome.Ok(Unit)
            }
        }

    /** The projects that have notes on pagel.uz, each refreshed into Room. */
    suspend fun refreshAll(account: Account): Outcome<Unit> =
        when (val r = api.noteProjects(account.serverUrl, account.token)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> { for (p in r.value.projects) refreshNotes(account, p.project); Outcome.Ok(Unit) }
        }
}
