package org.pagel.ai.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.session.message
import org.pagel.ai.data.sync.LiveStatus
import javax.inject.Inject

data class ProjectsUiState(val loading: Boolean = true, val projects: List<ProjectEntity> = emptyList(), val status: String = "")

/** The project list, read from Room only; the sync engine fills Room from pagel.uz. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(session: Session) : ViewModel() {
    val ui: StateFlow<ProjectsUiState> = combine(session.projects(), session.live) { projects, live ->
        ProjectsUiState(loading = projects.isEmpty() && live !is LiveStatus.Offline, projects = projects, status = live.line())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())
}

internal fun LiveStatus.line(): String = when (this) {
    LiveStatus.Idle -> ""
    LiveStatus.Syncing -> "Syncing…"
    is LiveStatus.Live -> "Live"
    is LiveStatus.Offline -> error.message()
}
