package de.posista.ailimits

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import java.time.Instant

class ProviderNotifications : NotificationListenerService() {
    override fun onNotificationPosted(item: StatusBarNotification) {
        val provider = Provider.fromPackage(item.packageName) ?: return
        if (item.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = item.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.take(512)?.toString()
            ?.takeUnless { it.equals(provider.title, ignoreCase = true) }
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.take(ResetParser.MAX_INPUT)
        val text = listOfNotNull(title, body).distinct().joinToString(" ")
        val postedAt = Instant.ofEpochMilli(item.postTime)
        val candidate = ResetParser.readNotification(text, provider, postedAt, Instant.now()) ?: return
        val app = application as LimitApplication
        app.scope.launch { app.save(listOf(candidate), Source.NOTIFICATION, postedAt) }
    }
}
