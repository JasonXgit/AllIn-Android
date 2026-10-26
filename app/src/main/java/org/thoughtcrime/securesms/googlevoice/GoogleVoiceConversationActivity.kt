package org.thoughtcrime.securesms.googlevoice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.R as CoreUiR
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.googlevoice.proto.Message
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AllIn: one Google Voice conversation, loaded from Google and re-checked every few seconds while on screen, with a
 * box to reply (text and photos). Sent messages show straight away and are marked once Google accepts them.
 */
class GoogleVoiceConversationActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(GoogleVoiceConversationActivity::class.java)

    private const val EXTRA_THREAD_ID = "thread_id"
    private const val EXTRA_NAME = "name"

    /** How often the open conversation is re-checked for new messages. */
    private const val REFRESH_MS = 5_000L

    @JvmStatic
    fun createIntent(context: Context, threadId: String, name: String): Intent {
      return Intent(context, GoogleVoiceConversationActivity::class.java)
        .putExtra(EXTRA_THREAD_ID, threadId)
        .putExtra(EXTRA_NAME, name)
    }
  }

  private val theme = DynamicNoActionBarTheme()

  private var serverMessages by mutableStateOf<List<BubbleMessage>?>(null)
  private val pending = mutableStateListOf<PendingMessage>()
  private var error by mutableStateOf<String?>(null)
  private var draft by mutableStateOf("")
  private var attachment by mutableStateOf<PreparedPhoto?>(null)

  private lateinit var threadId: String
  private var recipients: List<String> = emptyList()
  private lateinit var signer: GoogleVoiceSigner

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    threadId = requireNotNull(intent.getStringExtra(EXTRA_THREAD_ID))
    val name = intent.getStringExtra(EXTRA_NAME) ?: "Google Voice"

    signer = GoogleVoiceSigner(this)
    signer.warmUp()

    setContent {
      SignalTheme {
        GoogleVoiceConversationScreen(
          name = name,
          messages = serverMessages?.let { it + pending.map(PendingMessage::toBubble) },
          error = error,
          draft = draft,
          attachment = attachment?.preview,
          onDraftChange = { draft = it },
          onPhotoPicked = ::onPhotoPicked,
          onRemoveAttachment = { attachment = null },
          onSendClick = ::onSendClick,
          onRetryClick = ::retry,
          onNavigationClick = { finish() }
        )
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
          loadThread()
          delay(REFRESH_MS)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  override fun onDestroy() {
    signer.destroy()
    super.onDestroy()
  }

  private suspend fun loadThread() {
    try {
      val thread = withContext(Dispatchers.IO) { GoogleVoiceApi.getThread(threadId) }
      recipients = thread?.phoneNumbers.orEmpty()
      val loaded = thread?.messages.orEmpty()
        .filter { it.type == Message.Type.SMS_IN || it.type == Message.Type.SMS_OUT }
        .sortedBy { it.timestamp }
        .map { it.toBubble() }

      // Once Google lists a message we sent, show Google's copy instead of ours.
      pending.removeAll { sent -> sent.status == SendStatus.SENT && loaded.any { sent.matches(it) } }
      serverMessages = loaded
      error = null

      if (thread != null && !thread.read) {
        GoogleVoiceInbox.markReadLocally(threadId)
        withContext(Dispatchers.IO) { GoogleVoiceApi.markRead(threadId) }
      }
    } catch (e: GoogleVoiceException) {
      Log.w(TAG, "Couldn't load thread", e)
      if (serverMessages == null) {
        error = if (e is GoogleVoiceException.NotSignedIn) "Signed out of Google Voice. Sign in again in Settings." else "Couldn't load this conversation."
      }
    }
  }

  private fun onPhotoPicked(uri: Uri) {
    lifecycleScope.launch {
      try {
        val (jpeg, preview) = withContext(Dispatchers.IO) { GoogleVoicePhotos.prepareForSending(this@GoogleVoiceConversationActivity, uri) }
        attachment = PreparedPhoto(jpeg, preview)
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read photo", e)
        Toast.makeText(this@GoogleVoiceConversationActivity, "Couldn't use that photo.", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun onSendClick() {
    val text = draft.trim()
    val photo = attachment
    if (text.isEmpty() && photo == null) return

    val message = PendingMessage(
      localId = "local-${GoogleVoiceApi.newTransactionId()}",
      text = text,
      photo = photo,
      timestamp = System.currentTimeMillis()
    )
    pending += message
    draft = ""
    attachment = null
    send(message)
  }

  private fun retry(localId: String) {
    val index = pending.indexOfFirst { it.localId == localId }
    if (index < 0) return
    val message = pending[index].copy(status = SendStatus.SENDING)
    pending[index] = message
    send(message)
  }

  private fun send(message: PendingMessage) {
    lifecycleScope.launch {
      val result = try {
        val transactionId = GoogleVoiceApi.newTransactionId()
        // Without a signature, send anyway with the web app's placeholder: Google may still accept it.
        val signature = signer.sign(threadId, recipients, transactionId) ?: "!".also { Log.w(TAG, "Sending without a signature") }
        val response = withContext(Dispatchers.IO) { GoogleVoiceApi.sendSms(threadId, message.text, transactionId, signature, message.photo?.jpeg) }
        message.copy(status = SendStatus.SENT, serverId = response.threadItemID.ifBlank { null })
      } catch (e: GoogleVoiceException) {
        Log.w(TAG, "Couldn't send", e)
        if (e is GoogleVoiceException.NotSignedIn) {
          Toast.makeText(this@GoogleVoiceConversationActivity, "Signed out of Google Voice. Sign in again in Settings.", Toast.LENGTH_LONG).show()
        }
        message.copy(status = SendStatus.FAILED)
      }

      val index = pending.indexOfFirst { it.localId == message.localId }
      if (index >= 0) pending[index] = result

      if (result.status == SendStatus.SENT) {
        loadThread()
        withContext(Dispatchers.IO) { GoogleVoiceInbox.refresh() }
      }
    }
  }
}

enum class SendStatus { RECEIVED, SENDING, SENT, FAILED }

data class BubbleMessage(
  val id: String,
  val text: String,
  val timestamp: Long,
  val outgoing: Boolean,
  val attachmentIds: List<String> = emptyList(),
  val localPhoto: Bitmap? = null,
  val status: SendStatus = SendStatus.RECEIVED
)

private class PreparedPhoto(val jpeg: ByteArray, val preview: Bitmap)

private data class PendingMessage(
  val localId: String,
  val text: String,
  val photo: PreparedPhoto?,
  val timestamp: Long,
  val status: SendStatus = SendStatus.SENDING,
  val serverId: String? = null
) {
  fun toBubble() = BubbleMessage(localId, text, timestamp, outgoing = true, localPhoto = photo?.preview, status = status)

  /** True if [server] is Google's copy of this message. */
  fun matches(server: BubbleMessage): Boolean {
    if (serverId != null && server.id == serverId) return true
    return server.outgoing && server.text == text && server.timestamp >= timestamp - TimeUnit.MINUTES.toMillis(2)
  }
}

private fun Message.toBubble(): BubbleMessage {
  val mms = MMS
  return BubbleMessage(
    id = ID,
    text = text.ifBlank { mms?.text.orEmpty() },
    timestamp = timestamp,
    outgoing = type == Message.Type.SMS_OUT,
    attachmentIds = mms?.attachments.orEmpty().filter { it.ID.isNotBlank() }.map { it.ID }
  )
}

@Composable
private fun GoogleVoiceConversationScreen(
  name: String,
  messages: List<BubbleMessage>?,
  error: String?,
  draft: String,
  attachment: Bitmap?,
  onDraftChange: (String) -> Unit,
  onPhotoPicked: (Uri) -> Unit,
  onRemoveAttachment: () -> Unit,
  onSendClick: () -> Unit,
  onRetryClick: (String) -> Unit,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = name,
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    bottomBar = {
      if (messages != null) {
        ComposeBar(
          draft = draft,
          attachment = attachment,
          onDraftChange = onDraftChange,
          onPhotoPicked = onPhotoPicked,
          onRemoveAttachment = onRemoveAttachment,
          onSendClick = onSendClick
        )
      }
    }
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      when {
        error != null -> Text(text = error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        messages == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        else -> LazyColumn(
          modifier = Modifier.fillMaxSize(),
          reverseLayout = true,
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          items(messages.asReversed(), key = { it.id }) { message -> Bubble(message, onRetryClick) }
        }
      }
    }
  }
}

@Composable
private fun ComposeBar(
  draft: String,
  attachment: Bitmap?,
  onDraftChange: (String) -> Unit,
  onPhotoPicked: (Uri) -> Unit,
  onRemoveAttachment: () -> Unit,
  onSendClick: () -> Unit
) {
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPhotoPicked) }
  val canSend = draft.isNotBlank() || attachment != null

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
      .padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
    if (attachment != null) {
      Box(modifier = Modifier.padding(start = 48.dp, bottom = 6.dp)) {
        Image(
          bitmap = attachment.asImageBitmap(),
          contentDescription = "Photo to send",
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
        )
        IconButton(
          onClick = onRemoveAttachment,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(28.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
        ) {
          Icon(painter = painterResource(CoreUiR.drawable.symbol_x_24), contentDescription = "Remove photo", modifier = Modifier.size(18.dp))
        }
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Icon(
          painter = painterResource(CoreUiR.drawable.symbol_photo_24),
          contentDescription = "Attach a photo",
          tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      TextField(
        value = draft,
        onValueChange = onDraftChange,
        placeholder = { Text("Google Voice message") },
        maxLines = 5,
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent
        ),
        modifier = Modifier.weight(1f)
      )
      IconButton(onClick = onSendClick, enabled = canSend) {
        Icon(
          painter = painterResource(CoreUiR.drawable.symbol_send_fill_24),
          contentDescription = "Send",
          tint = if (canSend) GoogleVoiceAccent else MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun Bubble(message: BubbleMessage, onRetryClick: (String) -> Unit) {
  val context = LocalContext.current
  val time = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), message.timestamp).first
  val footer = when (message.status) {
    SendStatus.RECEIVED -> time
    SendStatus.SENDING -> "Sending…"
    SendStatus.SENT -> "$time · Sent"
    SendStatus.FAILED -> "Not sent. Tap to retry."
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 2.dp),
    horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 300.dp)
        .background(
          color = if (message.outgoing) GoogleVoiceAccent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp)
        )
        .then(if (message.status == SendStatus.FAILED) Modifier.clickable { onRetryClick(message.id) } else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      message.localPhoto?.let { BubblePhoto(it) }
      message.attachmentIds.forEach { AttachmentPhoto(it) }
      if (message.text.isNotEmpty()) {
        Text(text = message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      }
      Text(
        text = footer,
        style = MaterialTheme.typography.labelSmall,
        color = if (message.status == SendStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun AttachmentPhoto(attachmentId: String) {
  val bitmap by produceState<Bitmap?>(initialValue = null, attachmentId) {
    value = withContext(Dispatchers.IO) {
      try {
        GoogleVoicePhotos.load(attachmentId)
      } catch (e: GoogleVoiceException) {
        null
      }
    }
  }

  val loaded = bitmap
  if (loaded != null) {
    BubblePhoto(loaded)
  } else {
    Box(modifier = Modifier.size(160.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
  }
}

@Composable
private fun BubblePhoto(bitmap: Bitmap) {
  Image(
    bitmap = bitmap.asImageBitmap(),
    contentDescription = "Photo",
    contentScale = ContentScale.Fit,
    modifier = Modifier
      .padding(bottom = 4.dp)
      .heightIn(max = 320.dp)
      .clip(RoundedCornerShape(12.dp))
  )
}

@DayNightPreviews
@Composable
private fun GoogleVoiceConversationScreenPreview() {
  Previews.Preview {
    GoogleVoiceConversationScreen(
      name = "Pizza Place",
      messages = listOf(
        BubbleMessage("1", "Can I order a large pepperoni?", 1, outgoing = true),
        BubbleMessage("2", "Sure! Ready in 20 minutes.", 2, outgoing = false),
        BubbleMessage("3", "Thanks!", 3, outgoing = true, status = SendStatus.SENDING)
      ),
      error = null,
      draft = "On my way",
      attachment = null,
      onDraftChange = {},
      onPhotoPicked = {},
      onRemoveAttachment = {},
      onSendClick = {},
      onRetryClick = {},
      onNavigationClick = {}
    )
  }
}
