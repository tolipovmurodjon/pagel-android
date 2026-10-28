package org.pagel.ai.data.notebook

import java.text.Normalizer

/** What a search result is, in the order the results show (Boss: "find everything, like an expert"). */
enum class HitKind(val wire: String, val label: String) {
    TASK("task", "Tasks"),
    STEP("step", "Steps"),
    NOTE("note", "Notes"),
    QUICK_NOTE("quick_note", "Quick notes"),
    RESOURCE("resource", "Resources");

    companion object {
        fun of(wire: String): HitKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One result. [ref] opens it: a task's id, "taskId#position" for a step, a note's vault path, "project#n" for a quick
 * note, a resource's id. [snippet] marks the matched words with ** as pagel.uz's search.ts does.
 */
data class Hit(val kind: HitKind, val ref: String, val title: String, val snippet: String, val project: String? = null, val updated: String? = null)

/** One group of results under its kind's heading. */
data class HitGroup(val kind: HitKind, val hits: List<Hit>)

/**
 * The one search box (Phase 6b), on the phone's side: words as pagel.uz's search.ts splits them, the phone's own Room
 * copy searched at once (offline too), pagel.uz's answer merged in when it comes, grouped by kind. Never the Compass,
 * the journal or Kundalik: they are not in any table this reads.
 */
object SearchLogic {
    /** Most results a group shows before "more". */
    const val PER_GROUP = 6

    /**
     * The query's words: letters and digits, NFC, lowercase; an apostrophe (' ‘ ’ ʻ ʼ `) splits a word as the server's
     * tokenizer does, so "o'zbek" finds oʻzbek and o‘zbek.
     */
    fun words(q: String): List<String> =
        Normalizer.normalize(q, Normalizer.Form.NFC).lowercase().split(Regex("""[^\p{L}\p{N}]+|[ʻʼ]+""")).filter { it.isNotEmpty() }

    private fun tokens(text: String): List<IntRange> {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFC)
        return Regex("""[\p{L}\p{N}&&[^ʻʼ]]+""").findAll(norm).map { it.range }.toList()
    }

    /** Every query word starts a word of [text] (prefix match, case-insensitive). */
    fun matches(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val norm = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        val starts = tokens(norm).map { norm.substring(it) }
        return words.all { w -> starts.any { it.startsWith(w) } }
    }

    /**
     * A short piece of [text] around the first match, the matched words wrapped in ** (as the server's snippet()),
     * "…" where it was cut. Plain text: markdown marks are taken out first.
     */
    fun snippet(text: String, words: List<String>, around: Int = 48): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("""[*_`#>\[\]]+"""), "").replace(Regex("""\s+"""), " ").trim()
        val low = plain.lowercase()
        val hits = tokens(low).filter { r -> words.any { low.substring(r).startsWith(it) } }
        if (hits.isEmpty()) return plain.take(around * 2).let { if (plain.length > it.length) "$it…" else it }
        val from = (hits.first().first - around).coerceAtLeast(0).let { s -> if (s == 0) 0 else plain.indexOf(' ', s).let { if (it < 0 || it > hits.first().first) s else it + 1 } }
        val to = (hits.first().first + around * 2).coerceAtMost(plain.length).let { e -> if (e == plain.length) e else plain.lastIndexOf(' ', e).let { if (it <= hits.first().last) e else it } }
        val sb = StringBuilder()
        if (from > 0) sb.append('…')
        var at = from
        for (r in hits) {
            if (r.first < from || r.last >= to) continue
            sb.append(plain, at, r.first).append("**").append(plain, r.first, r.last + 1).append("**")
            at = r.last + 1
        }
        sb.append(plain, at, to)
        if (to < plain.length) sb.append('…')
        return sb.toString()
    }

    /** A snippet's runs: (text, is it a matched word). */
    fun parts(snippet: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        snippet.split("**").forEachIndexed { i, s -> if (s.isNotEmpty()) out += s to (i % 2 == 1) }
        return out
    }

    /**
     * pagel.uz's answer first (it ranks by bm25), then the phone's own hits it did not have (a task made offline, a
     * step), one row per thing, grouped in [HitKind] order.
     */
    fun merge(remote: List<Hit>, local: List<Hit>): List<Hit> {
        val seen = HashSet<Pair<HitKind, String>>()
        return (remote + local).filter { seen.add(it.kind to it.ref) }
    }

    fun group(hits: List<Hit>): List<HitGroup> =
        HitKind.entries.mapNotNull { k -> hits.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { HitGroup(k, it) } }

    /** What the phone has in Room, searched on the phone: a title match ranks above a body match, newer first. */
    fun local(
        q: String,
        tasks: List<LocalTask>,
        steps: List<LocalStep>,
        notes: List<LocalNote>,
        quickNotes: List<LocalQuickNote>,
    ): List<Hit> {
        val w = words(q)
        if (w.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Int, Hit>>()
        for (t in tasks) {
            val words = (t.title?.let { "$it " }.orEmpty()) + t.text
            if (matches(words, w)) out += 0 to Hit(HitKind.TASK, t.id, t.title ?: t.text, snippet(t.text, w), t.project, t.updated)
        }
        val byTask = tasks.associateBy { it.id }
        for (s in steps) {
            if (matches(s.text, w)) out += 0 to Hit(HitKind.STEP, "${s.taskId}#${s.position}", s.text, snippet(byTask[s.taskId]?.let { it.title ?: it.text } ?: s.text, w), byTask[s.taskId]?.project)
        }
        for (n in notes) {
            val inTitle = matches(n.title, w)
            if (inTitle || (n.body != null && matches(n.body, w))) {
                out += (if (inTitle) 0 else 1) to Hit(HitKind.NOTE, n.path, n.title, snippet(n.body ?: n.title, w), n.path.substringBeforeLast('/', ""), n.updated)
            }
        }
        for (n in quickNotes) {
            if (matches(n.text, w)) out += 1 to Hit(HitKind.QUICK_NOTE, "${n.project}#${n.n}", n.text.take(120), snippet(n.text, w), n.project, n.day)
        }
        return out.sortedWith(compareBy<Pair<Int, Hit>> { it.first }.thenByDescending { it.second.updated.orEmpty() }).map { it.second }
    }
}

/** The plain fields local search reads (Room rows are mapped to these, so the logic stays testable). */
data class LocalTask(val id: String, val text: String, val title: String?, val project: String, val updated: String?)
data class LocalStep(val taskId: String, val position: Int, val text: String)
data class LocalNote(val path: String, val title: String, val body: String?, val updated: String?)
data class LocalQuickNote(val project: String, val n: Int, val text: String, val day: String?)
