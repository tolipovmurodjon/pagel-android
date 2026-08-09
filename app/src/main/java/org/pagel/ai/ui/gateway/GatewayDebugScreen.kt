package org.pagel.ai.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.pagel.ai.net.ws.ConnState
import org.pagel.ai.ui.hud.JarvisCore
import org.pagel.ai.ui.theme.MonoFamily

/**
 * Task 8's debug screen — NOT the final Connect UI (Phase B/H builds that). Just enough to drive
 * the machine built in Tasks 1-6: the gateway URL, the gateway auth token (a second, independent
 * gate on top of device pairing — see [org.pagel.ai.net.ws.ConnectAuth]'s doc), Connect/Disconnect,
 * and operator+node state shown *separately* with each half's own last error, because "operator
 * Connected while node still awaits approval" is a normal state a Boolean would hide (see
 * [org.pagel.ai.net.GatewayManager]'s doc). No QR scanning, no mDNS discovery, no gateway registry
 * — out of scope for this task.
 */
@Composable
fun GatewayDebugScreen(onBack: () -> Unit, vm: GatewayViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    Surface(Modifier.fillMaxSize().scanlines(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(4.dp))
                JarvisCore(Modifier.size(28.dp), particleCount = 90)
                Spacer(Modifier.width(8.dp))
                Text("GATEWAY", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Debug wiring for the OpenClaw gateway connection. Two independent sockets share " +
                    "one device identity: operator (chat/dashboard/settings) and node " +
                    "(sensors/canvas/talk). Each pairs and can fail independently.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = ui.url,
                onValueChange = vm::setUrl,
                label = { Text("Gateway URL") },
                placeholder = { Text("ws://100.64.0.1:18789") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = ui.token,
                onValueChange = vm::setToken,
                label = { Text("Gateway auth token") },
                placeholder = { Text("shared secret, not the device pairing token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Only needed if the gateway runs gateway.auth.mode = \"token\" — read it on the " +
                    "server from gateway.auth.token in ~/.openclaw/openclaw.json. Both fields take " +
                    "effect on the next CONNECT tap, no app restart needed.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = vm::connect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text("CONNECT", style = MaterialTheme.typography.labelLarge) }
                Button(
                    onClick = vm::disconnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("DISCONNECT", style = MaterialTheme.typography.labelLarge) }
            }

            Spacer(Modifier.height(28.dp))
            ConnRow("OPERATOR", "chat · dashboard · settings", ui.operatorState, ui.operatorError, ui.operatorSignedPayload)
            Spacer(Modifier.height(14.dp))
            ConnRow("NODE", "sensors · canvas · talk", ui.nodeState, ui.nodeError, ui.nodeSignedPayload)
        }
    }
}

@Composable
private fun ConnRow(
    label: String,
    subtitle: String,
    state: ConnState,
    error: String?,
    signedPayload: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudCorners(MaterialTheme.colorScheme.outlineVariant)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StateChip(state)
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isMissingGatewayTokenError(error)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Enter the gateway auth token above and tap CONNECT again.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // "device signature invalid" means the gateway rebuilt this exact string from our frame
            // and got something different. The payload never crosses the wire, so without showing it
            // here there is no way to see WHICH field diverged — the error only ever names the
            // signature. Token slot is redacted upstream.
            if (signedPayload != null && error.contains("signature", ignoreCase = true)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "SIGNED PAYLOAD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        signedPayload,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The raw gateway error ("INVALID_REQUEST: unauthorized: gateway token missing (provide gateway
 *  auth token)") is accurate but doesn't tell Boss what to do about it — this spots that specific
 *  case so [ConnRow] can point at the token field instead of leaving him to guess. */
private fun isMissingGatewayTokenError(error: String): Boolean =
    error.contains("gateway token", ignoreCase = true)

@Composable
private fun StateChip(state: ConnState) {
    val color = when (state) {
        ConnState.Connected -> MaterialTheme.colorScheme.primary
        ConnState.AwaitingApproval -> MaterialTheme.colorScheme.secondary
        ConnState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnState.Failed -> MaterialTheme.colorScheme.error
    }
    val label = when (state) {
        ConnState.Connected -> "CONNECTED"
        ConnState.AwaitingApproval -> "AWAITING APPROVAL"
        ConnState.Connecting -> "CONNECTING"
        ConnState.Disconnected -> "DISCONNECTED"
        ConnState.Failed -> "FAILED"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily), color = color)
    }
}

/** Verbatim copy of `DigitalWellbeingScreen`'s private `Modifier.scanlines()` — that one is file-
 *  private, and this is only the second use site, so duplicating a ~10-line modifier beats adding
 *  a shared abstraction for it (see the project's "build it three times" rule). */
private fun Modifier.scanlines(): Modifier = drawWithContent {
    drawContent()
    val gap = 3.5.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(Color(0x08FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        y += gap
    }
}

/** Verbatim copy of `DigitalWellbeingScreen`'s private `Modifier.hudCorners()` — see [scanlines]'s
 *  doc for why this is a duplicate rather than a shared util. */
private fun Modifier.hudCorners(color: Color, len: Dp = 9.dp, stroke: Dp = 1.5.dp): Modifier =
    drawBehind {
        val l = len.toPx()
        val w = stroke.toPx()
        val cw = size.width
        val ch = size.height
        drawLine(color, Offset(0f, 0f), Offset(l, 0f), w)
        drawLine(color, Offset(0f, 0f), Offset(0f, l), w)
        drawLine(color, Offset(cw, 0f), Offset(cw - l, 0f), w)
        drawLine(color, Offset(cw, 0f), Offset(cw, l), w)
        drawLine(color, Offset(0f, ch), Offset(l, ch), w)
        drawLine(color, Offset(0f, ch), Offset(0f, ch - l), w)
        drawLine(color, Offset(cw, ch), Offset(cw - l, ch), w)
        drawLine(color, Offset(cw, ch), Offset(cw, ch - l), w)
    }
