package org.pagel.ai.ui.inbox

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.InboxStateEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.data.inbox.InboxPictures
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.session.Session
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.projects.shortName
import javax.inject.Inject

/** Where a save came from, as the web's small coloured mark (learn-client.tsx Source). */
enum class Site(val label: String) { INSTAGRAM("Instagram"), X("X"), LINKEDIN("LinkedIn"), WEB(""), NOTE("Your note") }

fun siteOf(source: String): Pair<Site, String> {
    val host = runCatching { java.net.URI(source).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    return when {
        host.contains("instagram") -> Site.INSTAGRAM to "Instagram"
        host == "x.com" || host.endsWith(".x.com") || host.contains("twitter") -> Site.X to "X"
        host.contains("linkedin") -> Site.LINKEDIN to "LinkedIn"
        host.isNotEmpty() -> Site.WEB to host
        else -> Site.NOTE to "Your note"
    }
}

/** One card as the screen draws it: the web's card fields, with the AI's pick already decided. */
data class InboxCard(
    val file: String,
    val site: Site,
    val siteName: String,
    val author: String?,
    val posted: String?,
    val project: String,
    val projectTitle: String?,
    val projectColor: String?,
    val claim: String,
    val why: String?,
    val text: String,
    val link: String?,
    val hasPicture: Boolean,
    /** The AI's own guess (null = not read yet), and what the big button offers (a Note when today's keeps are full). */
    val pick: Choice?,
    val guess: Choice?,
    val putOff: Boolean,
    val someday: Boolean,
    val next: String,
    val note: String,
)

data class InboxUi(
    val loading: Boolean = true,
    val cards: List<InboxCard> = emptyList(),
    val sorted: Int = 0,
    val goal: Int = 20,
    val kept: Int = 0,
    val cap: Int = 5,
    val minWords: Int = 6,
    val left: Int = 0,
    val streak: Int = 0,
    val projects: List<ProjectEntity> = emptyList(),
) {
    val full get() = kept >= cap
}

/** A card on its way out: drawn over the deck for its exit motion, after Room already let it go. */
data class Leaving(val id: Long, val card: InboxCard, val choice: Choice, val fromX: Float = 0f, val fromY: Float = 0f)

/** An open choice form (Action, Note, Learn, Do now): the card it is for and the line Boss writes. */
data class Form(val card: InboxCard, val choice: Choice, val line: String, val project: String, val someday: Boolean)

private val POST_HEAD = Regex("^(Instagram|X|LinkedIn) .*?posted [^\\n]*\\n+")

/** The post's body without the "Instagram … posted …" line the importer writes (learn-client.tsx). */
fun postBody(text: String) = text.replace(POST_HEAD, "").trim()

fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/**
 * The Inbox (NATIVE-PLAN Phase 2, the web's /learn): one save at a time; Room answers every choice at once and the
 * outbox sends it. Drop has a 5 s Undo; keeps cheer at 5, 10, 25, 50, 100; 20 sorted a day is the ring's goal.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repo: InboxRepository,
    private val pictures: InboxPictures,
    private val accounts: AccountStore,
    private val toasts: ToastCenter,
    session: Session,
) : ViewModel() {
    private val streak = MutableStateFlow(0)
    private val _leaving = MutableStateFlow<Leaving?>(null)
    val leaving: StateFlow<Leaving?> = _leaving.asStateFlow()
    private val _form = MutableStateFlow<Form?>(null)
    val form: StateFlow<Form?> = _form.asStateFlow()
    private val _pictures = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val picturesShown: StateFlow<Map<String, Bitmap?>> = _pictures.asStateFlow()
    /** A keep's milestone or the day's goal crossed on this screen: the screen fires confetti, the VM says the words. */
    private val _cheer = MutableStateFlow(0)
    val cheer: StateFlow<Int> = _cheer.asStateFlow()
    private var nextLeave = 1L
    private var refreshing = false

    val ui: StateFlow<InboxUi> = combine(repo.deck, repo.state, repo.leaving, session.projects(), streak) { deck, state, leavingCount, projects, run ->
        build(deck, state, leavingCount, projects, run)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUi())

    init {
        refresh()
        // The pictures of the next three cards, ahead of time, so they show at once (and offline).
        viewModelScope.launch {
            repo.deck.map { d -> d.take(3).filter(InboxItemEntity::hasPicture).map(InboxItemEntity::file) }.distinctUntilChanged().collect { files ->
                val account = (accounts.load() as? AccountState.Paired)?.account ?: return@collect
                for (f in files) if (f !in _pictures.value) {
                    // null = the post has none (or it could not be fetched now): the card drops the picture's place.
                    val bmp = pictures.load(account, f)
                    _pictures.update { it + (f to bmp) }
                }
            }
        }
        // The goal crossed here gets the finish moment once, as on the web (learn-client.tsx goalBar).
        viewModelScope.launch {
            var was: Int? = null
            repo.state.collect { s ->
                if (s == null) return@collect
                val before = was
                was = s.sorted
                if (before != null && before < s.goal && s.sorted >= s.goal) {
                    toasts.show("${s.goal} sorted today. Goal done ✓")
                    _cheer.update { it + 1 }
                }
            }
        }
    }

    /** Pulls the next cards; a failure (offline) is silent: the saved deck stays and sorting goes on. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                val account = (accounts.load() as? AccountState.Paired)?.account ?: return@launch
                repo.refresh(account)
            } finally {
                refreshing = false
            }
        }
    }

    /** A choice that needs no words (Later, Drop) leaves now; the others open their form first. */
    fun choose(card: InboxCard, choice: Choice, fromX: Float = 0f, fromY: Float = 0f) {
        val u = ui.value
        if (choice == Choice.KEEP && u.full) return
        if (choice == Choice.LATER && card.putOff) return
        when (choice) {
            Choice.LATER, Choice.DROP -> leave(card, choice, fromX, fromY, null, null, null)
            else -> openForm(card, choice)
        }
    }

    fun openForm(card: InboxCard, choice: Choice) {
        val home = if (ui.value.projects.any { it.name == card.project }) card.project else "me"
        val who = card.author?.let { if (card.site == Site.LINKEDIN || it.startsWith("@")) it else "@$it" }
        val line = when (choice) {
            Choice.ACT -> card.next
            Choice.NOTE -> card.note.ifEmpty { listOfNotNull(who, card.claim.ifEmpty { null }).joinToString(": ") }
            else -> ""
        }
        _form.value = Form(card, choice, line, home, card.someday)
    }

    fun editForm(f: Form) { _form.value = f }

    fun closeForm() { _form.value = null }

    /** The form's button: the card leaves with its choice's motion, the words go with it. */
    fun submit() {
        val f = _form.value ?: return
        val need = if (f.choice == Choice.KEEP) ui.value.minWords else 2
        if (f.choice != Choice.NOW && words(f.line) < need) return
        _form.value = null
        when (f.choice) {
            Choice.ACT -> leave(f.card, Choice.ACT, 0f, 0f, f.line, f.project, f.someday)
            Choice.NOTE -> leave(f.card, Choice.NOTE, 0f, 0f, f.line, f.project, null)
            Choice.KEEP -> leave(f.card, Choice.KEEP, 0f, 0f, f.line, null, null)
            else -> leave(f.card, Choice.NOW, 0f, 0f, null, null, null)
        }
    }

    fun leaveDone(id: Long) = _leaving.update { if (it?.id == id) null else it }

    private fun leave(card: InboxCard, choice: Choice, fromX: Float, fromY: Float, line: String?, project: String?, someday: Boolean?) {
        _leaving.value = Leaving(nextLeave++, card, choice, fromX, fromY)
        if (choice != Choice.LATER) streak.update { it + 1 }
        val keptBefore = ui.value.kept
        viewModelScope.launch {
            val op = repo.choose(card.file, choice, line, project, someday)
            when (choice) {
                Choice.DROP -> toasts.show("Dropped", action = "Undo", millis = 5_000) { undo(op, card) }
                Choice.ACT -> toasts.show("Added to ${ui.value.projects.firstOrNull { it.name == project }?.let { shortName(it.name, it.title) } ?: project}")
                Choice.NOTE -> toasts.show("Noted. It is in Notebook → Saved notes.")
                Choice.KEEP -> {
                    val n = keptBefore + 1
                    val cheer = CHEERS[n]
                    toasts.show(cheer ?: "Kept. The AI turns it into a note.")
                    if (cheer != null) _cheer.update { it + 1 }
                }
                Choice.NOW -> toasts.show("Done and gone")
                Choice.LATER -> {}
            }
            if (ui.value.cards.size < InboxRepository.LOW) refresh()
        }
    }

    private fun undo(op: Long, card: InboxCard) {
        viewModelScope.launch { if (repo.undo(op, card.file, Choice.DROP)) streak.update { (it - 1).coerceAtLeast(0) } }
    }

    private fun build(deck: List<InboxItemEntity>, s: InboxStateEntity?, leavingCount: Int, projects: List<ProjectEntity>, run: Int): InboxUi {
        val today = org.pagel.ai.data.inbox.tashkentDay(System.currentTimeMillis())
        val fresh = s != null && s.day == today
        val kept = if (fresh) s!!.keptToday else 0
        val cap = s?.cap ?: 5
        val byName = projects.associateBy(ProjectEntity::name)
        return InboxUi(
            loading = s == null && deck.isEmpty(),
            cards = deck.take(2).map { i -> card(i, kept >= cap, byName[i.project]) },
            sorted = if (fresh) s!!.sorted else 0,
            goal = s?.goal ?: 20,
            kept = kept,
            cap = cap,
            minWords = s?.minWords ?: 6,
            left = ((s?.total ?: deck.size) - leavingCount).coerceAtLeast(deck.size),
            streak = run,
            projects = projects,
        )
    }

    companion object {
        /** The web's cheers for keeps (learn-client.tsx CHEERS): 5 is the habit, bigger days get bigger words. */
        val CHEERS = mapOf(
            5 to "5 learned. Today's habit is done 🎉",
            10 to "10 learned. Double the habit 🔥",
            25 to "25 learned in one day. That is a whole chapter 🚀",
            50 to "50 learned. Rare day 🏆",
            100 to "100 learned. Legendary 👑",
        )

        fun card(i: InboxItemEntity, full: Boolean, project: ProjectEntity?): InboxCard {
            val (site, name) = siteOf(i.source)
            val body = postBody(i.text)
            val pick = if (i.guessed) Choice.guessOf(i.kind) else null
            return InboxCard(
                file = i.file, site = site, siteName = name,
                author = i.author.takeIf(String::isNotBlank)?.let { if (site == Site.LINKEDIN || it.startsWith("@")) it else "@$it" },
                posted = i.posted.takeIf(String::isNotBlank),
                project = i.project, projectTitle = project?.let { shortName(it.name, it.title) }, projectColor = project?.color,
                claim = i.claim.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: i.file },
                why = i.why.takeIf { it.isNotBlank() && !it.contains("not tied to your work", ignoreCase = true) },
                text = body.take(1200),
                link = i.source.takeIf { it.startsWith("http") },
                hasPicture = i.hasPicture,
                pick = pick,
                guess = if (pick == Choice.KEEP && full) Choice.NOTE else pick,
                putOff = i.later != null,
                someday = i.kind == "someday",
                next = i.next, note = i.note,
            )
        }
    }
}
