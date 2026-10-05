package org.pagel.ai.ui.senses

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.BuildConfig
import org.pagel.ai.update.UpdateState
import org.pagel.ai.update.Updater

/** Shows the installed version and lets Boss check or apply an update by hand; the background worker does it anyway. */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { Updater(ctx.applicationContext) }
    var status by remember { mutableStateOf("not checked yet") }
    var state by remember { mutableStateOf<UpdateState?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("UPDATES", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Installed ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · $status",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    status = "checking…"
                    scope.launch {
                        val s = withContext(Dispatchers.IO) { updater.check() }
                        state = s
                        status = when (s) {
                            is UpdateState.Available -> "update ${s.manifest.versionName} is ready"
                            UpdateState.UpToDate -> "up to date"
                            is UpdateState.Failed -> "check failed: ${s.reason}"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("CHECK", style = MaterialTheme.typography.labelLarge) }

            val available = state as? UpdateState.Available
            if (available != null) {
                Button(
                    onClick = {
                        if (!updater.canInstall()) {
                            status = "allow installs from PAGEL, then tap again"
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            status = "downloading…"
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { updater.downloadAndInstall(available.manifest) }
                                status = err ?: "handed to Android, confirm if it asks"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text("INSTALL", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
