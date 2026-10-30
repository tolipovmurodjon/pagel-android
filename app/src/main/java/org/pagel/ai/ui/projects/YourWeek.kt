package org.pagel.ai.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.api.KpiWeekReply
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.projects.ProjectRepository
import org.pagel.ai.data.projects.WeekCounts
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import javax.inject.Inject

/** Real use, measured (Boss 2026-10-29: "real using is the main KPI"): pagel.uz's counts of the last 7 days. */
@HiltViewModel
class YourWeekViewModel @Inject constructor(private val api: PagelApi, private val accounts: AccountStore, projects: ProjectRepository) : ViewModel() {
    val local: StateFlow<WeekCounts?> = projects.week().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _week = MutableStateFlow<KpiWeekReply?>(null)
    val week: StateFlow<KpiWeekReply?> = _week

    fun load() {
        viewModelScope.launch {
            val a = (accounts.state.value as? AccountState.Paired)?.account ?: return@launch
            (api.kpiWeek(a.serverUrl, a.token) as? Outcome.Ok)?.let { _week.value = it.value }
        }
    }
}

@Composable
fun YourWeekRoute(vm: YourWeekViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    val w by vm.week.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    YourWeekCard(w, local)
}

/** Three numbers counted on the phone, pagel.uz's line, one bar per day (ticks); no words from tasks, ever. Offline shows dashes. */
@Composable
fun YourWeekCard(w: KpiWeekReply?, local: WeekCounts? = null) {
    Pane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Text("Your week", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            Spacer(Modifier.height(Space.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(local?.done?.toString() ?: "–", "tasks done")
                Stat(local?.projectsMoved?.toString() ?: "–", "projects moved")
                Stat(local?.habitsKept?.toString() ?: "–", "habits kept")
            }
            w?.let { serverLine(it) }?.let { line ->
                Spacer(Modifier.height(Space.xs))
                Text(line, style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
            Spacer(Modifier.height(Space.md))
            val days = w?.days.orEmpty()
            val max = days.maxOfOrNull { it.done.values.sum() }?.coerceAtLeast(1) ?: 1
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.Bottom) {
                (if (days.isEmpty()) List(7) { null } else days).forEach { d ->
                    val n = d?.done?.values?.sum() ?: 0
                    val phone = d?.done?.get("phone") ?: 0
                    Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "${d?.day ?: ""}: $n done, $phone on the phone" }, contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight((n.toFloat() / max).coerceIn(0.06f, 1f)).clip(RoundedCornerShape(4.dp))
                            .background(if (n == 0) Glass.Ink.copy(alpha = 0.08f) else Glass.Primary))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$value $label" }) {
        Text(value, style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize, lineHeight = MaterialTheme.typography.titleLarge.lineHeight), color = Glass.Ink)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
    }
}

/** pagel.uz's counts in one quiet line: projects closed, days opened, open to first tick (m:ss). */
internal fun serverLine(w: KpiWeekReply): String {
    val parts = mutableListOf("${w.projectsFinished} closed", "opened ${w.daysOpened}/7 days")
    w.days.lastOrNull()?.firstTickMs?.let { ms -> val s = (ms / 1000).coerceAtMost(5999); parts += "first tick ${s / 60}:${"%02d".format(s % 60)}" }
    return parts.joinToString(" · ")
}
