package org.pagel.ai.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import org.pagel.ai.service.WakeService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.pagel.ai.ui.hud.JarvisCore
import org.pagel.ai.ui.theme.MonoFamily
import org.pagel.ai.voice.Transcript
import org.pagel.ai.voice.VoiceState

@Composable
fun VoiceScreen(onBack: () -> Unit, autostart: Boolean = false, vm: VoiceViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val amplitude by vm.amplitude.collectAsState()
    val muted by vm.muted.collectAsState()
    val armed by vm.armed.collectAsState(initial = false)
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var wantStart by remember { mutableStateOf(false) }
    var wantArm by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && wantStart) vm.start(ctx)
        if (granted && wantArm) { vm.setArmed(true); WakeService.start(ctx) }
        wantStart = false
        wantArm = false
    }
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    fun talk() {
        if (hasMic()) vm.start(ctx) else { wantStart = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    fun setHandsFree(on: Boolean) {
        if (!on) { vm.setArmed(false); WakeService.stop(ctx); return }
        if (hasMic()) { vm.setArmed(true); WakeService.start(ctx) }
        else { wantArm = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    // A "Hey PAGEL" wake handed us here → open the mic immediately.
    LaunchedEffect(autostart) { if (autostart) talk() }

    val active = state != VoiceState.IDLE && state != VoiceState.ERROR

    // A short haptic pulse each time PAGEL takes/yields the floor — physical presence.
    LaunchedEffect(state) {
        when (state) {
            VoiceState.LISTENING, VoiceState.SPEAKING -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            else -> Unit
        }
    }

    // The core breathes with the conversation; while PAGEL speaks it pulses to his live voice level.
    val targetEnergy = when (state) {
        VoiceState.IDLE -> 0.65f
        VoiceState.CONNECTING -> 0.85f
        VoiceState.LISTENING -> 1.0f
        VoiceState.THINKING -> 1.15f
        VoiceState.SPEAKING -> 1.35f
        VoiceState.ERROR -> 0.5f
    }
    val targetSpin = when (state) {
        VoiceState.IDLE -> 0.5f
        VoiceState.CONNECTING -> 2.2f
        VoiceState.LISTENING -> 0.85f
        VoiceState.THINKING -> 2.4f
        VoiceState.SPEAKING -> 1.15f
        VoiceState.ERROR -> 0.3f
    }
    val baseEnergy by animateFloatAsState(targetEnergy, tween(700), label = "energy")
    val spin by animateFloatAsState(targetSpin, tween(700), label = "spin")
    val liveAmp by animateFloatAsState(if (state == VoiceState.SPEAKING) amplitude else 0f, tween(120), label = "amp")
    val energy = baseEnergy + liveAmp * 0.9f

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x182FE4D6), Color(0xFF04070A)),
                        radius = 1500f,
                    ),
                ),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                // top bar
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.stop(); onBack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("PAGEL · VOICE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "HEY PAGEL",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = armed,
                        onCheckedChange = { setHandsFree(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        ),
                    )
                }

                // hero core + reactive halo + caption
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(18.dp))
                        Box(contentAlignment = Alignment.Center) {
                            // halo that swells with PAGEL's voice
                            Box(
                                Modifier
                                    .size(300.dp)
                                    .scale(1f + liveAmp * 0.18f)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0x222FE4D6).copy(alpha = 0.10f + liveAmp * 0.22f), Color.Transparent),
                                        ),
                                        CircleShape,
                                    ),
                            )
                            JarvisCore(Modifier.size(300.dp), energy = energy, spin = spin)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error?.takeIf { state == VoiceState.ERROR } ?: captionFor(state),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state == VoiceState.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // live transcript (auto-scrolls to newest)
                val listState = rememberLazyListState()
                LaunchedEffect(transcript.size) {
                    if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(transcript) { line -> TranscriptBubble(line) }
                }

                // controls: mute (when live) + talk/end + retry (on error)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        RoundControl(
                            icon = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (muted) "UNMUTE" else "MUTE",
                            filled = false,
                            onClick = { vm.toggleMute() },
                            size = 64,
                        )
                        Spacer(Modifier.width(28.dp))
                    }
                    RoundControl(
                        icon = if (active) Icons.Rounded.Stop else if (state == VoiceState.ERROR) Icons.Rounded.Refresh else Icons.Rounded.Mic,
                        label = if (active) "END" else if (state == VoiceState.ERROR) "RETRY" else "TALK",
                        filled = !active,
                        onClick = { if (active) vm.stop() else talk() },
                        size = 92,
                    )
                }
            }
        }
    }
}

private fun captionFor(state: VoiceState): String = when (state) {
    VoiceState.IDLE -> "Tap to talk"
    VoiceState.CONNECTING -> "Connecting…"
    VoiceState.LISTENING -> "Listening…"
    VoiceState.THINKING -> "Thinking…"
    VoiceState.SPEAKING -> "PAGEL speaking"
    VoiceState.ERROR -> "Something went wrong"
}

@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    size: Int,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .then(
                    if (filled) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size((size * 0.4f).dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TranscriptBubble(line: Transcript) {
    val label = if (line.fromUser) "YOU" else "PAGEL"
    val labelColor = if (line.fromUser) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily), color = labelColor)
        Spacer(Modifier.height(6.dp))
        Text(line.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}
