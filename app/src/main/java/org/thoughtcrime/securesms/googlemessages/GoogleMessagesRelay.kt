package org.thoughtcrime.securesms.googlemessages

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.io.ByteArrayOutputStream
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * AllIn: lets AllIn Desktop read and send phone texts through this phone.
 *
 * Desktop and phone are linked devices on the same Signal account, so they talk with Signal sync messages (sent
 * only to the account's own devices, end-to-end encrypted) carrying JSON in the AllIn-only `allinRelay` field. Signal's
 * own apps ignore that field. Desktop asks (hello, thread, send, read, media, new); the phone answers and pushes
 * changes while Desktop has said hello recently.
 *
 * Messages to Desktop are also delivered to the account's other devices (the real Signal on the phone, which ignores
 * them), so pushes are kept small and only sent while a Desktop is listening.
 */
object GoogleMessagesRelay {

  private val TAG = Log.tag(GoogleMessagesRelay::class.java)

  /** Desktop says hello at start and every few hours; stop pushing when it's been quiet this long. */
  private val LISTENING_FOR_MS = TimeUnit.HOURS.toMillis(8)
  private const val MAX_PHOTO_DIMENSION = 1280
  private const val MAX_PHOTO_BYTES = 180_000

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var desktopSeenAt = 0L
  @Volatile private var threadsPush: Job? = null

  private fun enabled() = GoogleMessages.isActive() && SignalStore.googleMessages.relayToDesktop

  private fun desktopListening() = System.currentTimeMillis() - desktopSeenAt < LISTENING_FOR_MS

  /** A relay message from one of the user's other devices. */
  @JvmStatic
  fun onRelay(bytes: ByteArray) {
    val request = try {
      JSONObject(String(bytes))
    } catch (e: Exception) {
      Log.w(TAG, "Unreadable relay message")
      return
    }
    // Messages from other phones (another AllIn sending threads) are not for us.
    val type = request.optString("t")
    if (type !in setOf("hello", "thread", "send", "read", "media", "new")) return
    desktopSeenAt = System.currentTimeMillis()

    if (!enabled()) {
      send(JSONObject().put("t", "off").put("reason", if (SignalStore.googleMessages.relayToDesktop) "Phone texts are off on the phone" else "Turned off for Desktop on the phone"))
      return
    }

    scope.launch {
      try {
        when (type) {
          "hello" -> pushThreads()
          "thread" -> {
            val id = request.getString("id")
            val texts = runCatching { GoogleMessages.loadMessages(id) }.getOrElse { GoogleMessages.cachedMessages(id).orEmpty() }
            send(JSONObject().put("t", "messages").put("id", id).put("messages", JSONArray(texts.takeLast(30).map(::textJson))))
          }
          "read" -> GoogleMessages.markRead(request.getString("id"))
          "send", "new" -> {
            val localId = request.optString("localId")
            try {
              val id = if (type == "new") {
                val numbers = request.getJSONArray("numbers").let { a -> (0 until a.length()).map { a.getString(it) } }
                GoogleMessages.conversationFor(numbers)
              } else {
                request.getString("id")
              }
              val jpeg = request.optString("jpeg").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }
              GoogleMessages.send(id, request.optString("text"), jpeg, jpeg?.let { "image/jpeg" }, jpeg?.let { "photo.jpg" })
              send(JSONObject().put("t", "sent").put("localId", localId).put("ok", true).put("id", id))
            } catch (e: Exception) {
              Log.w(TAG, "Relay send failed", e)
              send(JSONObject().put("t", "sent").put("localId", localId).put("ok", false).put("error", e.message ?: "Not sent"))
            }
          }
          "media" -> {
            val attachment = GoogleMessages.Attachment(request.getString("mediaId"), request.getString("key"), request.optString("mime"), "", true)
            val reply = JSONObject().put("t", "media").put("mediaId", attachment.mediaId)
            try {
              reply.put("data", Base64.encodeToString(shrink(GoogleMessages.downloadMedia(attachment)), Base64.NO_WRAP))
            } catch (e: Exception) {
              Log.w(TAG, "Relay media failed", e)
              reply.put("error", "Couldn't load the picture")
            }
            send(reply)
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Relay request $type failed", e)
      }
    }
  }

  /** The conversation list changed; tell a listening Desktop shortly (changes come in bursts). */
  fun onThreadsChanged() {
    if (!enabled() || !desktopListening()) return
    if (threadsPush?.isActive == true) return
    threadsPush = scope.launch {
      delay(3_000)
      pushThreads()
    }
  }

  fun onMessage(text: GoogleMessages.Text) {
    if (!enabled() || !desktopListening()) return
    scope.launch { send(JSONObject().put("t", "message").put("message", textJson(text))) }
  }

  private fun pushThreads() {
    val threads = GoogleMessages.threads().take(40).map {
      JSONObject()
        .put("id", it.threadId)
        .put("name", it.name)
        .put("snippet", it.snippet.take(200))
        .put("dateMs", it.dateMs)
        .put("unread", it.unread)
        .put("outgoing", it.lastMessageOutgoing)
        .put("rcs", it.rcs)
    }
    send(JSONObject().put("t", "threads").put("status", GoogleMessages.status).put("threads", JSONArray(threads)))
  }

  private fun textJson(text: GoogleMessages.Text): JSONObject {
    return JSONObject()
      .put("id", text.id)
      .put("conversationId", text.conversationId)
      .put("text", text.text)
      .put("ts", text.timestamp)
      .put("out", text.outgoing)
      .put("failed", text.failed)
      .put("sending", text.sending)
      .put("sender", text.senderName ?: JSONObject.NULL)
      .put(
        "att",
        JSONArray(
          text.attachments.map {
            JSONObject().put("mediaId", it.mediaId).put("key", it.key).put("mime", it.mime).put("name", it.name).put("image", it.isImage)
          }
        )
      )
  }

  private fun shrink(bytes: ByteArray): ByteArray {
    var bitmap = GmPhotos.decodeScaled(bytes, MAX_PHOTO_DIMENSION) ?: return bytes
    var quality = 80
    while (true) {
      val out = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      if (out.size() <= MAX_PHOTO_BYTES || quality <= 40) return out.toByteArray()
      quality -= 15
      if (quality <= 50) bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3 / 4, bitmap.height * 3 / 4, true)
    }
  }

  private fun send(message: JSONObject) {
    try {
      val content = Content(
        syncMessage = SyncMessage(
          allinRelay = message.toString().toByteArray().toByteString(),
          padding = ByteArray(16 + (Math.random() * 64).toInt()).toByteString()
        )
      )
      AppDependencies.signalServiceMessageSender.sendSyncMessage(content, false, Optional.empty())
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't send relay message", e)
    }
  }
}
