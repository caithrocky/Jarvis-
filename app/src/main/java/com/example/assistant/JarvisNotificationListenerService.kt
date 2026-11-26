package com.example.assistant

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val isMissedCall: Boolean,
    val isMessage: Boolean,
    val timestampMs: Long
)

object NotificationVaultBridge {
    private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
    val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    fun setConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun record(item: CapturedNotification) {
        val current = _notifications.value.filterNot { it.key == item.key }.toMutableList()
        current.add(0, item)
        _notifications.value = current.take(40)
    }

    fun removeByKey(key: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
    }

    fun clearAll() {
        _notifications.value = emptyList()
    }

    fun isAccessGranted(context: Context): Boolean {
        return runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)
    }

    fun openAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildSummary(queryLower: String = ""): String {
        val list = _notifications.value
        if (list.isEmpty()) {
            return "You have no unread notifications right now, Sir."
        }
        if (queryLower.contains("missed call")) {
            val missed = list.filter { it.isMissedCall }
            return if (missed.isEmpty()) {
                "You have no missed calls logged, Sir."
            } else {
                val callers = missed.take(4).joinToString(", ") { "${it.title} (${it.text})" }
                "You have ${missed.size} missed call(s), Sir: $callers."
            }
        }
        if (queryLower.contains("text") || queryLower.contains("message") || queryLower.contains("whatsapp")) {
            val msgs = list.filter { it.isMessage }
            return if (msgs.isEmpty()) {
                "No unread text messages in your notification tray right now, Sir."
            } else {
                val summary = msgs.take(4).joinToString(". ") {
                    "${it.appLabel} from ${it.title}: ${it.text.take(80)}"
                }
                "Recent messages, Sir: $summary. Tell me if you'd like me to draft a reply."
            }
        }
        val top = list.take(5).joinToString(". ") {
            "${it.appLabel} — ${it.title}: ${it.text.take(70)}"
        }
        return "You have ${list.size} notification(s), Sir. $top"
    }
}

class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationVaultBridge.setConnected(true)
        runCatching {
            activeNotifications?.forEach { sbn -> captureSbn(sbn) }
        }
    }

    override fun onListenerDisconnected() {
        NotificationVaultBridge.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        captureSbn(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) {
            NotificationVaultBridge.removeByKey(sbn.key)
        }
    }

    private fun captureSbn(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        val sig = "$pkg $title $text".lowercase(Locale.US)
        val isMissedCall = notification.category == Notification.CATEGORY_MISSED_CALL ||
            sig.contains("missed call")
        val isMessage = notification.category == Notification.CATEGORY_MESSAGE ||
            pkg.contains("whatsapp") ||
            pkg.contains("messaging") ||
            pkg.contains("telegram") ||
            pkg.contains("instagram") ||
            pkg.contains("snapchat")

        NotificationVaultBridge.record(
            CapturedNotification(
                key = sbn.key,
                packageName = pkg,
                appLabel = appLabel,
                title = title.ifBlank { appLabel },
                text = text,
                isMissedCall = isMissedCall,
                isMessage = isMessage,
                timestampMs = sbn.postTime
            )
        )
    }
}
