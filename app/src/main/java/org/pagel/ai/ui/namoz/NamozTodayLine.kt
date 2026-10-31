package org.pagel.ai.ui.namoz

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.pagel.ai.data.db.NamozDao
import org.pagel.ai.data.db.NamozSettingsEntity
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.isNamoz
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import javax.inject.Inject

/**
 * Today's quiet Namoz line: "Asr · 15:42 · in 1 h 03 m · 2/5". Only once he has started tracking and while "Show on
 * Today" is on (the page's settings). Plain words, his own ticks, no AI reads it.
 */
@HiltViewModel
class NamozLineViewModel @Inject constructor(repo: HabitsRepository, dao: NamozDao) : ViewModel() {
    val line: StateFlow<NamozLine?> = combine(repo.habits, repo.ticks(), dao.settings(), namozMinutes()) { habits, ticks, s, now ->
        todayLine(now, habits.filter(::isNamoz), ticks, s ?: NamozSettingsEntity())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        /** Nothing until he has started tracking (1 November, five prayers) AND turned "Show on Today" on. */
        fun todayLine(now: java.time.LocalDateTime, prayers: List<org.pagel.ai.data.db.TaskEntity>, ticks: List<org.pagel.ai.data.db.HabitTickEntity>, settings: NamozSettingsEntity): NamozLine? {
            if (!settings.onToday || prayers.size < 5) return null
            val ui = NamozUi.of(now, prayers, ticks, emptyList(), emptyList(), settings, emptySet())
            if (!ui.started) return null
            return NamozLine(ui.next.prayer.emoji, Namoz.notice(ui.next) + " · " + ui.countdown + " · ${ui.doneToday.size}/5")
        }
    }
}

data class NamozLine(val emoji: String, val text: String)

@Composable
fun NamozTodayLine(line: NamozLine, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.textEdge, end = Space.gutter, top = Space.headerTop).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(org.pagel.ai.ui.glass.HabitIcons.vector(org.pagel.ai.ui.glass.HabitIcons.forEmoji(line.emoji)), null, tint = Glass.Ink.copy(alpha = 0.78f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.sm))
        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
