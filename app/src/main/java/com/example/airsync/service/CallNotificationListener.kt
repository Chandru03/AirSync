package com.example.airsync.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An incoming call announced by any app (WhatsApp, Telegram, Signal, the dialer…). */
data class IncomingCall(
    val key: String,
    val packageName: String,
    val answer: PendingIntent?,
    val decline: PendingIntent?
)

/** Process-wide view of the ringing call notification, filled by [CallNotificationListener]. */
object IncomingCallTracker {
    private val _incoming = MutableStateFlow<IncomingCall?>(null)
    val incoming: StateFlow<IncomingCall?> = _incoming.asStateFlow()

    internal fun posted(call: IncomingCall) { _incoming.value = call }
    internal fun removed(key: String) { if (_incoming.value?.key == key) _incoming.value = null }
    internal fun clear() { _incoming.value = null }

    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(context, CallNotificationListener::class.java).flattenToString()
        return enabled.split(":").any { it == me }
    }
}

/**
 * Watches for *incoming-call* notifications so head gestures can answer or decline calls from
 * VoIP apps. Android's telephony APIs only control SIM calls; WhatsApp & co. expose their
 * Answer / Decline buttons on a `category=call` notification, which is what we press.
 *
 * Only call notifications are inspected; nothing else is read or stored.
 */
class CallNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IncomingCallTracker.clear()
        runCatching { activeNotifications }.getOrNull()?.forEach(::inspect)
    }

    override fun onListenerDisconnected() = IncomingCallTracker.clear()

    override fun onNotificationPosted(sbn: StatusBarNotification) = inspect(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = IncomingCallTracker.removed(sbn.key)

    private fun inspect(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.category != Notification.CATEGORY_CALL) return
        val extras = n.extras
        val actions = n.actions.orEmpty()

        // 1. Notification.CallStyle (Android 12+) carries explicit intents and a call type.
        val callType = extras.getInt(EXTRA_CALL_TYPE, 0)
        var answer = extras.parcelable<PendingIntent>(EXTRA_ANSWER_INTENT)
        var decline = extras.parcelable<PendingIntent>(EXTRA_DECLINE_INTENT)

        // 2. Otherwise match the action buttons by their label.
        if (answer == null) answer = actions.firstOrNull { it.title.matches(ANSWER_WORDS) }?.actionIntent
        if (decline == null) decline = actions.firstOrNull { it.title.matches(DECLINE_WORDS) }?.actionIntent

        // 3. CallStyle layout for an incoming call is always [Decline, Answer].
        if (answer == null && decline == null && actions.size == 2 && callType != CALL_TYPE_ONGOING) {
            decline = actions[0].actionIntent
            answer = actions[1].actionIntent
        }

        // Diagnostics (labels only, never caller details) so new apps can be supported quickly.
        Log.i(TAG, "call notif pkg=${sbn.packageName} type=$callType actions=${actions.map { it.title }} " +
            "answer=${answer != null} decline=${decline != null}")

        val incoming = callType == CALL_TYPE_INCOMING || (callType == 0 && answer != null)
        if (incoming) IncomingCallTracker.posted(IncomingCall(sbn.key, sbn.packageName, answer, decline))
        else IncomingCallTracker.removed(sbn.key) // became an ongoing call
    }

    private fun CharSequence?.matches(words: Regex) = this != null && words.containsMatchIn(toString())

    @Suppress("DEPRECATION")
    private inline fun <reified T> android.os.Bundle.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, T::class.java)
        else getParcelable(key) as? T

    private companion object {
        const val TAG = "CallNotifications"
        // Public Notification extras (API 31), spelled out so they work on any SDK.
        const val EXTRA_CALL_TYPE = "android.callType"
        const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        const val CALL_TYPE_INCOMING = 1
        const val CALL_TYPE_ONGOING = 2
        val ANSWER_WORDS = Regex("(?i)answer|accept|pick ?up|antwort|répondre|contestar|rispondi|接听|応答")
        val DECLINE_WORDS = Regex("(?i)decline|reject|dismiss|ablehnen|refuser|rechazar|rifiuta|拒绝|拒否")
    }
}
