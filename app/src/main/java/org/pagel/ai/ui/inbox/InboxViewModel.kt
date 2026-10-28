package org.pagel.ai.ui.inbox

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.InboxStateEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.data.inbox.InboxPictures
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.inbox.Picture
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.session.Session
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.projects.shortName
import javax.inject.Inject

/**
 * How many times the AI is asked to read the same unread cards: [MAX] asks that pagel.uz took, then it stops until the
 * unread set changes. A save the AI cannot read is never asked for again every minute (checker 2026-10-28 #4).
 */
class ReadAsks {
    private var n = 0
    fun more() = n < MAX
    fun count() { n++ }

    companion object {
        const val MAX = 3
    }
}

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
    /** pagel.uz's AI is reading the next unread saves (asked from here); their picks come with the next deck. */
    val reading: Boolean = false,
) {
    val full get() = kept >= cap
}

/**
 * A card on its way out: drawn over the deck for its exit motion, after Room already let it go. [shuffled]: swiped up
 * back into the deck (it leaves with Later's slide and feel, and nothing is sent).
 */
data class Leaving(val id: Long, val card: InboxCard, val choice: Choice, val thrown: Throw = Throw.None, val shuffled: Boolean = false)

/** An open choice form (Action, Note, Learn, Do now): the card it is for and the line Boss writes. */
data class Form(val card: InboxCard, val choice: Choice, val line: String, val project: String, val someday: Boolean)

/**
 * The deck as the screen shows it: the cards hidden by hand ([gone]: swiped, Room not yet caught up) leave the top
 * at once. Only the leading ones: a shuffled card further down stays where Room put it.
 */
fun visibleDeck(deck: List<InboxItemEntity>, gone: Set<String>): List<InboxItemEntity> =
    if (gone.isEmpty()) deck else deck.dropWhile { it.file in gone }

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
    private val session: Session,
) : ViewModel() {
    private val streak = MutableStateFlow(0)
    private val reading = MutableStateFlow(false)
    /** Cards swiped or sorted that Room has not let go yet: hidden at once, so the next card is on top on the same frame. */
    private val gone = MutableStateFlow<Set<String>>(emptySet())
    private val visible = MutableStateFlow(false)
    /** Pictures that could not be fetched (offline): asked for again when the live link is back. */
    private val retryPictures = mutableSetOf<String>()
    private var lastAsk = 0L
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

    val ui: StateFlow<InboxUi> = combine(repo.deck, repo.state, repo.leaving, session.projects(), combine(streak, reading, gone) { a, b, c -> Triple(a, b, c) }) { deck, state, leavingCount, projects, (run, aiReading, hidden) ->
        build(visibleDeck(deck, hidden), state, leavingCount, projects, run).copy(reading = aiReading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUi())

    init {
        refresh()
        // The pictures of the next ten cards, ahead of time while online, so a subway ride still shows them. One that
        // could not be fetched is asked for again when the live link is back (checker 2026-10-28 #4).
        viewModelScope.launch {
            val files = repo.deck.map { d -> d.take(PREFETCH).filter(InboxItemEntity::hasPicture).map(InboxItemEntity::file) }.distinctUntilChanged()
            val online = session.live.map { it is LiveStatus.Live }.distinctUntilChanged()
            combine(files, online) { f, on -> f to on }.collect { (list, on) ->
                val account = (accounts.load() as? AccountState.Paired)?.account ?: return@collect
                for (f in list) if (f !in _pictures.value || (on && f in retryPictures)) {
                    // null in the map = no picture to show now: the card drops the picture's place.
                    when (val p = pictures.load(account, f)) {
                        is Picture.Shown -> { retryPictures -= f; _pictures.update { it + (f to p.bitmap) } }
                        Picture.None -> { retryPictures -= f; _pictures.update { it + (f to null) } }
                        Picture.Unavailable -> { retryPictures += f; _pictures.update { it + (f to null) } }
                    }
                }
            }
        }
        // Back online with a short deck: the next cards come now, not at the next choice.
        viewModelScope.launch {
            session.live.map { it is LiveStatus.Live }.distinctUntilChanged().collect { on -> if (on && ui.value.cards.size < 2) refresh() }
        }
        // The AI's read of unread saves (the web's triage after 8 s on /learn): when one of the next 5 cards has no
        // pick, ask pagel.uz's AI to read the next unread ones, at most once a minute; offline, try again in 15 s.
        // Only while the Inbox is on screen, and at most [ReadAsks.MAX] asks for the same unread cards: app-v0.7.0 asked
        // every minute for as long as the process lived, up to 1,440 Claude runs a day on the box lexion.uz shares
        // (checker 2026-10-28 #4). A new unread set (a pick came, or Boss sorted on) starts a fresh count.
        viewModelScope.launch {
            val unread = repo.deck.map { d -> d.take(NEXT).filter { !it.guessed }.map(InboxItemEntity::file) }.distinctUntilChanged()
            combine(unread, visible) { files, on -> files to on }.collectLatest { (files, on) ->
                if (files.isEmpty()) { reading.value = false; return@collectLatest }
                if (!on) return@collectLatest
                val asks = ReadAsks()
                while (asks.more()) {
                    if (askToRead()) asks.count()
                    delay(15_000)
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

    /** The Inbox is on screen (started) or not: the AI is asked to read only while Boss looks at it. */
    fun onScreen(on: Boolean) { visible.value = on }

    /** True when pagel.uz took the ask. */
    private suspend fun askToRead(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAsk < ASK_GAP_MS) return false
        val account = (accounts.load() as? AccountState.Paired)?.account ?: return false
        val files = repo.deck.first().take(25).filter { !it.guessed }.map(InboxItemEntity::file)
        if (files.isEmpty() || !repo.askToRead(account, files)) return false
        lastAsk = now
        reading.value = true
        // The read takes well under a minute on the server; the deck brings the picks.
        viewModelScope.launch {
            delay(40_000); refresh()
            delay(40_000); refresh()
            reading.value = false
        }
        return true
    }

    /** A choice that needs no words (Later, Drop) leaves now; the others open their form first. */
    fun choose(card: InboxCard, choice: Choice, thrown: Throw = Throw.None) {
        val u = ui.value
        if (choice == Choice.KEEP && u.full) return
        if (choice == Choice.LATER && card.putOff) return
        when (choice) {
            Choice.LATER, Choice.DROP -> leave(card, choice, thrown, null, null, null)
            else -> openForm(card, choice)
        }
    }

    /**
     * Swipe up (Boss 2026-10-28: "swipe up it should shuffle"): the card goes back into the deck at a random later spot,
     * nothing is sent and nothing is counted. False when it is the last card (it springs back).
     */
    fun shuffle(card: InboxCard, thrown: Throw): Boolean {
        if (ui.value.cards.size < 2) return false
        _leaving.value = Leaving(nextLeave++, card, Choice.LATER, thrown, shuffled = true)
        hide(card.file)
        viewModelScope.launch {
            repo.shuffle(card.file)
            settle(card.file)
        }
        return true
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
            Choice.ACT -> leave(f.card, Choice.ACT, Throw.None, f.line, f.project, f.someday)
            Choice.NOTE -> leave(f.card, Choice.NOTE, Throw.None, f.line, f.project, null)
            Choice.KEEP -> leave(f.card, Choice.KEEP, Throw.None, f.line, null, null)
            else -> leave(f.card, Choice.NOW, Throw.None, null, null, null)
        }
    }

    fun leaveDone(id: Long) = _leaving.update { if (it?.id == id) null else it }

    private fun leave(card: InboxCard, choice: Choice, thrown: Throw, line: String?, project: String?, someday: Boolean?) {
        _leaving.value = Leaving(nextLeave++, card, choice, thrown)
        hide(card.file)
        if (choice != Choice.LATER) streak.update { it + 1 }
        val keptBefore = ui.value.kept
        viewModelScope.launch {
            val op = repo.choose(card.file, choice, line, project, someday)
            settle(card.file)
            when (choice) {
                Choice.DROP -> toasts.show("Dropped", action = "Undo", millis = 5_000) { undo(op, card) }
                Choice.ACT -> toasts.show("Added to ${ui.value.projects.firstOrNull { it.name == project }?.let { shortName(it.name, it.title) } ?: project}")
                Choice.NOTE -> toasts.show("Noted ✓")
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

    private fun hide(file: String) = gone.update { it + file }

    /** Room has let the card go (it is no longer on top), or 3 s passed: it stops being hidden by hand. */
    private suspend fun settle(file: String) {
        withTimeoutOrNull(3_000) { repo.deck.first { d -> d.firstOrNull()?.file != file } }
        gone.update { it - file }
    }

    private fun undo(op: Long, card: InboxCard) {
        viewModelScope.launch { if (repo.undo(op, card.file, Choice.DROP)) streak.update { (it - 1).coerceAtLeast(0) } }
    }

    private fun build(deck: List<InboxItemEntity>, s: InboxStateEntity?, leavingCount: Int, projects: List<ProjectEntity>, run: Int): InboxUi {
        val today = org.pagel.ai.data.inbox.tashkentDay(System.currentTimeMillis())
        // Today's counts: the day pagel.uz names, or counts it sent today (never a ring reset by a stale day field).
        val fresh = s != null && (s.day == today || org.pagel.ai.data.inbox.tashkentDay(s.fetchedAt) == today)
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
        /** Cards whose pictures are fetched ahead of time. */
        private const val PREFETCH = 10
        /** The cards Boss sees next: one with no AI pick among them asks pagel.uz's AI to read. */
        private const val NEXT = 5
        private const val ASK_GAP_MS = 60_000L

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
