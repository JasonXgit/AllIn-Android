package org.thoughtcrime.securesms.conversationlist

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.fantasy.EspnApi
import org.thoughtcrime.securesms.fantasy.EspnException
import org.thoughtcrime.securesms.fantasy.FantasyFootballActivity
import org.thoughtcrime.securesms.googlevoice.GoogleVoiceInbox
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.Locale

/**
 * AllIn: rows pinned above the chat list for AllIn's own features (currently Fantasy Football), plus the refresh
 * loop that keeps Google Voice threads in the list current.
 *
 * The pinned rows live in a ComposeView above the list rather than inside its paged adapter, so Signal's swipe,
 * selection and paging code is untouched. [refresh] is called whenever the chat list resumes.
 */
class AllInPinnedRows(private val view: ComposeView, private val owner: LifecycleOwner) {

  companion object {
    private val TAG = Log.tag(AllInPinnedRows::class.java)

    /** How often Google Voice is re-checked while the chat list is on screen. */
    private const val GOOGLE_VOICE_REFRESH_MS = 60_000L
  }

  private var fantasySummary by mutableStateOf<String?>(null)

  init {
    view.setContent {
      SignalTheme {
        FantasyRow(
          summary = fantasySummary,
          onClick = { view.context.startActivity(FantasyFootballActivity.createIntent(view.context)) }
        )
      }
    }

    owner.lifecycleScope.launch {
      owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
          withContext(Dispatchers.IO) { GoogleVoiceInbox.refresh() }
          delay(GOOGLE_VOICE_REFRESH_MS)
        }
      }
    }
  }

  fun refresh(isArchive: Boolean) {
    val show = !isArchive && SignalStore.fantasy.enabled && SignalStore.fantasy.signedIn
    view.visibility = if (show) View.VISIBLE else View.GONE
    if (!show) return

    owner.lifecycleScope.launch {
      fantasySummary = withContext(Dispatchers.IO) { loadFantasySummary() } ?: fantasySummary
    }
  }

  private fun loadFantasySummary(): String? {
    return try {
      val leagues = EspnApi.myLeagues()
      val entry = leagues.firstOrNull { it.leagueId == SignalStore.fantasy.selectedLeague } ?: leagues.firstOrNull() ?: return "No leagues found"
      val league = EspnApi.league(entry)
      val matchup = league.myMatchup ?: return "${league.name} · Week ${league.week}"

      val homeIsMine = matchup.home.id == entry.teamId
      val mine = if (homeIsMine) matchup.homePoints else matchup.awayPoints
      val theirs = if (homeIsMine) matchup.awayPoints else matchup.homePoints
      val opponent = (if (homeIsMine) matchup.away else matchup.home)?.name ?: return "Week ${league.week} · Bye"

      "Week ${league.week} · You ${points(mine)} – ${points(theirs)} $opponent"
    } catch (e: EspnException) {
      Log.w(TAG, "Couldn't load fantasy summary", e)
      if (e is EspnException.NotSignedIn) "Sign in to ESPN again" else null
    }
  }

  private fun points(value: Double) = String.format(Locale.US, "%.1f", value)
}

@Composable
private fun FantasyRow(summary: String?, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(48.dp)
        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_avatar_football),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(32.dp)
      )
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 16.dp)
    ) {
      Text(text = "Fantasy Football", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      Text(
        text = summary ?: "Your ESPN leagues",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    Icon(
      painter = painterResource(R.drawable.symbol_pin_24),
      contentDescription = "Pinned",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .padding(start = 8.dp)
        .size(16.dp)
    )
  }
}

@DayNightPreviews
@Composable
private fun FantasyRowPreview() {
  Previews.Preview {
    FantasyRow(summary = "Week 7 · You 50.8 – 80.7 Erik and Bridget's Best Team", onClick = {})
  }
}
