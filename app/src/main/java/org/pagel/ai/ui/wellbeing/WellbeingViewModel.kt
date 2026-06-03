package org.pagel.ai.ui.wellbeing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.room.AwarenessDao
import org.pagel.ai.data.usage.UsagePermission
import org.pagel.ai.data.usage.UsageRepository
import org.pagel.ai.data.usage.UsageStatsHarvester
import org.pagel.ai.domain.UsageSnapshot
import javax.inject.Inject

data class WellbeingUi(
    val hasAccess: Boolean = true,
    val loading: Boolean = false,
    val snapshot: UsageSnapshot? = null,
)

@HiltViewModel
class WellbeingViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: UsageRepository,
    private val harvester: UsageStatsHarvester,
    dao: AwarenessDao,
) : ViewModel() {

    private val _state = MutableStateFlow(WellbeingUi(loading = true))
    val state: StateFlow<WellbeingUi> = _state.asStateFlow()

    /** Live count of raw events stored on-device — grows as PAGEL harvests + survives app close. */
    val storedEvents: StateFlow<Int> =
        dao.observeEventCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Harvest the OS event stream into Room, then refresh the live view. */
    fun harvestAndRefresh() {
        if (!UsagePermission.hasAccess(ctx)) {
            _state.value = WellbeingUi(hasAccess = false, loading = false)
            return
        }
        _state.value = _state.value.copy(hasAccess = true, loading = true)
        viewModelScope.launch {
            runCatching { harvester.harvest() }
            val snap = runCatching { repo.today() }.getOrNull()
            _state.value = WellbeingUi(hasAccess = true, loading = false, snapshot = snap)
        }
    }
}
