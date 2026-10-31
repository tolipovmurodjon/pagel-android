package org.pagel.ai.ui.inbox

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
import org.pagel.ai.data.inbox.isCurrent
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
    /** The Old pile (saves from before the fresh start): how many are left (null = not known yet), and whether it is open. */
    val old: Int? = null,
    val oldMode: Boolean = false,
    /** The Old pile just reached 0: its row says "Old pile done" once, then hides. */
    val oldDone: Boolean = false,
) {
    val full get() = kept >= cap
}

/**
 * A card on its way out: drawn over the deck for its exit motion, after Room already let it go. [shuffled]: swiped up
 * back into the deck (it leaves with Later's slide and feel, and nothing is sent).
 */
data class Leaving(val id: Long, val card: InboxCard, val choice: Choice, val thrown: Throw = Throw.None, val shuffled: Boolean = false)

/** An open choice form (Action, Note, Learn, Do now): the card it is for and the line Boss writes. */
data class Form(
    val card: InboxCard, val choice: Choice, val line: String, val project: String, val someday: Boolean, val due: String? = null,
    /** Waiting for: who has the ball (optional). Project: the new project's name and its life Area (null = none). */
    val who: String = "", val name: String = "", val area: String? = null,
)

/** Whether a form has what its choice needs before the card may leave. */
fun formReady(f: Form, minWords: Int): Boolean = when (f.choice) {
    Choice.NOW -> true
    Choice.KEEP -> words(f.line) >= minWords
    Choice.PROJECT -> words(f.name) >= 1 && words(f.line) >= 2
    else -> words(f.line) >= 2
}

/**
 * The bulk tidy-up sheet: the first page of old saves, how many pagel.uz holds in all ([total], its `older_than_60`;
 * a page is at most 60), the whole deck by source, and whether the ask is still out or failed (offline).
 */
data class Tidy(
    val loading: Boolean = true, val items: List<InboxItemEntity> = emptyList(), val failed: Boolean = false,
    val total: Int = items.size, val bySource: Map<String, Int> = emptyMap(),
) {
    val count get() = maxOf(total, items.size)

    /** The whole Inbox by where it came from (pagel.uz's by_source), biggest first; an older pagel.uz: the page's own. */
    val sources: List<Pair<String, Int>> get() =
        if (bySource.isNotEmpty()) bySource.entries.map { (k, v) -> sourceName(k) to v }.filter { it.second > 0 }.sortedByDescending { it.second }
        else items.groupingBy { siteOf(it.source).second }.eachCount().toList().sortedByDescending { it.second }
}

/** pagel.uz's by_source keys (inbox-filter.ts platformOf) in words. */
fun sourceName(key: String) = when (key) {
    "instagram" -> "Instagram"; "x" -> "X"; "linkedin" -> "LinkedIn"; "youtube" -> "YouTube"; "telegram" -> "Telegram"
    "note" -> "Typed by you"; "web" -> "Other sites"; else -> key.replaceFirstChar { it.uppercase() }
}

/** A bulk tidy-up on its way: [done] of [total] queued so far (the quiet line "120 of 412"), [stalled] = waiting for pagel.uz. */
data class Bulk(val choice: Choice, val done: Int, val total: Int, val stalled: Boolean = false)

/** Where a toast's Open goes (round D item 2): a project's tasks or its notes. */
sealed interface InboxOpen {
    data class Project(val name: String) : InboxOpen
    data class Notes(val project: String) : InboxOpen
}

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
    private val where: org.pagel.ai.data.inbox.WhereItWent,
    private val projects: org.pagel.ai.data.projects.ProjectRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    private val prefs = appContext.getSharedPreferences("pagel-inbox", android.content.Context.MODE_PRIVATE)
    private val oldMode = MutableStateFlow(false)
    private val doneShown = MutableStateFlow(runCatching { prefs.getBoolean(OLD_DONE_SHOWN, false) }.getOrDefault(false))
    private var oldLoading = false
    private var oldMisses = 0
    private data class OldView(val fresh: String, val captured: Set<String>, val total: Int?, val leaving: Int, val mode: Boolean, val doneShown: Boolean)
    private val oldView = combine(repo.freshFrom, repo.captured.files, repo.oldTotal, repo.leavingOld, combine(oldMode, doneShown) { m, d -> m to d }) { f, c, t, l, md ->
        OldView(f, c, t, l, md.first, md.second)
    }
    private val _open = kotlinx.coroutines.channels.Channel<InboxOpen>(kotlinx.coroutines.channels.Channel.BUFFERED)
    /** A toast's Open: the shell pushes the project page or its notes. */
    val open: kotlinx.coroutines.flow.Flow<InboxOpen> = _open.receiveAsFlow()
    /** Today's decisions with where each went ("Sorted today", round D item 2). */
    val sortedToday: StateFlow<List<org.pagel.ai.data.db.InboxSortedEntity>> =
        where.sortedToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    private val _tidy = MutableStateFlow<Tidy?>(null)
    val tidy: StateFlow<Tidy?> = _tidy.asStateFlow()
    private val _bulk = MutableStateFlow<Bulk?>(null)
    /** The bulk tidy-up's quiet progress line, while it pages through pagel.uz's old saves. */
    val bulk: StateFlow<Bulk?> = _bulk.asStateFlow()
    private var bulkJob: kotlinx.coroutines.Job? = null
    /** The form each choice was last sent with: a long-press repeats it on the next card (checker 2026-10-30 #7). */
    private val last = mutableMapOf<Choice, Form>()
    private val _pictures = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val picturesShown: StateFlow<Map<String, Bitmap?>> = _pictures.asStateFlow()
    /** A keep's milestone or the day's goal crossed on this screen: the screen fires confetti, the VM says the words. */
    private val _cheer = MutableStateFlow(0)
    val cheer: StateFlow<Int> = _cheer.asStateFlow()
    private var nextLeave = 1L
    private var refreshing = false

    val ui: StateFlow<InboxUi> = combine(repo.deck, repo.state, repo.leaving, session.projects(), combine(combine(streak, reading, gone) { a, b, c -> Triple(a, b, c) }, oldView) { t, o -> t to o }) { deck, state, _, projects, (t, old) ->
        val (run, aiReading, hidden) = t
        // The Inbox is the current saves; the Old pile is the rest, shown only while it is open.
        val pool = deck.filter { isCurrent(it, old.fresh, old.captured) != old.mode }
        build(visibleDeck(pool, hidden), state, projects, run, deck.isEmpty(), old).copy(reading = aiReading)
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
        // The Old pile: remembered once it has had saves, so reaching 0 says "Old pile done" once; and while it is open the
        // next 60 come in when fewer than a screenful are left.
        viewModelScope.launch {
            ui.map { it.old to it.oldDone }.distinctUntilChanged().collect { (n, done) ->
                if ((n ?: 0) > 0) runCatching { prefs.edit().putBoolean(OLD_SEEN, true).apply() }
                if (done) {
                    delay(4_000)
                    runCatching { prefs.edit().putBoolean(OLD_DONE_SHOWN, true).apply() }
                    doneShown.value = true
                }
            }
        }
        viewModelScope.launch {
            ui.map { it.oldMode to it.cards.size }.distinctUntilChanged().collect { (open, shown) -> if (open && shown < 2) loadOld() }
        }
        // The goal crossed here gets the finish moment once, as on the web (learn-client.tsx goalBar).
        viewModelScope.launch {
            var was: Int? = null
            repo.state.collect { s ->
                if (s == null) return@collect
                val before = was
                was = s.sorted
                if (before != null && before < s.goal && s.sorted >= s.goal) {
                    toasts.show("${s.goal} sorted today. Goal done")
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

    /** Opens the Old pile: the same sorting, on the saves from before the fresh start. */
    fun openOld() {
        oldMisses = 0
        oldMode.value = true
        loadOld()
    }

    fun closeOld() { oldMode.value = false }

    /** The next page of the Old pile (60, oldest first); it stops after a few pages that bring nothing new. */
    private fun loadOld() {
        if (oldLoading || oldMisses >= 3) return
        oldLoading = true
        viewModelScope.launch {
            try {
                val account = (accounts.load() as? AccountState.Paired)?.account ?: return@launch
                when (val r = repo.loadOld(account)) {
                    is org.pagel.ai.data.Outcome.Ok -> if (r.value == 0) oldMisses++ else oldMisses = 0
                    else -> oldMisses++
                }
            } finally {
                oldLoading = false
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
            Choice.SOMEDAY -> someday(card, thrown)
            else -> openForm(card, choice)
        }
    }

    /**
     * Someday is one tap, no sheet (Boss 2026-10-30): a P3 task in the card's project with the AI's next step (or the
     * claim) as its words, 5 s Undo. A save with too little to say opens the Action form with Someday already on.
     */
    private fun someday(card: InboxCard, thrown: Throw) {
        val line = card.next.ifBlank { card.claim }.trim()
        if (words(line) < 2) { _form.value = Form(card, Choice.ACT, "", homeOf(card), true); return }
        leave(card, Choice.SOMEDAY, thrown, line, homeOf(card), true)
    }

    private fun homeOf(card: InboxCard) = if (ui.value.projects.any { it.name == card.project }) card.project else "me"

    /**
     * Swipe up (Boss 2026-10-28: "swipe up it should shuffle"): the card goes back into the deck at a random later spot,
     * nothing is sent and nothing is counted. False when it is the last card (it springs back).
     */
    fun shuffle(card: InboxCard, thrown: Throw): Boolean {
        if (ui.value.cards.size < 2 || ui.value.oldMode) return false
        _leaving.value = Leaving(nextLeave++, card, Choice.LATER, thrown, shuffled = true)
        hide(card.file)
        viewModelScope.launch {
            repo.shuffle(card.file)
            settle(card.file)
        }
        return true
    }

    fun openForm(card: InboxCard, choice: Choice) {
        val home = homeOf(card)
        val who = card.author?.let { if (card.site == Site.LINKEDIN || it.startsWith("@")) it else "@$it" }
        val line = when (choice) {
            Choice.ACT -> card.next
            Choice.NOTE -> card.note.ifEmpty { listOfNotNull(who, card.claim.ifEmpty { null }).joinToString(": ") }
            Choice.CALENDAR, Choice.PROJECT -> card.next
            Choice.WAITING -> card.claim.take(80)
            else -> ""
        }
        val today = where.today()
        val due = when (choice) {
            Choice.WAITING -> org.pagel.ai.data.inbox.checkDate(today)
            // The day the save names ("closes on Friday" -> Friday), else tomorrow; 09:00 until the wheel turns.
            Choice.CALENDAR -> {
                val t = java.time.LocalDate.parse(today)
                val day = org.pagel.ai.data.inbox.dayIn(listOf(card.claim, card.next, card.text).joinToString(" "), t) ?: t.plusDays(1)
                "$day ${org.pagel.ai.data.inbox.CALENDAR_TIME}"
            }
            else -> null
        }
        // A new project is named from the card (the claim, cut short); Boss can rewrite it. Its Area starts as the area
        // of the project the AI guessed for the card.
        val name = if (choice == Choice.PROJECT) card.claim.take(40).trim() else ""
        val area = if (choice == Choice.PROJECT) ui.value.projects.firstOrNull { it.name == card.project }?.area else null
        _form.value = Form(card, choice, line, home, card.someday, due = due, name = name, area = area)
    }

    /**
     * Long-press on a choice (checker 2026-10-30 #7): the same choice with the same settings as last time (project,
     * Someday, who, when), on this card, with no sheet. A choice that needs new words (Learn, Project, Calendar) or was
     * never sent yet opens its form as a tap does.
     */
    fun repeat(card: InboxCard, choice: Choice) {
        val l = last[choice]
        when (choice) {
            Choice.DROP, Choice.LATER, Choice.SOMEDAY, Choice.NOW -> choose(card, choice)
            Choice.ACT, Choice.NOTE, Choice.WAITING -> if (l == null) openForm(card, choice) else {
                openForm(card, choice)
                val f = _form.value ?: return
                val r = f.copy(project = l.project, someday = l.someday, who = l.who,
                    due = if (choice == Choice.WAITING) f.due else l.due?.takeIf { it >= where.today() })
                _form.value = r
                if (formReady(r, ui.value.minWords)) submit()
            }
            else -> openForm(card, choice)
        }
    }

    fun editForm(f: Form) { _form.value = f }

    fun closeForm() { _form.value = null }

    /** The form's button: the card leaves with its choice's motion, the words go with it. */
    fun submit() {
        val f = _form.value ?: return
        if (!formReady(f, ui.value.minWords)) return
        _form.value = null
        last[f.choice] = f
        when (f.choice) {
            Choice.WAITING -> leave(f.card, Choice.WAITING, Throw.None, org.pagel.ai.data.inbox.waitingLine(f.who, f.line), f.project, false, f.due ?: org.pagel.ai.data.inbox.checkDate(where.today()))
            Choice.CALENDAR -> leave(f.card, Choice.CALENDAR, Throw.None, f.line, f.project, false, f.due)
            // The project is made on the phone first (its create waits in the outbox ahead of the card's choice), then the
            // card leaves with the first step as a task in it. The toast names it by its title, never the cut slug.
            Choice.PROJECT -> viewModelScope.launch {
                val slug = projects.create(f.name.trim(), area = f.area)
                leave(f.card, Choice.PROJECT, Throw.None, f.line, slug, false, title = f.name.trim())
            }
            Choice.ACT -> leave(f.card, Choice.ACT, Throw.None, f.line, f.project, f.someday, f.due)
            Choice.NOTE -> leave(f.card, Choice.NOTE, Throw.None, f.line, f.project, null)
            Choice.KEEP -> leave(f.card, Choice.KEEP, Throw.None, f.line, null, null)
            else -> leave(f.card, Choice.NOW, Throw.None, null, null, null)
        }
    }

    /** The tidy-up: asks pagel.uz for the saves older than [org.pagel.ai.data.inbox.OLD_DAYS] days and shows how many. */
    fun openTidy() {
        _tidy.value = Tidy()
        viewModelScope.launch {
            val account = (accounts.load() as? AccountState.Paired)?.account
            val r = account?.let { repo.older(it) }
            _tidy.value = when (r) {
                is org.pagel.ai.data.Outcome.Ok -> Tidy(loading = false, items = r.value.items, total = r.value.total, bySource = r.value.bySource)
                else -> Tidy(loading = false, failed = true)
            }
        }
    }

    fun closeTidy() { _tidy.value = null }

    /**
     * Boss saw the count and chose: all of them go to Someday or are dropped (checker 2026-10-30 #2: all 412, not the
     * first 60). The first page waits out the one 5 s Undo, which stops every one; only then are the next pages asked
     * for (60 a time), so nothing past the Undo was ever queued. A quiet line says "120 of 412"; none of it counts
     * toward the day's 20. Offline mid-way, it waits for pagel.uz and goes on.
     */
    fun tidyAll(choice: Choice) {
        val t = _tidy.value?.takeIf { it.items.isNotEmpty() } ?: return
        _tidy.value = null
        bulkJob?.cancel()
        val total = t.count
        val what = if (choice == Choice.SOMEDAY) "to Someday" else "dropped"
        bulkJob = viewModelScope.launch {
            val done = t.items.map(InboxItemEntity::file).toMutableSet()
            // The toast comes first (respond first, save after): the 60 rows are written behind it, and the Undo waits for them.
            val first = async { repo.chooseMany(t.items, choice, "me") }
            _bulk.value = Bulk(choice, done.size, total)
            toasts.show(if (choice == Choice.SOMEDAY) "$total moving to Someday" else "$total dropping", action = "Undo", millis = 5_000) {
                bulkJob?.cancel()
                _bulk.value = null
                viewModelScope.launch { repo.undoMany(first.await()) }
            }
            delay(org.pagel.ai.data.tasks.UNDO_WINDOW_MS + 300)
            repo.sendNow()
            var misses = 0
            while (done.size < total && misses < BULK_MISSES) {
                val account = (accounts.load() as? AccountState.Paired)?.account ?: break
                val page = when (val r = repo.older(account)) { is org.pagel.ai.data.Outcome.Ok -> r.value; else -> null }
                // pagel.uz still deals the ones on their way until each reaches it: only new files are queued.
                val fresh = page?.items?.filter { it.file !in done }.orEmpty()
                if (page != null && page.items.isEmpty()) break
                if (fresh.isEmpty()) {
                    misses++
                    _bulk.update { it?.copy(stalled = page == null) }
                    delay(if (page == null) 15_000 else 2_000)
                    continue
                }
                misses = 0
                repo.chooseMany(fresh, choice, "me")
                done += fresh.map(InboxItemEntity::file)
                _bulk.value = Bulk(choice, done.size, total)
            }
            _bulk.value = null
            toasts.show("${done.size} $what")
            refresh()
        }
    }

    fun leaveDone(id: Long) = _leaving.update { if (it?.id == id) null else it }

    private fun projectName(name: String?) = ui.value.projects.firstOrNull { it.name == name }?.let { org.pagel.ai.ui.projects.shortName(it.name, it.title) } ?: name.orEmpty()

    /** Undo one of today's decisions from "Sorted today": only while it still waits on the phone. */
    fun undoSorted(s: org.pagel.ai.data.db.InboxSortedEntity) {
        viewModelScope.launch { if (!where.undo(s)) toasts.show("Already on pagel.uz", good = false) else gone.update { it - s.file } }
    }

    private fun leave(card: InboxCard, choice: Choice, thrown: Throw, line: String?, project: String?, someday: Boolean?, due: String? = null, title: String? = null) {
        _leaving.value = Leaving(nextLeave++, card, choice, thrown)
        hide(card.file)
        if (choice != Choice.LATER) streak.update { it + 1 }
        val keptBefore = ui.value.kept
        viewModelScope.launch {
            val op = repo.choose(card.file, choice, line, project, someday, due)
            where.record(card.file, choice.log, card.claim.ifEmpty { postBody(card.text).take(80) }, project, line, due, op)
            settle(card.file)
            // Every toast says where it went in one plain line (round D item 2), short enough to stay one line beside Undo on
            // a 320 dp screen (~20 letters). A choice held for its 5 s (Drop and every act) offers Undo; the rest offer Open
            // (checker 2026-10-30 #5).
            val plain = org.pagel.ai.data.inbox.plainDay(due)
            when (choice) {
                Choice.DROP -> toasts.show("Dropped", action = "Undo", millis = 5_000) { undo(op, card) }
                Choice.ACT -> {
                    val tomorrow = java.time.LocalDate.parse(where.today()).plusDays(1).toString()
                    val whenText = when (due) { null -> if (someday == true) " · Someday" else ""; where.today() -> " · today"; tomorrow -> " · tomorrow"; else -> " · $plain" }
                    toasts.show("${projectName(project)}$whenText", action = "Undo", millis = 5_000) { undo(op, card) }
                }
                Choice.NOTE -> toasts.show("Saved to ${projectName(project)} notes", action = "Open") { project?.let { _open.trySend(InboxOpen.Notes(it)) } }
                Choice.KEEP -> {
                    val n = keptBefore + 1
                    val cheer = CHEERS[n]
                    toasts.show(cheer ?: "Kept. The AI turns it into a note.")
                    if (cheer != null) _cheer.update { it + 1 }
                }
                Choice.NOW -> toasts.show("Done and gone")
                Choice.LATER -> toasts.show("Later · back in 3 days")
                Choice.SOMEDAY -> toasts.show("Someday · ${projectName(project)}", action = "Undo", millis = 5_000) { undo(op, card) }
                Choice.WAITING -> toasts.show("Check $plain", action = "Undo", millis = 5_000) { undo(op, card) }
                Choice.CALENDAR -> toasts.show(plain, action = "Undo", millis = 5_000) { undo(op, card) }
                // Undo takes the step back and the new project with it (it drops after its own 5 s).
                Choice.PROJECT -> toasts.show(shortTitle(title ?: projectName(project)), action = "Undo", millis = 5_000) {
                    undo(op, card) { project?.let { viewModelScope.launch { projects.delete(it, dropTasks = true) } } }
                }
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

    /** A new project's title for its toast: short enough to stay one line beside Undo. */
    private fun shortTitle(t: String): String {
        // Beside Undo on a 320 dp screen a longer title wraps to two lines (walk 67): a long one says "New project".
        return if (t.length <= 14) t else "New project"
    }

    /** A toast's Undo: the card is back on top and out of "Sorted today", while the choice still waits on the phone. */
    private fun undo(op: Long, card: InboxCard, also: () -> Unit = {}) {
        viewModelScope.launch {
            if (repo.undo(op, card.file, Choice.DROP)) {
                streak.update { (it - 1).coerceAtLeast(0) }
                where.forget(card.file)
                also()
            } else toasts.show("Already on pagel.uz")
        }
    }

    private fun build(deck: List<InboxItemEntity>, s: InboxStateEntity?, projects: List<ProjectEntity>, run: Int, rawEmpty: Boolean, old: OldView): InboxUi {
        val today = org.pagel.ai.data.inbox.tashkentDay(System.currentTimeMillis())
        // Today's counts: the day pagel.uz names, or counts it sent today (never a ring reset by a stale day field).
        val fresh = s != null && (s.day == today || org.pagel.ai.data.inbox.tashkentDay(s.fetchedAt) == today)
        val kept = if (fresh) s!!.keptToday else 0
        val cap = s?.cap ?: 5
        val byName = projects.associateBy(ProjectEntity::name)
        val oldLeft = old.total?.let { (it - old.leaving).coerceAtLeast(0) }
        return InboxUi(
            loading = s == null && rawEmpty,
            cards = deck.take(2).map { i -> card(i, kept >= cap, byName[i.project]) },
            sorted = if (fresh) s!!.sorted else 0,
            goal = s?.goal ?: 20,
            kept = kept,
            cap = cap,
            minWords = s?.minWords ?: 6,
            left = deck.size,
            streak = run,
            projects = projects,
            old = oldLeft,
            oldMode = old.mode,
            oldDone = old.total == 0 && !old.doneShown && runCatching { prefs.getBoolean(OLD_SEEN, false) }.getOrDefault(false),
        )
    }

    companion object {
        private const val OLD_SEEN = "old_pile_seen"
        private const val OLD_DONE_SHOWN = "old_pile_done_shown"
        /** Cards whose pictures are fetched ahead of time. */
        private const val PREFETCH = 10
        /** The cards Boss sees next: one with no AI pick among them asks pagel.uz's AI to read. */
        private const val NEXT = 5
        private const val ASK_GAP_MS = 60_000L
        /** Pages in a row with nothing new before a bulk tidy-up stops (2 s apart online, 15 s offline): ~5 min online. */
        private const val BULK_MISSES = 150

        /** The web's cheers for keeps (learn-client.tsx CHEERS): 5 is the habit, bigger days get bigger words. */
        val CHEERS = mapOf(
            5 to "5 learned. Today's habit is done",
            10 to "10 learned. Double the habit",
            25 to "25 learned in one day. That is a whole chapter",
            50 to "50 learned. Rare day",
            100 to "100 learned. Legendary",
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
