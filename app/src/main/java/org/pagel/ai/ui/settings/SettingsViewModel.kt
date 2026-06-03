package org.pagel.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pagel.ai.data.sync.BrainSync
import org.pagel.ai.data.sync.SyncSettings
import org.pagel.ai.data.usage.UsageRepository
import java.time.LocalDate
import javax.inject.Inject

data class SettingsUi(
    val url: String = "",
    val bearer: String = "",
    val status: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SyncSettings,
    private val repo: UsageRepository,
    private val sync: BrainSync,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val (u, b) = settings.current()
            _ui.value = _ui.value.copy(url = u, bearer = b)
        }
    }

    fun setUrl(v: String) { _ui.value = _ui.value.copy(url = v) }
    fun setBearer(v: String) { _ui.value = _ui.value.copy(bearer = v) }

    fun save() = viewModelScope.launch {
        settings.save(_ui.value.url, _ui.value.bearer)
        _ui.value = _ui.value.copy(status = "Saved.")
    }

    fun syncNow() = viewModelScope.launch {
        settings.save(_ui.value.url, _ui.value.bearer)
        _ui.value = _ui.value.copy(status = "Syncing…")
        val snap = runCatching { repo.today() }.getOrNull()
        if (snap == null) {
            _ui.value = _ui.value.copy(status = "Grant Usage Access first.")
            return@launch
        }
        val result = sync.push(snap, LocalDate.now().toString())
        _ui.value = _ui.value.copy(
            status = if (result.isSuccess) "Synced to brain ✓"
            else "Failed: ${result.exceptionOrNull()?.message}",
        )
    }
}
