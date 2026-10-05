package org.pagel.ai.ui.senses

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** One line of the permission list. [granted] is read live; [grant] starts the right flow (dialog or Settings). */
private class Perm(
    val label: String,
    val note: String,
    val granted: (Context) -> Boolean,
    val grant: (Context, (Array<String>) -> Unit) -> Unit,
)

private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun settings(ctx: Context, action: String, withPackage: Boolean) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
    ctx.startActivity(i)
}

private fun runtime(label: String, note: String, vararg perms: String) = Perm(
    label, note,
    granted = { c -> perms.all { has(c, it) } },
    grant = { _, ask -> ask(arrayOf(*perms)) },
)

private val PERMS: List<Perm> = listOf(
    Perm(
        "Location, all the time", "where you are, trips, places",
        granted = { c -> has(c, Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < 29 || has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) },
        grant = { c, ask ->
            if (!has(c, Manifest.permission.ACCESS_FINE_LOCATION)) {
                ask(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else {
                // Android 11+ gives "Allow all the time" only from Settings, never from a dialog.
                settings(c, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            }
        },
    ),
    runtime("Steps and movement", "walking, running, scooter vs car", Manifest.permission.ACTIVITY_RECOGNITION),
    Perm("Screen time", "top apps, the 2 hour rule",
        granted = { c -> usageAllowed(c) },
        grant = { c, _ -> settings(c, Settings.ACTION_USAGE_ACCESS_SETTINGS, false) }),
    Perm("Notification access", "money and people filter",
        granted = { c -> NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName) },
        grant = { c, _ -> settings(c, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false) }),
    Perm("Show notifications", "PAGEL can message you",
        granted = { c -> NotificationManagerCompat.from(c).areNotificationsEnabled() },
        grant = { c, ask -> if (Build.VERSION.SDK_INT >= 33) ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else settings(c, Settings.ACTION_APP_NOTIFICATION_SETTINGS, false) }),
    Perm("Battery: unrestricted", "keeps tracking alive",
        granted = { c -> c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true },
        grant = { c, _ -> settings(c, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) }),
    Perm("Install updates", "lets PAGEL update itself",
        granted = { c -> c.packageManager.canRequestPackageInstalls() },
        grant = { c, _ -> settings(c, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true) }),
    runtime("Calendar", "events, leave-now (not used yet)", Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    runtime("Contacts", "people memory (not used yet)", Manifest.permission.READ_CONTACTS),
    runtime("Camera", "snap on request (not used yet)", Manifest.permission.CAMERA),
    runtime("Microphone", "voice (not used yet)", Manifest.permission.RECORD_AUDIO),
    Perm("Photos", "latest photos (not used yet)",
        granted = { c -> has(c, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) },
        grant = { _, ask -> ask(arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)) }),
    runtime("SMS", "bank texts, money view (not used yet)", Manifest.permission.READ_SMS),
    runtime("Call log", "who you talked to (not used yet)", Manifest.permission.READ_CALL_LOG),
)

private fun usageAllowed(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED
}

/**
 * The permission list: every grant PAGEL can use, ON/OFF read live, a GRANT button that starts the right flow
 * (a dialog where Android allows one, the right Settings page where it does not). Re-reads every time the app
 * returns to the foreground, so coming back from Settings turns a row green without a restart.
 */
@Composable
fun SensesCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val on = PERMS.count { tick >= 0 && it.granted(ctx) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PERMISSIONS  $on / ${PERMS.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        for (p in PERMS) {
            val granted = tick >= 0 && p.granted(ctx)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        (if (granted) "ON" else "OFF") + "  ·  " + p.note,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!granted) {
                    Button(
                        onClick = { p.grant(ctx) { perms -> ask.launch(perms) } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Text("GRANT", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
