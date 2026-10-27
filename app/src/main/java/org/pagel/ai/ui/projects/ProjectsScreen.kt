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
                Text("${ui.projects.sumOf { it.openCount }} open tasks · ${ui.status}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
        }
        if (ui.loading) {
            item(key = "loading", contentType = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Glass.Primary) }
            }
        }
        if (!ui.loading && ui.projects.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text("No projects yet. Add one on pagel.uz and it appears here in a second.", style = MaterialTheme.typography.bodyLarge,
                    color = Glass.Muted, modifier = Modifier.padding(20.dp))
            }
        }
        items(ui.projects, key = { it.name }, contentType = { "project" }) { ProjectRow(it) }
    }
}

@Composable
private fun ProjectRow(p: ProjectEntity) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProjectTile(p.name, p.color, p.title)
        Spacer(Modifier.width(14.dp))
        Text(p.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(p.openCount.toString(), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
    }
}
