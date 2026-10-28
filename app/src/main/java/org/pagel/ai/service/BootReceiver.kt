package org.pagel.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Brings PAGEL back after a reboot or an app update, without Boss having to open it.
 *
 * ### Android 15 does permit this — our own earlier comment said otherwise and was wrong
 *
 * The deleted first version of this file claimed "Android 15 forbids starting an FGS from boot".
 * It does not. `Behavior changes: apps targeting Android 15` blocks exactly six foreground-service
 * types from a `BOOT_COMPLETED` receiver — `dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
 * `mediaProjection`, `microphone`. [AnchorService] is `specialUse|location`, neither of which is on
 * that list, and receiving `ACTION_BOOT_COMPLETED` is itself a documented exemption from the general
 * "no starting a foreground service from the background" rule.
 *
 * The real constraint is a *different* one, and it lives in [AnchorService.foregroundServiceType]:
 * a background start may only claim the `location` type when **background** location is held. See
 * that function.
 *
 * ### Three actions, not one
 * - `BOOT_COMPLETED` — the ordinary reboot.
 * - `LOCKED_BOOT_COMPLETED` — direct-boot, fires earlier, before the user has unlocked. Harmless to
 *   handle: the service only needs the encrypted-storage identity once it actually connects.
 * - `MY_PACKAGE_REPLACED` — an app update, which stops the old process. Without this, every install
 *   Boss does would leave PAGEL dead until he opened it, which is exactly what happened all through
 *   2026-08-10's testing.
 *
 * **What this cannot fix:** Android 15 will not deliver `BOOT_COMPLETED` at all to an app the user
 * force-stopped, and Samsung's "sleeping apps" bucket suppresses it independently. Both are
 * Settings-only and belong to the setup checklist, not to code.
 */
class BootReceiver : BroadcastReceiver() {
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface Parts {
        fun reminders(): org.pagel.ai.data.habits.ReminderPlanner
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // Alarms do not survive a reboot or an update; a new exact-alarm grant makes them exact (Phase 3 reminders).
        runCatching { dagger.hilt.android.EntryPointAccessors.fromApplication(ctx.applicationContext, Parts::class.java).reminders().replan() }
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                // A throw here would be an app-not-responding style failure inside a system
                // broadcast; a failed restart must degrade to "PAGEL starts when opened" instead.
                runCatching {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, AnchorService::class.java))
                }
            }
        }
    }
}
