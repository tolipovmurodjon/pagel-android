package org.pagel.ai.ui.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.pagel.ai.R
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale

/**
 * "What's new" (Boss 2026-10-28: never feel lost when testing an update): once after an update (never on a fresh
 * install, so the CI walk is not covered), a glass sheet with what to try, each line opening its screen and ticked once
 * tried. The list is res/values/whats_new.xml, updated every round. Prefs read off Main.
 */
object WhatsNewPrefs {
    private const val FILE = "pagel-whats-new"

    /** True once per version, after an update (lastUpdateTime > firstInstallTime); marks it seen. */
    fun showOnce(ctx: Context): Boolean = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val version = info.versionName.orEmpty()
        val show = info.lastUpdateTime > info.firstInstallTime && p.getString("seen", "") != version
        p.edit().putString("seen", version).apply()
        show
    }.getOrDefault(false)

    fun tried(ctx: Context): Set<String> = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("tried", emptySet()).orEmpty().toSet()
    fun markTried(ctx: Context, line: String) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.edit().putStringSet("tried", tried(ctx) + line).apply()
    }
}

/** One version's lines: "#0.3.33 · Habits, fixed" then its "words|where" lines (res/values/whats_new.xml). */
data class WhatsNewGroup(val version: String, val title: String, val lines: List<Pair<String, String>>)

/** Groups the resource's lines, newest first as written; lines before any "#" header form one untitled group. */
fun whatsNewGroups(items: List<String>): List<WhatsNewGroup> {
    val out = mutableListOf<WhatsNewGroup>()
    for (raw in items) {
        val item = raw.trim()
        if (item.startsWith("#")) {
            val head = item.removePrefix("#").trim()
            out += WhatsNewGroup(head.substringBefore(" · ").trim(), head.substringAfter(" · ", "").trim(), emptyList())
        } else if (item.isNotEmpty()) {
            if (out.isEmpty()) out += WhatsNewGroup("", "", emptyList())
            val last = out.removeAt(out.lastIndex)
            out += last.copy(lines = last.lines + (item.substringBefore('|') to item.substringAfter('|', "today")))
        }
    }
    return out
}

/**
 * The sheet (Boss 2026-10-29: "11/23" in one long card, the last rows cut and no scroll): full height, one scrolling
 * list whose end clears the system bar. Newest first: this version's lines open on top with their own counter, the ones
 * already tried sink to the bottom; older versions are folded rows ("0.3.30 · Compass · 2 ✓") that open on tap.
 */
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    GlassSheet(onDismiss = onDismiss, full = true) { WhatsNewContent(onOpen) }
}

@Composable
fun WhatsNewContent(onOpen: (String) -> Unit, items: List<String>? = null) {
    val ctx = LocalContext.current.applicationContext
    val res = stringArrayResource(R.array.whats_new_items).toList()
    val groups = remember(items, res) { whatsNewGroups(items ?: res) }
    var tried by remember { mutableStateOf(runCatching { WhatsNewPrefs.tried(ctx) }.getOrDefault(emptySet())) }
    var open by remember { mutableStateOf(setOf<String>()) }
    val current = groups.firstOrNull()
    fun tap(line: String, where: String) { runCatching { WhatsNewPrefs.markTried(ctx, line) }; tried = tried + line; onOpen(where) }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxWidth().fillMaxHeight(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.xl),
    ) {
        item(key = "head", contentType = "head") {
            val count = current?.let { g -> "${g.lines.count { it.first in tried }}/${g.lines.size}" }
            SheetTitle("What's new", count)
        }
        if (current != null) {
            item(key = "sub", contentType = "sub") {
                Text(listOf(current.version, current.title).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                    color = Glass.Muted, modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.md))
            }
            item(key = "current", contentType = "group") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                    Column(Modifier.padding(vertical = Space.xs)) {
                        // Tried lines sink to the bottom of their group (the order is read when the sheet opens a group).
                        for ((line, where) in current.lines.sortedBy { it.first in tried }) TryRow(line, where, line in tried) { tap(line, where) }
                    }
                }
            }
        }
        if (groups.size > 1) item(key = "older", contentType = "label") {
            Text("Earlier", style = MaterialTheme.typography.labelLarge, color = Glass.Muted,
                modifier = Modifier.padding(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.sm))
        }
        for (g in groups.drop(1)) {
            val key = g.version + g.title
            val isOpen = key in open
            item(key = "g-$key", contentType = "group") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs)) {
                    Column {
                        val press = remember { MutableInteractionSource() }
                        val done = g.lines.count { it.first in tried }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
                                .clickable(press, indication = null, role = Role.Button, onClickLabel = if (isOpen) "Close" else "Open") { open = if (isOpen) open - key else open + key }
                                .semantics(mergeDescendants = true) { stateDescription = if (isOpen) "Open" else "Folded" }
                                .padding(horizontal = Space.paneInset),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(listOf(g.version, g.title).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                                color = Glass.Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(if (done == g.lines.size) "${g.lines.size} ✓" else "$done/${g.lines.size}", style = Glass.NumberText, color = Glass.Muted,
                                modifier = Modifier.padding(horizontal = Space.sm))
                            Icon(if (isOpen) TabIcons.ChevronDown else TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
                        }
                        if (isOpen) for ((line, where) in g.lines.sortedBy { it.first in tried }) TryRow(line, where, line in tried) { tap(line, where) }
                    }
                }
            }
        }
    }
}

/** One "try this" line: a tick once tried, the words, a chevron; a tap opens its screen. */
@Composable
private fun TryRow(line: String, where: String, done: Boolean, onTap: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = "Try: $line"; stateDescription = if (done) "Tried" else "Not yet" }
            .padding(horizontal = Space.paneInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).background(if (done) Glass.Primary else Color.Transparent, CircleShape)
                // An untried ring ≥ 3:1 on the pane (round C checker: 0.10 ink was ~1.3:1).
                .then(if (done) Modifier else Modifier.border(1.5.dp, Glass.Ink.copy(alpha = 0.55f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(Space.md))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (done) Glass.Muted else Glass.Ink, modifier = Modifier.weight(1f).padding(vertical = Space.sm))
        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
    }
}
