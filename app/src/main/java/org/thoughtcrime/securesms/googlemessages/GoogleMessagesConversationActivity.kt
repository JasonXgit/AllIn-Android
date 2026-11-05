package org.thoughtcrime.securesms.googlemessages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.googlevoice.ComposeBar
import org.thoughtcrime.securesms.googlevoice.GoogleVoicePhotos
import org.thoughtcrime.securesms.googlevoice.PhoneTextsAccent
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import java.util.Locale

/**
 * AllIn: one phone-text conversation (SMS, MMS or RCS, through Google Messages), with a box to reply with text
 * or a photo. Updates live as messages arrive.
 */
class GoogleMessagesConversationActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(GoogleMessagesConversationActivity::class.java)

    private const val EXTRA_ID = "conversation_id"
    private const val EXTRA_NAME = "name"

    @JvmStatic
    fun createIntent(context: Context, conversationId: String, name: String): Intent {
      return Intent(context, GoogleMessagesConversationActivity::class.java)
        .putExtra(EXTRA_ID, conversationId)
        .putExtra(EXTRA_NAME, name)
    }
  }

  /** A text being sent from this screen, shown until the phone reports it. */
  private data class Pending(val localId: String, val text: String, val photo: Bitmap?, val jpeg: ByteArray?, val timestamp: Long, val failed: Boolean)

  private val theme = DynamicNoActionBarTheme()

  private var loaded by mutableStateOf<List<GoogleMessages.Text>?>(null)
  private var pending by mutableStateOf<List<Pending>>(emptyList())
  private var error by mutableStateOf<String?>(null)
  private var draft by mutableStateOf("")
  private var attachment by mutableStateOf<Pair<ByteArray, Bitmap>?>(null)

  private lateinit var conversationId: String

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    conversationId = requireNotNull(intent.getStringExtra(EXTRA_ID))
    val name = intent.getStringExtra(EXTRA_NAME) ?: GoogleMessages.conversationName(conversationId)
    val rcs = GoogleMessages.conversation(conversationId)?.type == org.thoughtcrime.securesms.googlemessages.proto.ConversationType.RCS

    setContent {
      SignalTheme {
        Scaffolds.Settings(
          title = name,
          onNavigationClick = { finish() },
          navigationIcon = SignalIcons.ArrowStart.imageVector,
          bottomBar = {
            if (loaded != null) {
              ComposeBar(
                draft = draft,
                attachment = attachment?.second,
                onDraftChange = { draft = it },
                onPhotoPicked = ::onPhotoPicked,
                onRemoveAttachment = { attachment = null },
                onSendClick = ::onSendClick,
                placeholder = if (rcs) "RCS message" else "Text message",
                accent = PhoneTextsAccent
              )
            }
          }
        ) { paddingValues ->
          Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val messages = loaded
            when {
              error != null && messages == null -> Text(text = error!!, modifier = Modifier.align(Alignment.Center).padding(24.dp))
              messages == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
              else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                items(pending.asReversed(), key = { it.localId }) { item -> PendingBubble(item) }
                items(messages.asReversed(), key = { it.id }) { message -> TextBubble(message) }
              }
            }
          }
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        load()
        // Live updates: new messages land in GoogleMessages' cache for open conversations.
        GoogleMessages.changes.collectLatest {
          GoogleMessages.cachedMessages(conversationId)?.let { onLoaded(it) }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    GoogleMessagesNotifications.openConversationId = conversationId
    GoogleMessagesNotifications.cancel(conversationId)
  }

  override fun onPause() {
    GoogleMessagesNotifications.openConversationId = null
    super.onPause()
  }

  private suspend fun load() {
    try {
      onLoaded(withContext(Dispatchers.IO) { GoogleMessages.loadMessages(conversationId) })
      withContext(Dispatchers.IO) { runCatching { GoogleMessages.markRead(conversationId) } }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't load conversation", e)
      error = if (GoogleMessages.isActive()) "Couldn't reach Google Messages on your phone." else "Phone texts are off. Turn them on in Settings."
    }
  }

  private fun onLoaded(messages: List<GoogleMessages.Text>) {
    loaded = messages
    error = null
    // Once the phone lists a text we sent, drop our placeholder.
    pending = pending.filterNot { p -> messages.any { it.outgoing && it.text == p.text && it.timestamp >= p.timestamp - 120_000 } }
  }

  private fun onPhotoPicked(uri: Uri) {
    lifecycleScope.launch {
      try {
        attachment = withContext(Dispatchers.IO) { GoogleVoicePhotos.prepareForSending(this@GoogleMessagesConversationActivity, uri) }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read photo", e)
        Toast.makeText(this@GoogleMessagesConversationActivity, "Couldn't use that photo.", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun onSendClick() {
    val text = draft.trim()
    val photo = attachment
    if (text.isEmpty() && photo == null) return
    val item = Pending("local-${System.nanoTime()}", text, photo?.second, photo?.first, System.currentTimeMillis(), failed = false)
    pending = pending + item
    draft = ""
    attachment = null
    deliver(item)
  }

  private fun deliver(item: Pending) {
    lifecycleScope.launch {
      try {
        withContext(Dispatchers.IO) {
          GoogleMessages.send(conversationId, item.text, item.jpeg, item.jpeg?.let { "image/jpeg" }, item.jpeg?.let { "photo.jpg" })
        }
        if (item.jpeg != null) {
          // Photos don't match by text; keep them until the next load replaces them.
          load()
          pending = pending.filterNot { it.localId == item.localId }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Send failed", e)
        pending = pending.map { if (it.localId == item.localId) it.copy(failed = true) else it }
      }
    }
  }

  private fun retry(localId: String) {
    val item = pending.firstOrNull { it.localId == localId } ?: return
    pending = pending.map { if (it.localId == localId) it.copy(failed = false) else it }
    deliver(item.copy(failed = false))
  }

  @Composable
  private fun PendingBubble(item: Pending) {
    BubbleFrame(outgoing = true, onClick = if (item.failed) ({ retry(item.localId) }) else null) {
      item.photo?.let { Photo(it) }
      if (item.text.isNotEmpty()) BubbleText(item.text)
      Footer(if (item.failed) "Not sent. Tap to retry." else "Sending…", error = item.failed)
    }
  }

  @Composable
  private fun TextBubble(message: GoogleMessages.Text) {
    val context = LocalContext.current
    val time = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), message.timestamp).first
    BubbleFrame(outgoing = message.outgoing, onClick = null) {
      message.senderName?.let {
        Text(text = it, style = MaterialTheme.typography.labelMedium, color = PhoneTextsAccent)
      }
      message.attachments.filter { it.isImage }.forEach { AttachmentPhoto(it) }
      message.attachments.filterNot { it.isImage }.forEach { BubbleText("📎 ${it.name.ifBlank { it.mime }}") }
      if (message.text.isNotEmpty()) BubbleText(message.text)
      Footer(
        when {
          message.failed -> "Not sent"
          message.sending -> "Sending…"
          else -> time
        },
        error = message.failed
      )
    }
  }
}

@Composable
private fun BubbleFrame(outgoing: Boolean, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 300.dp)
        .background(
          color = if (outgoing) PhoneTextsAccent.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp)
        )
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      content()
    }
  }
}

@Composable
private fun BubbleText(text: String) {
  Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Footer(text: String, error: Boolean) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  )
}

@Composable
private fun AttachmentPhoto(attachment: GoogleMessages.Attachment) {
  val bitmap by produceState<Bitmap?>(initialValue = GmPhotos.cached(attachment.mediaId), attachment.mediaId) {
    if (value == null) value = withContext(Dispatchers.IO) { GmPhotos.load(attachment) }
  }
  val loaded = bitmap
  if (loaded != null) {
    Photo(loaded)
  } else {
    Box(modifier = Modifier.size(160.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
  }
}

@Composable
private fun Photo(bitmap: Bitmap) {
  Image(
    bitmap = bitmap.asImageBitmap(),
    contentDescription = "Photo",
    contentScale = ContentScale.Fit,
    modifier = Modifier.padding(bottom = 4.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
  )
}

/** AllIn: pictures from phone texts, downloaded from Google and kept in memory only. */
internal object GmPhotos {
  private val TAG = Log.tag(GmPhotos::class.java)

  private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  fun cached(mediaId: String): Bitmap? = cache.get(mediaId)

  fun load(attachment: GoogleMessages.Attachment): Bitmap? {
    cache.get(attachment.mediaId)?.let { return it }
    return try {
      val bytes = GoogleMessages.downloadMedia(attachment)
      decodeScaled(bytes, 1600)?.also { cache.put(attachment.mediaId, it) }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't load picture", e)
      null
    }
  }

  fun decodeScaled(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
  }
}
