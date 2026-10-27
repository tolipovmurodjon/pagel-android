package org.pagel.ai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.pagel.ai.BuildConfig
import org.pagel.ai.ui.gateway.GatewayViewModel
import org.pagel.ai.ui.senses.SensesCard
import org.pagel.ai.ui.senses.UpdateCard

/**
 * The first screen: what PAGEL can see (permissions, live), what version this is, and a door to the gateway
 * settings. A stop-gap until the real PAGEL dashboard; it replaces opening straight onto the gateway form,
 * whose back arrow had nowhere to go.
 */
@Composable
fun HomeScreen(onOpenGateway: () -> Unit, vm: GatewayViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("PAGEL", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "v${BuildConfig.VERSION_NAME} · operator ${ui.operatorState} · node ${ui.nodeState}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SensesCard()
            UpdateCard()
            Button(
                onClick = onOpenGateway,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("GATEWAY SETTINGS", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
