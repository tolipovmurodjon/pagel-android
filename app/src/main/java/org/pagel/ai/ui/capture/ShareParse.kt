package org.pagel.ai.ui.capture

import org.pagel.ai.data.inbox.CAPTURE_MAX

/** What a share becomes in the Inbox: the words, and who said them (null = pagel.uz reads the link itself). */
data class Shared(val text: String, val source: String?)

private val URL_ONLY = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
private val URL_IN_TEXT = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

/**
 * Reads a share intent (the Android share sheet: SEND with text, or a picture) into one Inbox save. A link alone stays
 * the link, so pagel.uz files it with the link as its source; a link with words keeps the words and the link; plain text
 * is Boss's own. Null when there is nothing to save: no text (a picture alone cannot be saved until pagel.uz takes
 * pictures). The text is cut at pagel.uz's limit, never trusted further (android-intent-security).
 */
fun parseShare(action: String?, text: CharSequence?, subject: CharSequence?): Shared? {
    if (action != "android.intent.action.SEND") return null
    val body = text?.toString()?.trim().orEmpty().take(CAPTURE_MAX)
    if (body.isEmpty()) return null
    if (URL_ONLY.matches(body)) return Shared(body, null)
    val title = subject?.toString()?.trim().orEmpty().take(200)
    // A page title sent beside a link is worth keeping: "Title" then the link, on separate lines of one save.
    val words = if (title.isNotEmpty() && URL_IN_TEXT.containsMatchIn(body) && !body.contains(title)) "$title\n$body" else body
    return Shared(words.take(CAPTURE_MAX), "Boss said (shared)")
}
