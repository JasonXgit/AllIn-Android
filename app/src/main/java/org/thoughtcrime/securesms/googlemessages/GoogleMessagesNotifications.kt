package org.thoughtcrime.securesms.googlemessages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.googlevoice.PhoneTextsAccent
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * AllIn: notifications for incoming phone texts, one per conversation, on their own "Phone texts" channel.
 *
 * Google Messages on the phone notifies too; users who want only AllIn's can turn those off in Google Messages.
 */
object GoogleMessagesNotifications {

  private val TAG = Log.tag(GoogleMessagesNotifications::class.java)

  private const val CHANNEL_ID = "allin_phone_texts"

  @Volatile
  var openConversationId: String? = null

  private val lines = mutableMapOf<String, MutableList<String>>()

  @Synchronized
  fun onIncoming(text: GoogleMessages.Text, name: String) {
    val notifiedUpTo = SignalStore.googleMessages.notifiedUpTo
    if (notifiedUpTo == 0L || text.timestamp <= notifiedUpTo) return
    SignalStore.googleMessages.notifiedUpTo = text.timestamp
    if (!SignalStore.googleMessages.notificationsEnabled || text.conversationId == openConversationId) return

    val context = AppDependencies.application
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    ensureChannel(context)

    val body = text.text.ifBlank { if (text.attachments.isNotEmpty()) "Photo" else "New message" }
    val conversationLines = lines.getOrPut(text.conversationId) { mutableListOf() }
    conversationLines += (text.senderName?.let { "$it: " } ?: "") + body
    while (conversationLines.size > 6) conversationLines.removeAt(0)

    val tap = PendingIntent.getActivity(
      context,
      notificationId(text.conversationId),
      GoogleMessagesConversationActivity.createIntent(context, text.conversationId, name),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(PhoneTextsAccent.toArgb())
      .setContentTitle(name)
      .setContentText(conversationLines.last())
      .setStyle(NotificationCompat.BigTextStyle().bigText(conversationLines.joinToString("\n")))
      .setSubText("Text message")
      .setWhen(text.timestamp)
      .setShowWhen(true)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(tap)
      .setAutoCancel(true)
      .build()
    try {
      manager.notify(notificationId(text.conversationId), notification)
    } catch (e: SecurityException) {
      Log.w(TAG, "Not allowed to notify", e)
    }
  }

  @Synchronized
  fun cancel(conversationId: String) {
    lines.remove(conversationId)
    NotificationManagerCompat.from(AppDependencies.application).cancel(notificationId(conversationId))
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Phone texts", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "New SMS, MMS and RCS messages"
        }
      )
    }
  }

  private fun notificationId(conversationId: String): Int = 0x6800000 + (conversationId.hashCode() and 0xFFFFF)
}
