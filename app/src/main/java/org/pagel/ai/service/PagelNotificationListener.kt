package org.pagel.ai.service

import android.service.notification.NotificationListenerService

/**
 * Exists so Android lists PAGEL under "Notification access". It keeps no history of its own: while Boss has
 * granted access and the system has bound it, [instance] lets `notifications.list` read the currently active
 * notifications. Nothing is stored or forwarded from here.
 */
class PagelNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        var instance: PagelNotificationListener? = null
    }
}
