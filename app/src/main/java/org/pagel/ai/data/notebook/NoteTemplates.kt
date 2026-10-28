package org.pagel.ai.data.notebook

import java.time.LocalDate

/**
 * New notes written on the phone (Phase 6b): a blank note, today's daily note, a book note. Each lands in a folder Boss
 * opened; the path is "<folder>/<name>.md" with a name Windows and Obsidian can both hold.
 */
object NoteTemplates {
    /** The book note (Boss's books: title, author, why I read it, the highlights, my own words). */
    fun book(title: String, author: String): String = buildString {
        append("# ").append(title.trim().ifEmpty { "Book" }).append("\n\n")
        append("Author: ").append(author.trim()).append("\n\n")
        append("## Why I read it\n\n\n")
        append("## Highlights\n\n- \n\n")
        append("## My words\n\n")
    }

    fun daily(day: LocalDate): String = "# $day\n\n"

    fun blank(title: String): String = "# ${title.trim()}\n\n"

    /** A file name from a title: no characters Windows refuses, no leading dot, at most 80 characters. */
    fun fileName(title: String): String {
        val clean = title.replace(Regex("""[\u0000-\u001f<>:"/\\|?*#^\[\]]"""), " ").replace(Regex("""\s+"""), " ").trim().trimStart('.')
        return clean.take(80).trim().ifEmpty { "Untitled" }
    }

    /** The note's path in [folder] ("." = the vault's top level). */
    fun pathIn(folder: String, name: String): String = (if (folder == "." || folder.isEmpty()) "" else "$folder/") + fileName(name) + ".md"

    /** Kundalik is closed for good (pagel.uz vault-store.ts LOCKED): any path with a Kundalik part, any case. */
    fun isLocked(path: String): Boolean = path.split('/').any { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC).lowercase() == "kundalik" }
}
