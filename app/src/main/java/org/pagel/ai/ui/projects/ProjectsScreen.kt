package org.pagel.ai.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Pane
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import org.pagel.ai.ui.glass.SkeletonRows

@Composable
fun ProjectsRoute(contentPadding: PaddingValues, reselect: Int, vm: ProjectsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ProjectsScreen(ui, contentPadding, reselect)
}

/** Phase 1's proof screen: every project from pagel.uz, live. Today's real list replaces it in Phase 2. */
@Composable
fun ProjectsScreen(ui: ProjectsUiState, contentPadding: PaddingValues, reselect: Int = 0) {
    val list = rememberLazyListState()
    // Re-tapping the tab scrolls back to the top (RULES.md, navigation).
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "head", contentType = "head") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
                Text("Projects", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                Text("${ui.projects.sumOf { it.openCount }} open tasks" + if (ui.status.isEmpty()) "" else " · ${ui.status}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
        }
        if (ui.loading) {
            item(key = "loading", contentType = "loading") {
                SkeletonRows(6)
            }
        }
        if (!ui.loading && ui.projects.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text("No projects yet. Add one on pagel.uz and it appears here in a second.", style = MaterialTheme.typography.bodyLarge,
                    color = Glass.Muted, modifier = Modifier.padding(20.dp))
            }
        }
        // One glass card with thin dividers, like the web's Habits card (Boss 8/10); ~17 rows, so one pane is cheap.
        if (ui.projects.isNotEmpty()) item(key = "list", contentType = "list") {
            Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ui.projects.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                        ProjectRow(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(p: ProjectEntity) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProjectTile(p.name, p.color, p.title)
        Spacer(Modifier.width(14.dp))
        Text(shortName(p.name, p.title), style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(p.openCount.toString(), style = MaterialTheme.typography.labelSmall, color = Glass.PrimaryInk,
            modifier = Modifier.background(Glass.PrimarySoft, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/**
 * The name the web shows ("ABABIL", not "ABABIL — first-aid delivery drone plan"): a known project's label, else the
 * plan title cut at its dash or colon, without a trailing "plan".
 */
internal fun shortName(name: String, title: String): String = LABELS[name] ?: title
    .substringBefore(" — ").substringBefore(": ").removeSuffix(" plan").removeSuffix(" Plan").trim()
    .replaceFirstChar { it.uppercase() }.ifEmpty { name }

private val LABELS = mapOf(
    "humopedia" to "Humopedia", "ababil" to "ABABIL", "pagel" to "PAGEL", "uzcosmos" to "UzCosmos", "me" to "Me",
    "university" to "University", "gamedev" to "Game dev", "games" to "Games", "samarqand" to "Samarqand", "labs" to "Labs",
    "motion" to "Motion", "muhub" to "MuHub", "ovoz" to "Ovoz", "brilliant" to "Brilliant", "autonomy" to "Autonomy", "design" to "Design",
)
