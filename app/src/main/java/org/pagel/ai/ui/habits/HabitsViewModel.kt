package org.pagel.ai.ui.habits

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitBoard
import org.pagel.ai.data.habits.HabitPrefsRepository
import org.pagel.ai.data.habits.HabitsDay
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.habitBoard
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.data.habits.isNamoz
import org.pagel.ai.data.habits.partAt
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.ui.glass.ToastCenter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * A timed habit's clock: [startedAt] while it runs, the time already spent before that, the [day] it was started for
 * (the clock ticks that day, never the day the strip shows when it ends: checker 2026-10-29 #3) and its [minutes].
 */
data class HabitTimer(val startedAt: Long? = null, val spentMs: Long = 0, val day: String? = null, val minutes: Int = 0) {
    val running get() = startedAt != null
    fun spent(now: Long) = spentMs + (startedAt?.let { now - it } ?: 0)
}

/**
 * The clocks, one set for the whole app (the Habits tab and a habit's page share them) and saved on the phone, so a
 * running clock survives the app being killed: when PAGEL comes back, a clock that ran out in the meantime ticks its day.
 */
internal object HabitClocks {
    val all = kotlinx.coroutines.flow.MutableStateFlow<Map<String, HabitTimer>>(emptyMap())
    @Volatile var loaded = false
    private const val KEY = "habit-clocks"

    fun load(prefs: android.content.SharedPreferences) {
        if (loaded) return
        loaded = true
        all.value = decode(prefs.getString(KEY, null).orEmpty())
    }

    fun save(prefs: android.content.SharedPreferences, map: Map<String, HabitTimer>) {
        prefs.edit().putString(KEY, encode(map)).apply()
    }

    /** "id|startedAt|spentMs|day|minutes" lines; a broken line is skipped. */
    fun encode(map: Map<String, HabitTimer>) = map.entries.joinToString("\n") { (id, t) -> "$id|${t.startedAt ?: ""}|${t.spentMs}|${t.day.orEmpty()}|${t.minutes}" }

    fun decode(text: String): Map<String, HabitTimer> = text.lineSequence().mapNotNull { line ->
        val f = line.split('|')
        if (f.size != 5 || f[0].isBlank()) return@mapNotNull null
        f[0] to HabitTimer(f[1].toLongOrNull(), f[2].toLongOrNull() ?: 0, f[3].ifBlank { null }, f[4].toIntOrNull() ?: 0)
    }.toMap()
}

/**
 * A habit's timer screen: which colour (swipe), whether the goal rings and which of the three soft chimes, and the
 * ambient sound (an index into [AmbientSounds.all], -1 = none) with its volume. Older saves have the first three fields.
 */
data class TimerLook(val colour: Int = -1, val bell: Boolean = true, val sound: Int = 0, val ambient: Int = -1, val volume: Float = 0.6f) {
    fun encode() = "$colour|$bell|$sound|$ambient|$volume"

    companion object {
        fun decode(text: String): TimerLook? {
            val f = text.split('|')
            if (f.size !in 3..5) return null
            return TimerLook(
                f[0].toIntOrNull() ?: return null, f[1].toBooleanStrictOrNull() ?: return null, f[2].toIntOrNull() ?: return null,
                f.getOrNull(3)?.toIntOrNull() ?: -1, (f.getOrNull(4)?.toFloatOrNull() ?: 0.6f).coerceIn(0f, 1f),
            )
        }
        const val KEY = "timer-look-"
    }
}

data class HabitsUi(
    val loading: Boolean = true,
    val today: String = "",
    /** The old day model (Today's counts, the all-done card): kept so the finish moment and its tests stay one rule. */
    val day: HabitsDay = HabitsDay(emptyList(), emptyList(), emptyList(), 0, 0),
    val board: HabitBoard? = null,
    /** The part pill chosen; the clock's part until Boss taps another. */
    val part: Part = Part.MORNING,
    val finishing: Set<String> = emptySet(),
    val reminders: Set<String> = emptySet(),
    val byId: Map<String, TaskEntity> = emptyMap(),
    val timers: Map<String, HabitTimer> = emptyMap(),
    val now: Long = 0,
    val ticks: List<HabitTickEntity> = emptyList(),
    val prefs: Map<String, HabitPrefEntity> = emptyMap(),
    val freezes: List<HabitFreezeEntity> = emptyList(),
    val timerLooks: Map<String, TimerLook> = emptyMap(),
)

/**
 * Habits 2.0 (round D): reads Room only. A tick answers in one frame and the outbox sends it; a past day of the last
 * week is ticked for that day; a timed habit runs its clock here and ticks itself at the end; the five prayers are one
 * group that starts counting on 1 November.
 */
@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val repo: HabitsRepository,
    private val prefsRepo: HabitPrefsRepository,
    private val tasks: TaskRepository,
    private val todayRepo: TodayRepository,
    private val accounts: AccountStore,
    private val toasts: ToastCenter,
    @ApplicationContext private val ctx: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    session: Session,
) : ViewModel() {
    private val finishing = MutableStateFlow<Set<String>>(emptySet())
    private val selected = MutableStateFlow<LocalDate?>(null)
    private val chosenPart = MutableStateFlow<Part?>(null)
    private val timers = HabitClocks.all
    private val timerLooks = MutableStateFlow<Map<String, TimerLook>>(emptyMap())
    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val _celebrate = Channel<Unit>(Channel.BUFFERED)
    /** The day's last habit ticked: ding and confetti, once a day on this phone (the web's AllDone). */
    val celebrate: Flow<Unit> = _celebrate.receiveAsFlow()
    private val _timerDone = Channel<String>(Channel.BUFFERED)
    /** A timed habit reached its minutes: the card plays the finish moment. */
    val timerDone: Flow<String> = _timerDone.receiveAsFlow()
    private var celebratedDay: String? = null
    private var ticker: Job? = null
    private val prefsFile by lazy { ctx.getSharedPreferences("pagel-habits", Context.MODE_PRIVATE) }

    private val data = combine(repo.habits, repo.ticks(), prefsRepo.prefs, prefsRepo.freezes, finishing) { h, t, p, f, fin -> Data(h, t, p, f, fin) }
    private val view = combine(selected, chosenPart, timers, clock) { s, p, tm, now -> View(s, p, tm, now) }

    val ui: StateFlow<HabitsUi> = combine(data, view, session.live, repo.reminderRows, timerLooks) { d, v, live, reminders, looks ->
        val today = repo.today()
        val todayDate = LocalDate.parse(today)
        val day = v.selected?.takeIf { it != todayDate } ?: todayDate
        val board = habitBoard(d.habits, d.ticks, d.prefs, d.freezes, todayDate, day)
        HabitsUi(
            loading = d.habits.isEmpty() && (live is LiveStatus.Idle || live is LiveStatus.Syncing),
            today = today,
            day = habitsDay(d.habits.filterNot(::isNamoz), d.ticks, today, d.finishing),
            board = board,
            part = v.part ?: partAt(LocalTime.now(ZoneId.of("Asia/Tashkent"))),
            finishing = d.finishing,
            reminders = reminders.filter { it.enabled }.map { it.taskId }.toSet(),
            byId = d.habits.associateBy(TaskEntity::id),
            timers = v.timers,
            now = v.now,
            ticks = d.ticks,
            prefs = d.prefs.associateBy { it.taskId },
            freezes = d.freezes,
            timerLooks = looks,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUi())

    private data class Data(val habits: List<TaskEntity>, val ticks: List<HabitTickEntity>, val prefs: List<HabitPrefEntity>, val freezes: List<HabitFreezeEntity>, val finishing: Set<String>)
    private data class View(val selected: LocalDate?, val part: Part?, val timers: Map<String, HabitTimer>, val now: Long)

    init {
        refresh()
        repo.replan()
        // The clocks come back after the app was killed (read off Main); a running one picks up its ticker.
        viewModelScope.launch {
            withContext(io) { HabitClocks.load(prefsFile) }
            if (timers.value.values.any { it.running }) runTicker()
        }
        viewModelScope.launch {
            val saved = withContext(io) {
                prefsFile.all.mapNotNull { (k, v) -> if (k.startsWith(TimerLook.KEY) && v is String) TimerLook.decode(v)?.let { k.removePrefix(TimerLook.KEY) to it } else null }.toMap()
            }
            timerLooks.update { saved + it }
        }
        viewModelScope.launch { timers.collect { m -> if (HabitClocks.loaded) withContext(io) { HabitClocks.save(prefsFile, m) } } }
        viewModelScope.launch {
            ui.collect { u ->
                if (!u.day.allDone || u.finishing.isNotEmpty() || celebratedDay == u.today) return@collect
                celebratedDay = u.today
                val first = withContext(io) {
                    val key = "done-${u.today}"
                    if (prefsFile.getBoolean(key, false)) false else { prefsFile.edit().putBoolean(key, true).apply(); true }
                }
                if (first) _celebrate.send(Unit)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val account = (accounts.load() as? AccountState.Paired)?.account ?: return@launch
            repo.refresh(account)
        }
    }

    fun replan() = repo.replan()

    fun selectDay(d: LocalDate?) { selected.value = d }
    fun selectPart(p: Part) { chosenPart.value = p }

    private fun selectedDay(): LocalDate = selected.value ?: LocalDate.parse(repo.today())

    /**
     * One tap on a habit's check. Today: a once-a-day habit finishes (ring, fold), a count habit fills one segment. A day
     * of the last week (Boss tapped it in the strip) is ticked for that day. A habit stacked after this one is nudged.
     */
    fun tick(id: String) = tickOn(id, selectedDay())

    /** One tick on [day] (a tap ticks the strip's day; a clock ticks the day it was started for). */
    fun tickOn(id: String, day: LocalDate) {
        val today = LocalDate.parse(repo.today())
        if (day != today) {
            logPast(id, day, done = true)
            return
        }
        // The board shows the strip's day: its card only describes [day] when the two are the same.
        val card = ui.value.board?.takeIf { it.selected == day }?.let { it.cards + it.namoz }?.firstOrNull { it.task.id == id }
        val last = card == null || card.taps + 1 >= card.times
        if (last) finishing.update { it + id }
        timers.update { it - id }
        viewModelScope.launch {
            val t = repo.tick(id)
            if (t == null) { finishing.update { it - id }; return@launch }
            val inRow = org.pagel.ai.ui.today.Combo.app.tick(System.currentTimeMillis())
            val next = if (last) ui.value.board?.cards?.firstOrNull { it.after == card?.title && !it.done }?.title else null
            val said = when {
                card != null && isNamoz(card.task) -> card.title
                next != null -> "Next: $next"
                last && card != null && card.streak + 1 > 1 -> "${card.streak + 1} in a row"
                last -> "Done"
                else -> "${(card?.taps ?: 0) + 1} of ${card?.times}"
            }
            toasts.show(said, action = "Undo") { org.pagel.ai.ui.today.Combo.app.undo(); undo(id, t) }
            if (!last) return@launch
            delay(FOLD_MS)
            finishing.update { it - id }
        }
    }

    /** The check of a done habit, or a long press on a count: one tap of that day taken off. */
    fun untick(id: String, onToday: Boolean = false) {
        val day = if (onToday) LocalDate.parse(repo.today()) else selectedDay()
        viewModelScope.launch {
            // A day off is drawn as done, so its check takes the day off back (nothing was ticked).
            if (ui.value.freezes.any { it.taskId == id && it.day == day.toString() }) { prefsRepo.unfreeze(id, day.toString()); toasts.show("Day off taken back"); return@launch }
            if (repo.setDay(id, day.toString(), done = false)) toasts.show("Taken back")
        }
    }

    /**
     * A past day logged or taken back (Boss 2026-10-31: "for a missed day I can log and mark it"): the same habit log the
     * tick writes, with that date, then an Undo that writes the inverse. A habit done 3 times a day gets every tap it
     * lacks. pagel.uz takes a date up to [HabitsRepository.DOT_DAYS] days back; older days say so (SERVER NEEDED).
     */
    private fun logPast(id: String, day: LocalDate, done: Boolean) {
        val t = ui.value.byId[id] ?: return
        val times = t.times.coerceAtLeast(1)
        val have = ui.value.ticks.firstOrNull { it.taskId == id && it.day == day.toString() }?.n ?: 0
        val steps = if (done) times - have else have
        if (steps <= 0) return
        val said = day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK))
        viewModelScope.launch {
            var n = 0
            while (n < steps && repo.setDay(id, day.toString(), done)) n++
            if (n == 0) { toasts.show("Only the last 7 days for now"); return@launch }
            toasts.show(if (done) "Logged for $said" else "Taken back for $said", action = "Undo") {
                viewModelScope.launch { repeat(n) { repo.setDay(id, day.toString(), !done) } }
            }
        }
    }

    /** A tap on a day of the habit page's calendar: log it, take it back, or take a day off back. */
    fun toggleDay(id: String, day: LocalDate) {
        val today = LocalDate.parse(repo.today())
        if (day.isAfter(today)) { toasts.show("That day has not come yet"); return }
        val t = ui.value.byId[id] ?: return
        val have = ui.value.ticks.firstOrNull { it.taskId == id && it.day == day.toString() }?.n ?: 0
        when {
            ui.value.freezes.any { it.taskId == id && it.day == day.toString() } -> viewModelScope.launch { prefsRepo.unfreeze(id, day.toString()); toasts.show("Day off taken back") }
            have >= t.times.coerceAtLeast(1) -> if (day == today) untick(id, onToday = true) else logPast(id, day, done = false)
            day == today -> tickOn(id, day)
            else -> logPast(id, day, done = true)
        }
    }

    fun setTimerLook(id: String, look: TimerLook) {
        timerLooks.update { it + (id to look) }
        viewModelScope.launch(io) { prefsFile.edit().putString(TimerLook.KEY + id, look.encode()).apply() }
    }

    private fun undo(id: String, t: org.pagel.ai.data.habits.Ticked) {
        finishing.update { it - id }
        viewModelScope.launch { repo.undo(id, t) }
    }

    /** Start or pause a timed habit's clock; the ticker runs only while one is running. */
    fun toggleTimer(id: String, minutes: Int) {
        val now = System.currentTimeMillis()
        val day = selectedDay().toString()
        timers.update { all ->
            val t = all[id] ?: HabitTimer(day = day, minutes = minutes)
            all + (id to if (t.running) t.copy(startedAt = null, spentMs = t.spent(now)) else t.copy(startedAt = now))
        }
        clock.value = now
        runTicker()
    }

    /** One ticker while any clock runs; a clock that reaches its minutes ticks the day it was started for. */
    private fun runTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive && timers.value.values.any { it.running }) {
                val at = System.currentTimeMillis()
                clock.value = at
                val due = timers.value.filter { (_, t) -> t.running && t.minutes > 0 && t.spent(at) >= t.minutes * 60_000L }
                for ((k, t) in due) {
                    // Only the screen that takes the clock off ticks it (the tab and a habit's page share the clocks).
                    var mine = false
                    timers.update { m -> if (m[k] == t) { mine = true; m - k } else m }
                    if (!mine) continue
                    tickOn(k, t.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.parse(repo.today()))
                    _timerDone.send(k)
                }
                delay(TIMER_TICK_MS)
            }
        }
    }

    fun resetTimer(id: String) = timers.update { it - id }

    /** A streak freeze on the selected day, or today from the habit's page (a sick or travel day); two a month; never ahead. */
    fun freeze(id: String, onToday: Boolean = false) {
        val today = LocalDate.parse(repo.today())
        val d = if (onToday) today else selectedDay()
        if (d.isAfter(today)) { toasts.show("Only today or a day before"); return }
        val day = d.toString()
        viewModelScope.launch {
            if (prefsRepo.freeze(id, day)) toasts.show("Day off. The chain holds") else toasts.show("No days off left this month")
        }
    }

    fun unfreeze(id: String, onToday: Boolean = false) {
        val day = (if (onToday) LocalDate.parse(repo.today()) else selectedDay()).toString()
        viewModelScope.launch { prefsRepo.unfreeze(id, day) }
    }

    /** The emoji keeps the colour and the colour keeps the emoji (both live in one prefs field, HabitLook). */
    fun setEmoji(id: String, key: String?) = viewModelScope.launch {
        prefsRepo.emoji(id, org.pagel.ai.data.habits.HabitLook.look(key, org.pagel.ai.data.habits.HabitLook.colourOf(ui.value.prefs[id]?.emoji)))
    }
    fun setColour(id: String, colour: String?) = viewModelScope.launch {
        prefsRepo.emoji(id, org.pagel.ai.data.habits.HabitLook.look(org.pagel.ai.data.habits.HabitLook.emojiOf(ui.value.prefs[id]?.emoji), colour))
    }
    fun setAfter(id: String, other: String?) = viewModelScope.launch { prefsRepo.after(id, other) }

    /**
     * The anchor (habit stacking): another habit or a daily moment. A habit that would make a loop is refused
     * (HabitLook.leadsTo), and so is the habit itself.
     */
    fun setAnchor(id: String, anchor: org.pagel.ai.data.habits.HabitLook.Anchor?) {
        val habit = anchor as? org.pagel.ai.data.habits.HabitLook.Anchor.Habit
        if (habit != null && (habit.id == id || org.pagel.ai.data.habits.HabitLook.leadsTo(habit.id, id, ui.value.prefs))) {
            toasts.show("That would make a loop"); return
        }
        viewModelScope.launch { prefsRepo.after(id, org.pagel.ai.data.habits.HabitLook.store(anchor)) }
    }

    fun rename(id: String, title: String) {
        val clean = title.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { tasks.title(id, clean) }
    }

    /** Morning 07:00 · Afternoon 13:00 · Evening 19:00, or Anytime (no time); the date stays. */
    fun setPart(id: String, part: Part) {
        val t = ui.value.byId[id] ?: return
        val date = t.due?.take(10) ?: repo.today()
        val time = when (part) { Part.MORNING -> "07:00"; Part.AFTERNOON -> "13:00"; Part.EVENING -> "19:00"; Part.ANYTIME -> null }
        viewModelScope.launch { tasks.due(id, if (time == null) date else "$date $time"); repo.replan() }
    }

    /** The weekdays it repeats on (0 = Sunday); all seven = every day; none is refused. */
    fun setDays(id: String, days: Set<Int>) {
        if (days.isEmpty()) { toasts.show("Pick at least one day"); return }
        val every = if (days.size == 7) "day" else org.pagel.ai.ui.glass.Repeat.daySet(days)
        viewModelScope.launch { tasks.every(id, every); repo.replan() }
    }

    /** Delete with Undo (5 s): gone from the phone at once; pagel.uz hears of it only after the Undo window. */
    fun delete(id: String, onGone: () -> Unit) {
        viewModelScope.launch {
            val op = tasks.delete(id) ?: return@launch
            onGone()
            toasts.show("Habit deleted", action = "Undo", millis = 5_000) { viewModelScope.launch { tasks.undoDelete(id, op) } }
        }
    }
    /**
     * Drag-to-link (note 4): [id] dropped on [onto] now comes after it; dropped on the habit it already follows, the
     * link comes off. A loop is refused by [setAnchor]. Undo puts the old link back.
     */
    fun link(id: String, onto: String) {
        val L = org.pagel.ai.data.habits.HabitLook
        val was = ui.value.prefs[id]?.after
        val unlink = (L.anchorOf(was) as? org.pagel.ai.data.habits.HabitLook.Anchor.Habit)?.id == onto
        if (!unlink && (onto == id || L.leadsTo(onto, id, ui.value.prefs))) { toasts.show("That would make a loop"); return }
        val name = ui.value.byId[onto]?.let { it.title ?: it.text } ?: "it"
        viewModelScope.launch {
            prefsRepo.after(id, if (unlink) null else onto)
            toasts.show(if (unlink) "Unlinked" else "Linked: after $name", action = "Undo") { viewModelScope.launch { prefsRepo.after(id, was) } }
        }
    }

    /** A new habit (note 8): from today, at the part's usual time; Undo takes it straight back. */
    fun create(name: String, part: Part, every: String = "day", remind: Boolean = false) {
        val text = name.trim().takeIf(String::isNotEmpty) ?: return
        val time = when (part) { Part.MORNING -> "07:00"; Part.AFTERNOON -> "13:00"; Part.EVENING -> "19:00"; Part.ANYTIME -> null }
        val date = repo.today()
        viewModelScope.launch {
            val id = todayRepo.add(text, project = "me", priority = 2, due = if (time == null) date else "$date $time", every = every, onToday = false)
            if (remind) repo.setReminder(id, true)
            repo.replan()
            chosenPart.value = if (part == Part.ANYTIME) chosenPart.value else part
            toasts.show("Habit added", action = "Undo", millis = 5_000) { viewModelScope.launch { tasks.delete(id); repo.replan() } }
        }
    }
    fun setIdentity(id: String, line: String?) =viewModelScope.launch { prefsRepo.identity(id, line) }
    fun setMinutes(id: String, n: Int?) = viewModelScope.launch { prefsRepo.minutes(id, n) }

    /**
     * Namoz (Boss 2026-10-28): the five prayers as daily habits in the group "namoz", dated 1 November at each prayer's
     * time that day. Never twice: a prayer already on the phone is not added again.
     */
    fun startNamoz() {
        val have = ui.value.byId.values.filter(::isNamoz).mapNotNull { Namoz.prayerOf(it.title ?: it.text) }.toSet()
        val start = LocalDate.parse(Namoz.START)
        viewModelScope.launch {
            for (w in Namoz.windows(start)) {
                if (w.prayer in have) continue
                todayRepo.add(w.prayer.uz, project = "me", priority = 2, due = "${Namoz.START} ${w.label().take(5)}", every = "day", onToday = false, group = Namoz.GROUP)
            }
            toasts.show("Namoz starts on 1 November")
        }
    }

    fun setReminder(id: String, on: Boolean) {
        viewModelScope.launch { repo.setReminder(id, on) }
    }

    fun setTime(id: String, time: String) {
        viewModelScope.launch {
            val t = ui.value.byId[id] ?: return@launch
            tasks.due(id, (t.due?.take(10) ?: repo.today()) + " " + time)
            repo.replan()
        }
    }

    fun askExactOnce(needed: Boolean, open: () -> Unit) {
        if (!needed) return
        viewModelScope.launch {
            val first = withContext(io) { if (prefsFile.getBoolean(ASKED_EXACT, false)) false else { prefsFile.edit().putBoolean(ASKED_EXACT, true).apply(); true } }
            if (first) open()
        }
    }

    companion object {
        const val FOLD_MS = 1_250L
        private const val TIMER_TICK_MS = 1_000L
        private const val ASKED_EXACT = "asked-exact-alarm"
    }
}
