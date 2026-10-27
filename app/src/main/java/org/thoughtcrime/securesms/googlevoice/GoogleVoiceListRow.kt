package org.thoughtcrime.securesms.googlevoice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.util.DateUtils
import java.util.Locale

/** AllIn: the accent that marks Google Voice threads in the chat list (Google Voice's green). */
val GoogleVoiceAccent = Color(0xFF34A853)

/**
 * AllIn: draws a Google Voice thread as a chat-list row. Called from ConversationListAdapter (Java).
 */
object GoogleVoiceListRow {
  @JvmStatic
  fun bind(view: ComposeView, thread: GoogleVoiceInbox.ThreadSummary?) {
    view.setContent {
      SignalTheme {
        if (thread != null) {
          GoogleVoiceRow(thread) {
            view.context.startActivity(GoogleVoiceConversationActivity.createIntent(view.context, thread.threadId, thread.name))
          }
        }
      }
    }
  }
}

@Composable
private fun GoogleVoiceRow(thread: GoogleVoiceInbox.ThreadSummary, onClick: () -> Unit) {
  val context = LocalContext.current
  val time = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), thread.dateMs).first
  val weight = if (thread.unread) FontWeight.Bold else FontWeight.Normal

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(76.dp)
      .clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .padding(start = 16.dp)
        .size(48.dp)
        .background(GoogleVoiceAccent.copy(alpha = 0.25f), CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = thread.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
      )
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 16.dp, end = 16.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = thread.name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = weight,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = time,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp)
        )
      }
      Text(
        text = (if (thread.lastMessageOutgoing) "You: " else "") + thread.snippet,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = weight,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun GoogleVoiceRowPreview() {
  Previews.Preview {
    GoogleVoiceRow(
      GoogleVoiceInbox.ThreadSummary(-1, "t1", "Pizza Place", "Your order is ready!", System.currentTimeMillis(), unread = true, lastMessageOutgoing = false),
      onClick = {}
    )
  }
}
