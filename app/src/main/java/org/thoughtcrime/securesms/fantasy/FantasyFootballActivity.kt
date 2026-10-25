package org.thoughtcrime.securesms.fantasy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import java.util.Locale

/**
 * AllIn: the user's ESPN fantasy football leagues: this week's matchup, the rest of the league's matchups, and standings.
 * Opened from the pinned Fantasy Football entry in the chat list. Refreshes every time it comes to the front.
 */
class FantasyFootballActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(FantasyFootballActivity::class.java)

    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, FantasyFootballActivity::class.java)
    }
  }

  private val theme = DynamicNoActionBarTheme()

  private var state by mutableStateOf<FantasyState>(FantasyState.Loading)
  private var leagues: List<EspnLeagueEntry> = emptyList()

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    setContent {
      SignalTheme {
        FantasyFootballScreen(
          state = state,
          onLeagueSelected = { entry ->
            SignalStore.fantasy.selectedLeague = entry.leagueId
            load()
          },
          onSignInClick = { startActivity(EspnSignInActivity.createIntent(this)) },
          onRetryClick = { load() },
          onNavigationClick = { finish() }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    load()
  }

  private fun load() {
    if (state !is FantasyState.Loaded) {
      state = FantasyState.Loading
    }

    lifecycleScope.launch {
      state = withContext(Dispatchers.IO) {
        try {
          if (leagues.isEmpty()) {
            leagues = EspnApi.myLeagues()
          }
          val selected = leagues.firstOrNull { it.leagueId == SignalStore.fantasy.selectedLeague } ?: leagues.firstOrNull()
          if (selected == null) {
            FantasyState.Error("No ESPN fantasy football leagues found on this account.")
          } else {
            FantasyState.Loaded(leagues, EspnApi.league(selected))
          }
        } catch (e: EspnException) {
          Log.w(TAG, "Failed to load fantasy data", e)
          when (e) {
            is EspnException.NotSignedIn -> FantasyState.SignedOut
            is EspnException.Failed -> FantasyState.Error("Couldn't load ESPN right now.")
          }
        }
      }
    }
  }
}

sealed interface FantasyState {
  data object Loading : FantasyState
  data object SignedOut : FantasyState
  data class Error(val message: String) : FantasyState
  data class Loaded(val leagues: List<EspnLeagueEntry>, val league: EspnLeague) : FantasyState
}

@Composable
private fun FantasyFootballScreen(
  state: FantasyState,
  onLeagueSelected: (EspnLeagueEntry) -> Unit,
  onSignInClick: () -> Unit,
  onRetryClick: () -> Unit,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = "Fantasy Football",
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      when (state) {
        FantasyState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        FantasyState.SignedOut -> Message("Sign in to ESPN to see your leagues.", "Sign in", onSignInClick)
        is FantasyState.Error -> Message(state.message, "Try again", onRetryClick)
        is FantasyState.Loaded -> LeagueContent(state, onLeagueSelected)
      }
    }
  }
}

@Composable
private fun Message(text: String, action: String, onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
    TextButton(onClick = onClick) {
      Text(action)
    }
  }
}

@Composable
private fun LeagueContent(state: FantasyState.Loaded, onLeagueSelected: (EspnLeagueEntry) -> Unit) {
  val league = state.league
  val myTeamId = league.entry.teamId

  LazyColumn(modifier = Modifier.fillMaxSize()) {
    if (state.leagues.size > 1) {
      item {
        Row(
          modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          state.leagues.forEach { entry ->
            FilterChip(
              selected = entry.leagueId == league.entry.leagueId,
              onClick = { onLeagueSelected(entry) },
              label = { Text(entry.leagueName) }
            )
          }
        }
      }
    }

    item {
      SectionHeader("${league.name} · Week ${league.week}")
    }

    item {
      val matchup = league.myMatchup
      if (matchup == null) {
        Text(
          text = "No matchup this week.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(16.dp)
        )
      } else {
        MyMatchupCard(matchup, myTeamId)
      }
    }

    if (league.otherMatchups.isNotEmpty()) {
      item { SectionHeader("Around the league") }
      items(league.otherMatchups) { matchup -> MatchupRow(matchup) }
    }

    item {
      Dividers.Default()
      SectionHeader("Standings")
    }
    items(league.standings.withIndex().toList()) { (index, team) ->
      StandingRow(index + 1, team, isMine = team.id == myTeamId)
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
  )
}

@Composable
private fun MyMatchupCard(matchup: EspnMatchup, myTeamId: Int) {
  val homeIsMine = matchup.home.id == myTeamId
  val me = if (homeIsMine) matchup.home else matchup.away!!
  val mePoints = if (homeIsMine) matchup.homePoints else matchup.awayPoints
  val them = if (homeIsMine) matchup.away else matchup.home
  val themPoints = if (homeIsMine) matchup.awayPoints else matchup.homePoints

  Column(
    modifier = Modifier
      .padding(horizontal = 16.dp)
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
      .padding(16.dp)
  ) {
    ScoreLine(me.name, me.record, mePoints, leading = mePoints >= themPoints)
    Spacer(modifier = Modifier.padding(4.dp))
    if (them == null) {
      Text(text = "Bye week", style = MaterialTheme.typography.bodyMedium)
    } else {
      ScoreLine(them.name, them.record, themPoints, leading = themPoints > mePoints)
    }
  }
}

@Composable
private fun ScoreLine(name: String, record: String, points: Double, leading: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (leading) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(text = record, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
      text = formatPoints(points),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = if (leading) FontWeight.Bold else FontWeight.Normal
    )
  }
}

@Composable
private fun MatchupRow(matchup: EspnMatchup) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = matchup.home.name,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    Text(
      text = if (matchup.away == null) "Bye" else "${formatPoints(matchup.homePoints)} – ${formatPoints(matchup.awayPoints)}",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(horizontal = 12.dp)
    )
    Text(
      text = matchup.away?.name ?: "",
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
  }
}

@Composable
private fun StandingRow(rank: Int, team: EspnTeam, isMine: Boolean) {
  val weight = if (isMine) FontWeight.Bold else FontWeight.Normal
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(text = "$rank", style = MaterialTheme.typography.bodyMedium, fontWeight = weight, modifier = Modifier.width(32.dp))
    Text(
      text = team.name,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = weight,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    Text(text = team.record, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, modifier = Modifier.padding(horizontal = 12.dp))
    Text(text = formatPoints(team.pointsFor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private fun formatPoints(points: Double): String = String.format(Locale.US, "%.1f", points)

@DayNightPreviews
@Composable
private fun FantasyFootballScreenPreview() {
  val mine = EspnTeam(1, "Touchdown Town", "TDT", 5, 2, 0, 812.4, 2)
  val rival = EspnTeam(2, "Gridiron Gang", "GG", 4, 3, 0, 790.1, 4)
  val third = EspnTeam(3, "Blitz Bros", "BB", 6, 1, 0, 850.0, 1)
  val fourth = EspnTeam(4, "Fumble Squad", "FS", 1, 6, 0, 640.3, 8)
  val entry = EspnLeagueEntry(1, "Sunday League", 2026, 1)

  Previews.Preview {
    FantasyFootballScreen(
      state = FantasyState.Loaded(
        leagues = listOf(entry),
        league = EspnLeague(
          entry = entry,
          name = "Sunday League",
          week = 8,
          myMatchup = EspnMatchup(mine, 104.2, rival, 98.7),
          otherMatchups = listOf(EspnMatchup(third, 120.5, fourth, 88.0)),
          standings = listOf(third, mine, rival, fourth)
        )
      ),
      onLeagueSelected = {},
      onSignInClick = {},
      onRetryClick = {},
      onNavigationClick = {}
    )
  }
}
