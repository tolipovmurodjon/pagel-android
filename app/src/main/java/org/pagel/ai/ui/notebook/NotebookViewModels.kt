package org.pagel.ai.ui.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.notebook.Grade
import org.pagel.ai.data.notebook.Hit
import org.pagel.ai.data.notebook.HitGroup
import org.pagel.ai.data.notebook.NoteTemplates
import org.pagel.ai.data.notebook.NotebookRepository
import org.pagel.ai.data.notebook.OpenNote
import org.pagel.ai.data.notebook.RecallCard
import org.pagel.ai.data.notebook.RecallSession
import org.pagel.ai.data.notebook.ResourceDto
import org.pagel.ai.data.notebook.SearchLogic
import org.pagel.ai.data.notebook.VaultFolderEntity
import org.pagel.ai.data.notebook.VaultNoteEntity
import java.time.LocalDate
import javax.inject.Inject

/** A note row as lists show it: its title, its folder, whether it waits to be sent. */
data class NoteRow(val path: String, val title: String, val folder: String, val pending: Boolean)

internal fun VaultNoteEntity.row() = NoteRow(path, title, path.substringBeforeLast('/', ""), syncStatus == org.pagel.ai.data.notebook.PENDING)

/** Everything the Notebook's first screen draws. */
data class NotebookUi(
    val query: String = "",
    val groups: List<HitGroup> = emptyList(),
    /** True from the first keystroke until pagel.uz's answer (or the offline verdict) came: "nothing found" waits for it. */
    val asking: Boolean = false,
    val due: List<RecallCard> = emptyList(),
    val recent: List<NoteRow> = emptyList(),
    val openFolders: Int = 0,
    val waiting: Int = 0,
)

private suspend fun AccountStore.paired(): Account? = (load() as? AccountState.Paired)?.account

/** The Notebook tab (Phase 6b): the search box, Review 5, the journal's door, the folders, recent notes. */
@HiltViewModel
class NotebookViewModel @Inject constructor(private val repo: NotebookRepository, private val accounts: AccountStore) : ViewModel() {
    private val query = MutableStateFlow("")
    private val hits = MutableStateFlow<List<Hit>>(emptyList())
    private val asking = MutableStateFlow(false)
    private var searching: Job? = null

    val notices: Flow<String> = repo.notices

    val ui: StateFlow<NotebookUi> = combine(
        combine(query, hits, asking) { q, h, a -> Triple(q, h, a) },
        repo.cards,
        repo.notes,
        repo.folders,
        repo.waiting,
    ) { (q, h, a), cards, notes, folders, waiting ->
        NotebookUi(
            query = q, groups = SearchLogic.group(h), asking = a, due = cards,
            recent = notes.take(RECENT).map { it.row() }, openFolders = folders.count { it.allowed && it.switched }, waiting = waiting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookUi())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            accounts.paired()?.let { runCatching { repo.refresh(it) } }
            repo.nudge()
        }
    }

    /**
     * Respond first: the phone's own copy answers on the keystroke; pagel.uz's answer (it ranks better and also has
     * Resources and notes the phone never opened) merges in 300 ms after typing stops.
     */
    fun search(q: String) {
        query.value = q
        searching?.cancel()
        if (q.isBlank()) { hits.value = emptyList(); asking.value = false; return }
        asking.value = true
        searching = viewModelScope.launch {
            val here = repo.searchHere(q)
            hits.value = here
            delay(DEBOUNCE_MS)
            val there = accounts.paired()?.let { repo.searchThere(it, q) }
            if (there != null) hits.value = SearchLogic.merge(there, here)
            asking.value = false
        }
    }

    suspend fun quickNote(ref: String) = ref.substringBefore('#').let { p -> ref.substringAfter('#').toIntOrNull()?.let { repo.quickNote(p, it) } }

    private companion object {
        const val RECENT = 8
        const val DEBOUNCE_MS = 300L
    }
}

/** Notebook → Folders and one folder's notes. */
@HiltViewModel
class FoldersViewModel @Inject constructor(private val repo: NotebookRepository, private val accounts: AccountStore) : ViewModel() {
    val folders: StateFlow<List<VaultFolderEntity>?> = repo.folders.map<List<VaultFolderEntity>, List<VaultFolderEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val notes: StateFlow<List<NoteRow>> = repo.notes.map { all -> all.map { it.row() }.sortedBy { it.title.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notices: Flow<String> = repo.notices

    init {
        viewModelScope.launch { accounts.paired()?.let { runCatching { repo.refresh(it) } } }
    }

    fun setAllowed(path: String, on: Boolean) { viewModelScope.launch { repo.setAllowed(path, on) } }
}

/** The reader and the writer: one note (or one resource), its links resolved against the notes the phone knows. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NoteViewModel @Inject constructor(private val repo: NotebookRepository, private val accounts: AccountStore) : ViewModel() {
    private val path = MutableStateFlow<String?>(null)
    private val res = MutableStateFlow<ResourceDto?>(null)
    val notices: Flow<String> = repo.notices

    val note: StateFlow<OpenNote?> = path.filterNotNull().flatMapLatest { repo.note(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val resource: StateFlow<ResourceDto?> = res
    /** Every note path the phone knows (wikilinks resolve against these) and the open folders a new note can go in. */
    val paths: StateFlow<List<String>> = repo.notes.map { all -> all.map { it.path } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val writable: StateFlow<List<String>> = repo.folders.map { all -> all.filter { it.allowed && !it.locked }.map { it.path } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var files: List<String> = emptyList()

    fun bind(p: String) {
        if (path.value == p) return
        path.value = p
        viewModelScope.launch {
            files = repo.files()
            accounts.paired()?.let { runCatching { repo.open(it, p) } }
        }
    }

    fun bindResource(id: String) {
        viewModelScope.launch { res.value = accounts.paired()?.let { repo.resource(it, id) } }
    }

    /** A picture embed's bytes (null offline, or not in an open folder). */
    suspend fun picture(target: String): ByteArray? {
        if (files.isEmpty()) files = repo.files()
        val file = org.pagel.ai.data.notebook.Markdown.resolveFile(target, files) ?: return null
        return accounts.paired()?.let { repo.picture(it, file) }
    }

    /** Where a new note goes: [folder]/[title].md, " 2", " 3"… when the name is taken. */
    fun newPath(folder: String, title: String): String {
        val taken = paths.value.toSet()
        val first = NoteTemplates.pathIn(folder, title)
        if (first !in taken) return first
        return (2..99).map { NoteTemplates.pathIn(folder, "$title $it") }.firstOrNull { it !in taken } ?: first
    }

    fun save(p: String, body: String, done: (String) -> Unit) {
        viewModelScope.launch { done(repo.save(p, body)) }
    }

    fun today(): LocalDate = LocalDate.now(org.pagel.ai.data.compass.CompassRepository.TASHKENT)
}

/** Review 5: the session is fixed when it starts; each grade leaves Room at once and waits in the outbox. */
@HiltViewModel
class RecallViewModel @Inject constructor(private val repo: NotebookRepository) : ViewModel() {
    private val now = MutableStateFlow<RecallSession?>(null)
    val session: StateFlow<RecallSession?> = now

    init {
        viewModelScope.launch { now.value = RecallSession.start(repo.cards.first(), repo.nowIso()) }
    }

    fun reveal() { now.value = now.value?.reveal() }

    fun grade(g: Grade) {
        val s = now.value ?: return
        val card = s.current ?: return
        if (!s.revealed) return
        now.value = s.grade(g)
        viewModelScope.launch { repo.grade(card.id, g) }
    }
}
