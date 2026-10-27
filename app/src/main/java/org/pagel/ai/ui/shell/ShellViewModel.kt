package org.pagel.ai.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.session.Session
import javax.inject.Inject

/** Which root the app shows: nothing yet (reading the account off Main), pairing, or the tabs. */
@HiltViewModel
class ShellViewModel @Inject constructor(private val session: Session) : ViewModel() {
    val account: StateFlow<AccountState?> = session.account

    fun disconnect() {
        viewModelScope.launch { session.disconnect() }
    }
}
