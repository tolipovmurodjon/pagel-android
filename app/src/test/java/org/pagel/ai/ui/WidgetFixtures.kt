package org.pagel.ai.ui

import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.widget.CaloriesGlance
import org.pagel.ai.widget.HabitsGlance
import org.pagel.ai.widget.NamozGlance
import org.pagel.ai.widget.NextTaskGlance
import org.pagel.ai.widget.TodayGlance
import org.pagel.ai.widget.habitsGlance
import org.pagel.ai.widget.namozGlance
import org.pagel.ai.widget.nextTaskGlance
import org.pagel.ai.widget.todayGlance
import java.time.LocalDate
import java.time.LocalTime

/** One fake day for the widget tests and pictures: Boss's kind of tasks, habits, prayers and kcal. */
object WidgetFixtures {
    private fun task(id: String, text: String, due: String? = null, every: String? = null, status: String = "open", times: Int = 1, project: String = "pagel") = TaskEntity(
        id = id, project = project, phase = "Now", sort = 1.0, text = text, title = null, status = status, priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-11-01T05:00:00Z", serverVersion = 1, lastModified = 0, times = times,
    )

    val projects = listOf(
        ProjectEntity("pagel", "PAGEL", null, 0, 3), ProjectEntity("humopedia", "Humopedia", null, 1, 5), ProjectEntity("me", "Me", null, 2, 2),
    )

    val todayTasks = listOf(
        task("t_a", "Call the bank about the new card", due = "2026-11-01 15:00"),
        task("t_b", "Send the lesson draft to Dilnoza", project = "humopedia"),
        task("t_c", "Book the gym slot for Tuesday", project = "me"),
        task("t_d", "Review the widget pictures"),
    )

    /** Today's 3 as dealt from [todayTasks], with the day's count set to "12 done · 1 left" for the picture. */
    val today: TodayGlance = TodayGlance(todayGlance(todayTasks, projects, doneToday = 12, today = LocalDate.parse("2026-11-01")).rows, done = 12, open = 1)

    val next: NextTaskGlance = nextTaskGlance(
        listOf(task("t_a", "Call the bank. Ask for the new card")),
        listOf(StepEntity("t_a", 0, "Call before 17:00", null, done = false)),
        projects,
    )!!

    private const val DAY = "2026-11-01"
    private val habitRows = listOf(
        task("g", "Gym", due = "$DAY 18:00", every = "day"),
        task("w", "Drink water", due = DAY, every = "day", times = 3),
        task("r", "Read 10 pages", due = "$DAY 21:30", every = "day"),
    )
    val habits: HabitsGlance = habitsGlance(habitRows, listOf(HabitTickEntity(DAY, "g", 1), HabitTickEntity(DAY, "w", 2)), DAY)

    /** Six habits for the bigger picture (two rows of rings). */
    val habitsMany: HabitsGlance = habitsGlance(
        habitRows + listOf(
            task("s", "Stretch for ten minutes", due = "$DAY 07:30", every = "day"),
            task("c", "Morning coffee, no phone", due = "$DAY 08:00", every = "day"),
            task("b", "Wind down: phone away", due = "$DAY 22:00", every = "day"),
        ),
        listOf(HabitTickEntity(DAY, "g", 1), HabitTickEntity(DAY, "w", 2), HabitTickEntity(DAY, "s", 1)),
        DAY,
    )

    private fun prayers(day: String) = Namoz.Prayer.entries.map { task("n_${it.name}", it.uz, due = day, every = "day") }

    val namozNov1: NamozGlance = namozGlance(prayers(DAY), listOf(HabitTickEntity(DAY, "n_BOMDOD", 1)), LocalDate.parse(DAY), LocalTime.of(13, 0))
    val namozOct31: NamozGlance = namozGlance(prayers("2026-10-31"), emptyList(), LocalDate.parse("2026-10-31"), LocalTime.of(13, 0))

    val calories = CaloriesGlance("k", 1420, 2000)
}
