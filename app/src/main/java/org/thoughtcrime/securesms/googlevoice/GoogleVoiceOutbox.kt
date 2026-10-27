package org.thoughtcrime.securesms.googlevoice

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.util.concurrent.TimeUnit

/**
 * AllIn: Google Voice texts being sent. Lives for the whole app (not one screen), so a send keeps going and stays
 * visible if the conversation is closed or rotated. Each send is tried up to [ATTEMPTS] times; one that still fails
 * stays as "Not sent" until the user taps it to retry. Kept in memory only, like the rest of Google Voice.
 */
object GoogleVoiceOutbox {

  private val TAG = Log.tag(GoogleVoiceOutbox::class.java)

  private const val ATTEMPTS = 3
  private val RETRY_DELAYS_MS = listOf(TimeUnit.SECONDS.toMillis(5), TimeUnit.SECONDS.toMillis(15))

  enum class Status { SENDING, SENT, FAILED }

  data class Item(
    val localId: String,
    val threadId: String,
    val recipients: List<String>,
    val text: String,
    val jpeg: ByteArray?,
    val preview: Bitmap?,
    val timestamp: Long,
    val status: Status = Status.SENDING,
    val serverId: String? = null
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val _items = MutableStateFlow<List<Item>>(emptyList())
  val items: StateFlow<List<Item>> = _items

  /** One signer for the app: its BotGuard program stays loaded between conversations (faster sends). */
  private var signer: GoogleVoiceSigner? = null

  private fun signer(): GoogleVoiceSigner {
    return signer ?: GoogleVoiceSigner(AppDependencies.application).also { signer = it }
  }

  /** Loads the signing script ahead of the first send. Call from the main thread. */
  fun warmUp() {
    signer().warmUp()
  }

  fun send(threadId: String, recipients: List<String>, text: String, jpeg: ByteArray?, preview: Bitmap?) {
    val item = Item(
      localId = "local-${GoogleVoiceApi.newTransactionId()}",
      threadId = threadId,
      recipients = recipients,
      text = text,
      jpeg = jpeg,
      preview = preview,
      timestamp = System.currentTimeMillis()
    )
    _items.update { it + item }
    deliver(item)
  }

  fun retry(localId: String) {
    val item = _items.value.firstOrNull { it.localId == localId && it.status == Status.FAILED } ?: return
    set(localId) { it.copy(status = Status.SENDING) }
    deliver(item)
  }

  /** Drops sent texts that Google now lists itself ([isOnServer] says which). */
  fun acknowledge(threadId: String, isOnServer: (Item) -> Boolean) {
    _items.update { list -> list.filterNot { it.threadId == threadId && it.status == Status.SENT && isOnServer(it) } }
  }

  private fun deliver(item: Item) {
    scope.launch {
      for (attempt in 1..ATTEMPTS) {
        try {
          val transactionId = GoogleVoiceApi.newTransactionId()
          // Without a signature, send anyway with the web app's placeholder: Google may still accept it.
          val signature = signer().sign(item.threadId, item.recipients, transactionId) ?: "!".also { Log.w(TAG, "Sending without a signature") }
          val response = withContext(Dispatchers.IO) {
            GoogleVoiceApi.sendSms(item.threadId, item.text, transactionId, signature, item.jpeg)
          }
          set(item.localId) { it.copy(status = Status.SENT, serverId = response.threadItemID.ifBlank { null }) }
          GoogleVoiceMonitor.requestCheck()
          return@launch
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Send attempt $attempt of $ATTEMPTS failed", e)
          if (e is GoogleVoiceException.NotSignedIn || attempt == ATTEMPTS) break
          delay(RETRY_DELAYS_MS[attempt - 1])
        }
      }
      set(item.localId) { it.copy(status = Status.FAILED) }
    }
  }

  private fun set(localId: String, change: (Item) -> Item) {
    _items.update { list -> list.map { if (it.localId == localId) change(it) else it } }
  }
}
