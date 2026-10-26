package org.thoughtcrime.securesms.googlevoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.graphics.toArgb
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.googlevoice.GoogleVoiceInbox.snippet
import org.thoughtcrime.securesms.googlevoice.proto.Message
import org.thoughtcrime.securesms.googlevoice.proto.Thread
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * AllIn: notifications for incoming Google Voice texts, one per conversation, on their own "Google Voice" channel.
 */
object GoogleVoiceNotifications {

  private val TAG = Log.tag(GoogleVoiceNotifications::class.java)

  private const val CHANNEL_ID = "allin_google_voice"

  /** The conversation on screen right now, which shouldn't get notifications. */
  @Volatile
  var openThreadId: String? = null

  /** Notifies for incoming texts newer than the last ones notified, and clears notifications for read threads. */
  @Synchronized
  fun onThreads(threads: List<Thread>) {
    val context = AppDependencies.application
    val notifiedUpTo = SignalStore.googleVoice.notifiedUpTo
    if (notifiedUpTo == 0L) {
      // First run: don't notify for texts that arrived before notifications were set up.
      SignalStore.googleVoice.notifiedUpTo = threads.flatMap { it.messages }.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
      return
    }

    var newest = notifiedUpTo
    for (thread in threads.filter { it.isText }) {
      if (thread.read) {
        cancel(thread.ID)
        continue
      }

      val incoming = thread.messages.filter { it.type == Message.Type.SMS_IN && it.timestamp > notifiedUpTo }.sortedBy { it.timestamp }
      if (incoming.isEmpty()) continue
      newest = maxOf(newest, incoming.last().timestamp)

      if (thread.ID != openThreadId && SignalStore.googleVoice.notificationsEnabled) {
        show(context, thread, incoming)
      }
    }
    SignalStore.googleVoice.notifiedUpTo = newest
  }

  fun cancel(threadId: String) {
    NotificationManagerCompat.from(AppDependencies.application).cancel(notificationId(threadId))
  }

  private fun show(context: Context, thread: Thread, incoming: List<Message>) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) {
      Log.w(TAG, "Notifications are off for AllIn")
      return
    }
    ensureChannel(context)

    val name = GoogleVoiceInbox.threads().firstOrNull { it.threadId == thread.ID }?.name
      ?: thread.phoneNumbers.firstOrNull()?.let { GoogleVoiceContacts.displayName(it, null) }
      ?: "Google Voice"
    val lines = incoming.map { it.snippet() }.filter { it.isNotBlank() }

    val tap = PendingIntent.getActivity(
      context,
      notificationId(thread.ID),
      GoogleVoiceConversationActivity.createIntent(context, thread.ID, name),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(GoogleVoiceAccent.toArgb())
      .setContentTitle(name)
      .setContentText(lines.lastOrNull() ?: "New message")
      .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
      .setSubText("Google Voice")
      .setWhen(incoming.last().timestamp)
      .setShowWhen(true)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(tap)
      .setAutoCancel(true)
      .build()

    try {
      manager.notify(notificationId(thread.ID), notification)
    } catch (e: SecurityException) {
      Log.w(TAG, "Not allowed to notify", e)
    }
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Google Voice", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "New Google Voice texts"
        }
      )
    }
  }

  private fun notificationId(threadId: String): Int = 0x6700000 + (threadId.hashCode() and 0xFFFFF)
}
