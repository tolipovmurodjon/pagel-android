package org.pagel.ai.data.spend

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads bank notifications and extracts the money amount into [SpendStore]. Only bank packages are
 * inspected; everything else is ignored. Nothing is sent anywhere from here — the amount is stored
 * locally and rides the normal privacy-filtered daily sync as a candidate spend.
 */
@AndroidEntryPoint
class SpendNotificationListener : NotificationListenerService() {

    @Inject lateinit var store: SpendStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!SpendParser.isBank(pkg)) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val event = SpendParser.parse("$title $text") ?: return
        scope.launch { store.add(event) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
