package org.pagel.ai.data.notebook

import java.text.Normalizer

/** One run of text inside a line: plain, bold, italic, code, a web link or an Obsidian wikilink. */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val text: String) : Inline
    data class Italic(val text: String) : Inline
    data class Code(val text: String) : Inline
    data class Link(val text: String, val url: String) : Inline
    /** `[[target#heading|shown]]`: [target] as typed (no .md), [shown] what the reader sees. */
    data class WikiLink(val target: String, val shown: String, val heading: String?) : Inline
}

/** One block of a note, top to bottom, as the reader draws it. */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    /** A list line: [depth] 0 for the outer list; [marker] "•" or "1."; [checked] null when it is not a task box. */
    data class Item(val depth: Int, val marker: String, val checked: Boolean?, val inlines: List<Inline>) : Block
    data class Quote(val inlines: List<Inline>) : Block
    data class Code(val text: String, val lang: String?) : Block
    /** `![[picture.png]]` ([wiki] true: a vault file) or `![alt](url)`. */
    data class Image(val target: String, val alt: String, val wiki: Boolean) : Block
    data object Rule : Block
}

/**
 * The vault's markdown as Boss writes it in Obsidian (Phase 6b): headings, lists and task boxes, quotes and callouts,
 * code, pictures, web links and wikilinks. Pure Kotlin: the reader draws [parse]'s blocks, the tests read them. Rules
 * match pagel.uz's vault-store.ts (front matter stripped, the wikilink grammar, how a short link finds its note).
 */
object Markdown {
    private val FRONT = Regex("""^---\n[\s\S]*?\n---(\n|$)""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val ITEM = Regex("""^([ \t]*)([-*+]|\d{1,3}[.)])\s+(?:\[([ xX])]\s+)?(.*)$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
    private val CALLOUT = Regex("""^\[!([A-Za-z-]+)][+-]?\s*(.*)$""")
    private val FENCE = Regex("""^\s{0,3}(```|~~~)\s*([\w+-]*)\s*$""")
    private val WIKI_IMAGE = Regex("""^!\[\[([^\]|#\n]+?)(?:\|[^\]\n]*)?]]\s*$""")
    private val MD_IMAGE = Regex("""^!\[([^\]\n]*)]\(([^)\s]+)\)\s*$""")
    private val PICTURE = Regex("""\.(png|jpe?g|gif|webp)$""", RegexOption.IGNORE_CASE)

    /**
     * Group 1-4: wikilink target, # or ^, heading, shown · 5-6: [text](url) · 7: **bold** · 8: __bold__ · 9: `code` ·
     * 10: *italic* · 11: _italic_ (only between non-letters, so snake_case words stay words).
     */
    private val INLINE = Regex(
        """!?\[\[([^\]|#^\n]+)(?:([#^])([^\]|\n]*))?(?:\|([^\]\n]*))?]]""" +
            """|\[([^\]\n]+)]\(([^)\s]+)\)""" +
            """|\*\*([^*\n]+)\*\*|__([^_\n]+)__""" +
            """|`([^`\n]+)`""" +
            """|\*([^*\s][^*\n]*)\*|(?<![\p{L}\p{N}])_([^_\s][^_\n]*)_(?![\p{L}\p{N}])""",
    )

    fun stripFrontMatter(md: String): String = FRONT.replace(normal(md), "")

    private fun normal(md: String) = md.replace("\r\n", "\n").replace('\r', '\n')

    /** The note's title, as pagel.uz's titleOf: front matter's title, else the first # heading, else the file's name. */
    fun titleOf(path: String, md: String): String {
        val text = normal(md)
        val front = FRONT.find(text)?.value
        val fromFront = front?.let { Regex("""(?m)^title:\s*["']?(.+?)["']?\s*$""").find(it)?.groupValues?.get(1) }
        val fromHeading = Regex("""(?m)^#\s+(.+)$""").find(stripFrontMatter(text))?.groupValues?.get(1)
        return (fromFront ?: fromHeading ?: path.substringAfterLast('/').substringBeforeLast('.')).trim().take(200)
    }

    fun parse(md: String): List<Block> {
        val lines = stripFrontMatter(md).split('\n')
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(inlines(para.joinToString("\n")))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val close = fence.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim() != close) { code += lines[i]; i++ }
                out += Block.Code(code.joinToString("\n"), fence.groupValues[2].ifBlank { null })
                i++
                continue
            }
            when {
                line.isBlank() -> flush()
                RULE.matches(line) -> { flush(); out += Block.Rule }
                HEADING.matchEntire(line) != null -> {
                    flush()
                    val m = HEADING.matchEntire(line)!!
                    out += Block.Heading(m.groupValues[1].length, inlines(m.groupValues[2]))
                }
                WIKI_IMAGE.matchEntire(line.trim()) != null && PICTURE.containsMatchIn(WIKI_IMAGE.matchEntire(line.trim())!!.groupValues[1].trim()) -> {
                    flush()
                    val t = WIKI_IMAGE.matchEntire(line.trim())!!.groupValues[1].trim()
                    out += Block.Image(t, t.substringAfterLast('/'), wiki = true)
                }
                MD_IMAGE.matchEntire(line.trim()) != null -> {
                    flush()
                    val m = MD_IMAGE.matchEntire(line.trim())!!
                    out += Block.Image(m.groupValues[2], m.groupValues[1], wiki = false)
                }
                ITEM.matchEntire(line) != null -> {
                    flush()
                    val m = ITEM.matchEntire(line)!!
                    val indent: Int = m.groupValues[1].fold(0) { n, c -> n + if (c == '\t') 4 else 1 }
                    val raw = m.groupValues[2]
                    val marker = if (raw[0].isDigit()) raw.dropLast(1) + "." else "•"
                    val box = m.groupValues[3]
                    out += Block.Item((indent / 2).coerceAtMost(4), marker, if (box.isEmpty()) null else box != " ", inlines(m.groupValues[4]))
                }
                QUOTE.matchEntire(line) != null -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && QUOTE.matchEntire(lines[i]) != null) {
                        quoted += QUOTE.matchEntire(lines[i])!!.groupValues[1]
                        i++
                    }
                    // An Obsidian callout ("> [!note] Title") reads as a quote with its title first.
                    val first = quoted.firstOrNull()?.let { CALLOUT.matchEntire(it.trim()) }
                    if (first != null) quoted[0] = first.groupValues[2].ifBlank { first.groupValues[1].replaceFirstChar(Char::uppercase) }
                    out += Block.Quote(inlines(quoted.filter { it.isNotBlank() }.joinToString("\n")))
                    continue
                }
                else -> para += line.trimEnd()
            }
            i++
        }
        flush()
        return out
    }

    fun inlines(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var at = 0
        fun plain(s: String) {
            if (s.isEmpty()) return
            val last = out.lastOrNull()
            if (last is Inline.Text) out[out.size - 1] = Inline.Text(last.text + s) else out += Inline.Text(s)
        }
        for (m in INLINE.findAll(text)) {
            plain(text.substring(at, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> {
                    val target = g[1].trim().removeSuffix(".md")
                    val heading = if (g[2] == "#") g[3].trim().ifEmpty { null } else null
                    Inline.WikiLink(target, g[4].trim().ifEmpty { target.substringAfterLast('/') + (heading?.let { " › $it" } ?: "") }, heading)
                }
                g[5].isNotEmpty() -> Inline.Link(g[5], g[6])
                g[7].isNotEmpty() -> Inline.Bold(g[7])
                g[8].isNotEmpty() -> Inline.Bold(g[8])
                g[9].isNotEmpty() -> Inline.Code(g[9])
                g[10].isNotEmpty() -> Inline.Italic(g[10])
                else -> Inline.Italic(g[11])
            }
            at = m.range.last + 1
        }
        plain(text.substring(at))
        return out
    }

    /** The note's wikilink targets as pagel.uz keys them (vault-store.ts wikilinks): lowercase, no .md, no pictures. */
    fun wikilinks(md: String): List<String> = parse(md).flatMap { b ->
        when (b) {
            is Block.Heading -> b.inlines
            is Block.Paragraph -> b.inlines
            is Block.Item -> b.inlines
            is Block.Quote -> b.inlines
            else -> emptyList()
        }
    }.filterIsInstance<Inline.WikiLink>().map { key(it.target) }.filter { !PICTURE.containsMatchIn(it) }.distinct()

    /** How a path or a link target is compared: NFC, lowercase, without .md. */
    fun key(p: String): String = Normalizer.normalize(p.trim(), Normalizer.Form.NFC).lowercase().removeSuffix(".md")

    /**
     * Which note a wikilink opens, as pagel.uz resolves it: the exact path first, else Obsidian's short link (the note's
     * own name, or a path ending), the shortest path winning when two notes share a name. Null: no such note here.
     */
    fun resolve(target: String, paths: Collection<String>): String? {
        val t = key(target)
        paths.firstOrNull { key(it) == t }?.let { return it }
        return paths.filter { key(it).endsWith("/$t") || key(it.substringAfterLast('/')) == t }.minByOrNull { it.length }
    }

    /** A picture embed's file: its own path, else the vault file with that name nearest the top. */
    fun resolveFile(target: String, files: Collection<String>): String? {
        val t = Normalizer.normalize(target.trim(), Normalizer.Form.NFC).lowercase()
        files.firstOrNull { it.lowercase() == t }?.let { return it }
        return files.filter { it.lowercase().endsWith("/$t") }.minWithOrNull(compareBy({ it.count { c -> c == '/' } }, { it.length }))
    }
}
