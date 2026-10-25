package org.thoughtcrime.securesms.googlevoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.googlevoice.GoogleVoiceInbox.snippet
import org.thoughtcrime.securesms.googlevoice.proto.Message
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import java.util.Locale

/**
 * AllIn: one Google Voice conversation, loaded fresh from Google each time it opens. Read-only for now.
 */
class GoogleVoiceConversationActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(GoogleVoiceConversationActivity::class.java)

    private const val EXTRA_THREAD_ID = "thread_id"
    private const val EXTRA_NAME = "name"

    @JvmStatic
    fun createIntent(context: Context, threadId: String, name: String): Intent {
      return Intent(context, GoogleVoiceConversationActivity::class.java)
        .putExtra(EXTRA_THREAD_ID, threadId)
        .putExtra(EXTRA_NAME, name)
    }
  }

  private val theme = DynamicNoActionBarTheme()

  private var messages by mutableStateOf<List<BubbleMessage>?>(null)
  private var error by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    val threadId = requireNotNull(intent.getStringExtra(EXTRA_THREAD_ID))
    val name = intent.getStringExtra(EXTRA_NAME) ?: "Google Voice"

    setContent {
      SignalTheme {
        GoogleVoiceConversationScreen(
          name = name,
          messages = messages,
          error = error,
          onNavigationClick = { finish() }
        )
      }
    }

    lifecycleScope.launch {
      try {
        val thread = withContext(Dispatchers.IO) { GoogleVoiceApi.getThread(threadId) }
        messages = thread?.messages.orEmpty()
          .filter { it.type == Message.Type.SMS_IN || it.type == Message.Type.SMS_OUT }
          .sortedBy { it.timestamp }
          .map { BubbleMessage(it.ID, it.snippet(), it.timestamp, outgoing = it.type == Message.Type.SMS_OUT) }
      } catch (e: GoogleVoiceException) {
        Log.w(TAG, "Couldn't load thread", e)
        error = if (e is GoogleVoiceException.NotSignedIn) "Signed out of Google Voice. Sign in again in Settings." else "Couldn't load this conversation."
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }
}

data class BubbleMessage(val id: String, val text: String, val timestamp: Long, val outgoing: Boolean)

@Composable
private fun GoogleVoiceConversationScreen(
  name: String,
  messages: List<BubbleMessage>?,
  error: String?,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = name,
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    bottomBar = {
      Text(
        text = "Replying from AllIn is coming soon. Use the Google Voice app to reply for now.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .padding(16.dp)
      )
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
          items(messages.asReversed(), key = { it.id }) { message -> Bubble(message) }
        }
      }
    }
  }
}

@Composable
private fun Bubble(message: BubbleMessage) {
  val context = LocalContext.current
  val time = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), message.timestamp).first

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
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      Text(text = message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      Text(text = time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@DayNightPreviews
@Composable
private fun GoogleVoiceConversationScreenPreview() {
  Previews.Preview {
    GoogleVoiceConversationScreen(
      name = "Pizza Place",
      messages = listOf(
        BubbleMessage("1", "Can I order a large pepperoni?", 1, outgoing = true),
        BubbleMessage("2", "Sure! Ready in 20 minutes.", 2, outgoing = false)
      ),
      error = null,
      onNavigationClick = {}
    )
  }
}
