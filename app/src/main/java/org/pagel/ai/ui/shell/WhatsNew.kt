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
import androidx.compose.ui.res.stringResource
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

/** The sheet: the round's title, then each "try this" line with a tick once tried; a tap opens [onOpen] its screen. */
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    GlassSheet(onDismiss = onDismiss) { WhatsNewContent(onOpen) }
}

@Composable
fun WhatsNewContent(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val items = stringArrayResource(R.array.whats_new_items).map { it.substringBefore('|') to it.substringAfter('|', "today") }
    var tried by remember { mutableStateOf(runCatching { WhatsNewPrefs.tried(ctx) }.getOrDefault(emptySet())) }
    Column(Modifier.fillMaxWidth().padding(bottom = Space.xl)) {
        SheetTitle("What's new", "${tried.count { t -> items.any { it.first == t } }}/${items.size}")
        Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
            modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.md))
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column(Modifier.padding(vertical = Space.xs)) {
                for ((line, where) in items) {
                    val done = line in tried
                    val press = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
                            .clickable(press, indication = null, role = Role.Button) {
                                WhatsNewPrefs.markTried(ctx, line); tried = tried + line; onOpen(where)
                            }
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
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, modifier = Modifier.weight(1f).padding(vertical = Space.sm))
                        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
    }
}
