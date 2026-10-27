package org.pagel.ai.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane

@Composable
fun PairRoute(vm: PairViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    PairScreen(ui, vm::onCode, vm::onServer, vm::connect)
}

/** "Connect this phone": no password on the phone, one code from pagel.uz → Phones (NATIVE-PLAN Phase 1). */
@Composable
fun PairScreen(ui: PairUiState, onCode: (String) -> Unit, onServer: (String) -> Unit, onConnect: () -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("PAGEL", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink)
            Spacer(Modifier.height(6.dp))
            Text("Connect this phone to pagel.uz.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
            Spacer(Modifier.height(24.dp))
            Pane {
                Column(Modifier.padding(20.dp)) {
                    Text("On pagel.uz open More → Phones and tap Connect phone. Type the 6 digits here.", style = MaterialTheme.typography.bodyMedium, color = Glass.Ink)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = ui.code,
                        onValueChange = onCode,
                        label = { Text("Code") },
                        singleLine = true,
                        enabled = !ui.busy,
                        textStyle = TextStyle(fontFamily = Glass.Numbers, fontSize = 28.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConnect() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ui.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Glass.Risk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onConnect,
                        enabled = ui.canConnect,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Glass.Primary),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (ui.busy) CircularProgressIndicator(Modifier.height(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Connect", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (advanced) "Hide server" else "Server", color = Glass.Muted)
            }
            if (advanced) {
                OutlinedTextField(ui.server, onServer, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        }
    }
}
