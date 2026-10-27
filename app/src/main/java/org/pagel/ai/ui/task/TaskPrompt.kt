package org.pagel.ai.ui.task

/**
 * The task as a Claude Code prompt, word for word the web's lib/task-brief.ts runPrompt (no report file, no run id):
 * the panel's copy button puts it on the clipboard to paste into the session that does the work.
 */
fun runPrompt(project: String, task: String, priority: Int): String {
    val cards = Regex("""\[\[([^\]|]+)\]\]""").findAll(task).map { "D:\\Brain\\" + it.groupValues[1].replace("/", "\\") + ".md" }.toList()
    val read = if (cards.isEmpty()) "" else " Read the linked file${if (cards.size > 1) "s" else ""}: ${cards.joinToString(", ")}."
    return listOf(
        "# Task from Boss's dashboard ($project, P$priority)",
        "",
        "> $task",
        "",
        "Boss asked you to do this. Do the whole job here. He is watching.",
        "",
        "## Steps",
        "1. Read this repo's CLAUDE.md and D:\\Brain\\index.md, and the $project project's NOW phase with the pagel MCP project tool.$read",
        "2. Research before you build. Find at least 3 candidates (GitHub search sorted by stars, plus the web). A repo named in the task is a lead, not the answer.",
        "3. Vet each candidate: stars, forks, created, last push, license, open and closed issues, its own tests or evals. Distrust a repo under 100 stars or under a month old unless nothing better exists, and say so. Read its issues for known harm.",
        "4. Check the pick against Boss's rules (~/.claude/CLAUDE.md, this repo's CLAUDE.md, the brain cards). Name every conflict and every downside (where it must NOT be used).",
        "5. Show Boss a comparison table and your pick, with the cheapest option that works (a short prompt or rule often beats a whole plugin). Wait for his \"go\" before you install anything or change more than one file.",
        "6. Make the smallest change. Match the repo's style.",
        "7. Verify for real: run the tests, the build or the tool itself. \"Should work\" is not done. Tell Boss in one line how to test it himself.",
        "8. When it is done, finish the task in PAGEL: the pagel MCP complete_task tool (or `pagel done <id>`). Do not finish it if it is not done.",
        "9. End with a short report: `status: done` or `status: blocked`, then 3-6 short bullets: what changed (file:line), commits, what you tested (the command), what you did not test, the next step.",
        "",
        "Global rules still apply: no secrets, no pushes to main, no destructive commands without asking.",
    ).joinToString("\n")
}
