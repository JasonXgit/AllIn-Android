package org.thoughtcrime.securesms.fantasy

import androidx.annotation.WorkerThread
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.websession.WebSignInActivity
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AllIn: reads ESPN fantasy football leagues with the cookies from [EspnSignInActivity].
 *
 * ESPN's fantasy API is undocumented but stable; it is what fantasy.espn.com itself calls. Private leagues need the
 * espn_s2 and SWID cookies. Parsing is lenient so a renamed field degrades one value instead of the whole screen.
 */
object EspnApi {

  private val TAG = Log.tag(EspnApi::class.java)

  private const val FANTASY_URL = "https://fantasy.espn.com"
  private const val FAN_API = "https://fan.api.espn.com/apis/v2/fans"
  private const val LEAGUE_API = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons"

  /** ESPN's game ID for fantasy football, as used in a fan profile's entries. */
  private const val FOOTBALL_GAME_ID = 1

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()
  }

  fun hasSession(): Boolean = WebSignInActivity.cookie(FANTASY_URL, "espn_s2") != null

  /** The user's fantasy football teams, one per league, for the newest season they have. */
  @WorkerThread
  @Throws(EspnException::class)
  fun myLeagues(): List<EspnLeagueEntry> {
    val swid = WebSignInActivity.cookie(FANTASY_URL, "SWID") ?: throw EspnException.NotSignedIn()
    val url = FAN_API.toHttpUrl().newBuilder()
      .addPathSegment(swid)
      .addQueryParameter("displayHiddenPrefs", "true")
      .addQueryParameter("context", "fantasy")
      .addQueryParameter("source", "espncom-fantasy")
      .addQueryParameter("lang", "en")
      .addQueryParameter("region", "us")
      .build()
      .toString()

    val preferences = get(url).optJSONArray("preferences") ?: JSONArray()
    val entries = (0 until preferences.length())
      .mapNotNull { preferences.optJSONObject(it)?.optJSONObject("metaData")?.optJSONObject("entry") }
      .filter { it.optInt("gameId") == FOOTBALL_GAME_ID }
      .flatMap { entry ->
        val groups = entry.optJSONArray("groups") ?: JSONArray()
        (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.map { group ->
          EspnLeagueEntry(
            leagueId = group.optLong("groupId"),
            leagueName = group.optString("groupName"),
            season = entry.optInt("seasonId"),
            teamId = entry.optInt("entryId")
          )
        }
      }
      .filter { it.leagueId != 0L }
      // A league can appear under more than one fan preference (e.g. the team and the league are both followed).
      .distinctBy { it.leagueId to it.season }

    val newestSeason = entries.maxOfOrNull { it.season } ?: return emptyList()
    return entries.filter { it.season == newestSeason }.also {
      Log.i(TAG, "Found ${preferences.length()} fan preferences, ${it.size} football leagues for $newestSeason")
    }
  }

  /** Teams, this week's matchups and standings for one league. */
  @WorkerThread
  @Throws(EspnException::class)
  fun league(entry: EspnLeagueEntry): EspnLeague {
    val url = "$LEAGUE_API/${entry.season}/segments/0/leagues/${entry.leagueId}".toHttpUrl().newBuilder()
      .addQueryParameter("view", "mTeam")
      .addQueryParameter("view", "mMatchupScore")
      .addQueryParameter("view", "mScoreboard")
      .addQueryParameter("view", "mSettings")
      .build()
      .toString()

    val json = get(url)
    val week = json.optJSONObject("status")?.optInt("currentMatchupPeriod")?.takeIf { it > 0 } ?: json.optInt("scoringPeriodId", 1)

    val teamsJson = json.optJSONArray("teams") ?: JSONArray()
    val teams = (0 until teamsJson.length()).mapNotNull { teamsJson.optJSONObject(it) }.map { team ->
      val overall = team.optJSONObject("record")?.optJSONObject("overall")
      val name = team.optString("name").ifBlank { "${team.optString("location")} ${team.optString("nickname")}".trim() }
      EspnTeam(
        id = team.optInt("id"),
        name = name.ifBlank { team.optString("abbrev") },
        abbrev = team.optString("abbrev"),
        wins = overall?.optInt("wins") ?: 0,
        losses = overall?.optInt("losses") ?: 0,
        ties = overall?.optInt("ties") ?: 0,
        pointsFor = overall?.optDouble("pointsFor", 0.0) ?: 0.0,
        playoffSeed = team.optInt("playoffSeed")
      )
    }
    val teamsById = teams.associateBy { it.id }

    val scheduleJson = json.optJSONArray("schedule") ?: JSONArray()
    val matchups = (0 until scheduleJson.length())
      .mapNotNull { scheduleJson.optJSONObject(it) }
      .filter { it.optInt("matchupPeriodId") == week }
      .mapNotNull { matchup ->
        val home = matchup.optJSONObject("home") ?: return@mapNotNull null
        val away = matchup.optJSONObject("away")
        EspnMatchup(
          home = teamsById[home.optInt("teamId")] ?: return@mapNotNull null,
          homePoints = points(home),
          away = away?.let { teamsById[it.optInt("teamId")] },
          awayPoints = away?.let { points(it) } ?: 0.0
        )
      }

    val standings = teams.sortedWith(
      compareBy<EspnTeam> { if (it.playoffSeed > 0) it.playoffSeed else Int.MAX_VALUE }
        .thenByDescending { it.wins }
        .thenByDescending { it.pointsFor }
    )

    Log.i(TAG, "League loaded: ${teams.size} teams, week $week, ${matchups.size} matchups")
    return EspnLeague(
      entry = entry,
      name = json.optJSONObject("settings")?.optString("name")?.ifBlank { null } ?: entry.leagueName,
      week = week,
      myMatchup = matchups.firstOrNull { it.home.id == entry.teamId || it.away?.id == entry.teamId },
      otherMatchups = matchups.filterNot { it.home.id == entry.teamId || it.away?.id == entry.teamId },
      standings = standings
    )
  }

  /** Live points while games are on, final points otherwise. */
  private fun points(side: JSONObject): Double {
    return if (side.has("totalPointsLive")) side.optDouble("totalPointsLive", 0.0) else side.optDouble("totalPoints", 0.0)
  }

  private fun get(url: String): JSONObject {
    val espnS2 = WebSignInActivity.cookie(FANTASY_URL, "espn_s2") ?: throw EspnException.NotSignedIn()
    val swid = WebSignInActivity.cookie(FANTASY_URL, "SWID") ?: throw EspnException.NotSignedIn()

    val request = Request.Builder()
      .url(url)
      .header("Cookie", "espn_s2=$espnS2; SWID=$swid")
      .header("User-Agent", WebSignInActivity.chromeUserAgent(AppDependencies.application))
      .header("Accept", "application/json")
      .build()

    try {
      client.newCall(request).execute().use { response ->
        if (response.code == 401 || response.code == 403) {
          throw EspnException.NotSignedIn()
        }
        if (!response.isSuccessful) {
          throw EspnException.Failed("${request.url.encodedPath} returned ${response.code}")
        }
        return JSONObject(response.body.string())
      }
    } catch (e: IOException) {
      throw EspnException.Failed("ESPN request failed: ${e.javaClass.simpleName}", e)
    } catch (e: JSONException) {
      throw EspnException.Failed("ESPN returned unexpected data", e)
    }
  }
}

data class EspnLeagueEntry(val leagueId: Long, val leagueName: String, val season: Int, val teamId: Int)

data class EspnTeam(
  val id: Int,
  val name: String,
  val abbrev: String,
  val wins: Int,
  val losses: Int,
  val ties: Int,
  val pointsFor: Double,
  val playoffSeed: Int
) {
  val record: String get() = if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"
}

/** [away] is null during a bye week. */
data class EspnMatchup(val home: EspnTeam, val homePoints: Double, val away: EspnTeam?, val awayPoints: Double)

data class EspnLeague(
  val entry: EspnLeagueEntry,
  val name: String,
  val week: Int,
  val myMatchup: EspnMatchup?,
  val otherMatchups: List<EspnMatchup>,
  val standings: List<EspnTeam>
)

sealed class EspnException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class NotSignedIn : EspnException("Not signed in to ESPN")
  class Failed(message: String, cause: Throwable? = null) : EspnException(message, cause)
}
