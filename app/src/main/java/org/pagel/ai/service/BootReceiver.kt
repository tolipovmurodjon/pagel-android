package org.pagel.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arm the periodic harvest after reboot. (Android 15 forbids starting an FGS from boot, so the
 *  anchor service restarts when the app is next opened — WorkManager keeps harvesting meanwhile.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkScheduler.ensure(ctx)
        }
    }
}
