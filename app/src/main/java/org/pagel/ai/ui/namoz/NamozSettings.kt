package org.pagel.ai.ui.namoz

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.db.NamozSettingsEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.habits.Switch

/** The page's settings: every reminder off until he turns it on, Tashkent and Uzbekistan's method by default. */
@Composable
fun NamozSettingsSheet(ui: NamozUi, onSave: (NamozSettingsEntity) -> Unit, onRemind: (String, Boolean) -> Unit, onPlace: () -> Unit, onDismiss: () -> Unit) {
    val s = ui.settings
    val offsets = Namoz.setupOf(s).offsets
    GlassSheet(onDismiss) {
        SheetTitle("Namoz settings", null)
        Group("Reminders · \"Asr · 15:42\"") {
            for (w in ui.windows) {
                val id = ui.prayers[w.prayer]?.id ?: continue
                SwitchRow(w.prayer.uz, w.prayer.emoji, w.from.toString(), id in ui.reminders) { onRemind(id, it) }
            }
        }
        Group("Remind me") {
            for (m in Namoz.BEFORE) {
                CheckRow(if (m == 0) "At the time" else "$m minutes before", s.remindBefore == m) { onSave(s.copy(remindBefore = m)) }
            }
        }
        Group("Minutes added (to match my mosque)") {
            Namoz.Prayer.entries.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.uz, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                    fun set(d: Int) = onSave(s.copy(offsets = offsets.toMutableList().also { it[i] = (it[i] + d).coerceIn(-30, 30) }.joinToString(",")))
                    GlassIconButton(TabIcons.Minus, "${p.uz} one minute earlier", size = Space.touch, plain = true) { set(-1) }
                    Text("%+d".format(offsets[i]), style = Glass.NumberText, color = Glass.Ink, modifier = Modifier.width(32.dp))
                    GlassIconButton(TabIcons.Plus, "${p.uz} one minute later", size = Space.touch, plain = true) { set(1) }
                }
            }
        }
        Group("How the times are computed") {
            for ((key, name) in Namoz.METHODS) CheckRow(name.substringBefore(" ("), s.method == key) { onSave(s.copy(method = key)) }
        }
        Group("Asr") {
            CheckRow("Hanafi", s.hanafi) { onSave(s.copy(hanafi = true)) }
            CheckRow("Shafi'i", !s.hanafi) { onSave(s.copy(hanafi = false)) }
        }
        Group(null) {
            SwitchRow("Use my location", "target", if (s.autoLocation && s.lat != 0.0) "%.2f, %.2f".format(s.lat, s.lon) else "Tashkent", s.autoLocation) { on ->
                if (on) onPlace() else onSave(s.copy(autoLocation = false))
            }
            SwitchRow("Show on Today", "sunrise", "Next prayer", s.onToday) { onSave(s.copy(onToday = it)) }
            SwitchRow("Qaza counter", "memo", "My own number", s.qazaOn) { onSave(s.copy(qazaOn = it)) }
        }
        Spacer(Modifier.height(SheetBottom))
    }
}

@Composable
private fun Group(title: String?, content: @Composable () -> Unit) {
    if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = Glass.Muted, modifier = Modifier.padding(start = Space.xl, top = Space.md, bottom = Space.xs))
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(top = if (title == null) Space.md else 0.dp)) { Column { content() } }
}

/** One choice of a short list: the whole row taps, a mint check marks the chosen one (never cut off, unlike the old pills). */
@Composable
private fun CheckRow(label: String, on: Boolean, onPick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onPick)
            .semantics(mergeDescendants = true) { selected = on }
            .padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
        Box(Modifier.size(20.dp)) { if (on) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun SwitchRow(label: String, emoji: String, meta: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Switch) { onChange(!on) }
            .semantics(mergeDescendants = true) { contentDescription = "$label, $meta"; stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(Emoji.res(emoji)), null, Modifier.size(22.dp))
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
        Text(meta, style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(end = Space.md))
        Switch(on)
    }
}
