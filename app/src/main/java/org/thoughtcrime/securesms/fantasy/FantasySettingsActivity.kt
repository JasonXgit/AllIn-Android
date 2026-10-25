package org.thoughtcrime.securesms.fantasy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * AllIn: settings for the Fantasy Football entry. It is pinned to the top of the chat list only while enabled.
 */
class FantasySettingsActivity : BaseActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, FantasySettingsActivity::class.java)
    }
  }

  private val theme = DynamicNoActionBarTheme()

  private var enabled by mutableStateOf(false)
  private var signedIn by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    setContent {
      SignalTheme {
        FantasySettingsScreen(
          enabled = enabled,
          signedIn = signedIn,
          onEnabledChanged = {
            SignalStore.fantasy.enabled = it
            enabled = it
          },
          onSignInClick = { startActivity(EspnSignInActivity.createIntent(this)) },
          onOpenClick = { startActivity(FantasyFootballActivity.createIntent(this)) },
          onSignOutClick = {
            signOut()
            signedIn = false
          },
          onNavigationClick = { finish() }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    enabled = SignalStore.fantasy.enabled
    signedIn = SignalStore.fantasy.signedIn && EspnApi.hasSession()
  }

  private fun signOut() {
    val cookies = CookieManager.getInstance()
    for (name in listOf("espn_s2", "SWID")) {
      cookies.setCookie("https://fantasy.espn.com", "$name=; Max-Age=0; Domain=.espn.com; Path=/")
    }
    cookies.flush()
    SignalStore.fantasy.signedIn = false
  }
}

@Composable
private fun FantasySettingsScreen(
  enabled: Boolean,
  signedIn: Boolean,
  onEnabledChanged: (Boolean) -> Unit,
  onSignInClick: () -> Unit,
  onOpenClick: () -> Unit,
  onSignOutClick: () -> Unit,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = "Fantasy Football",
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(modifier = Modifier.padding(paddingValues)) {
      item {
        Rows.ToggleRow(
          checked = enabled,
          text = "Show Fantasy Football",
          label = "Pins your ESPN league's matchups and standings to the top of your chat list.",
          onCheckChanged = onEnabledChanged
        )
      }

      if (enabled) {
        item {
          Dividers.Default()
        }

        if (signedIn) {
          item {
            Rows.TextRow(
              text = "Open Fantasy Football",
              onClick = onOpenClick
            )
          }

          item {
            Rows.TextRow(
              text = "Signed in to ESPN",
              label = "Tap to sign out",
              onClick = onSignOutClick
            )
          }
        } else {
          item {
            Rows.TextRow(
              text = "Sign in to ESPN",
              label = "Needed to see private leagues",
              onClick = onSignInClick
            )
          }
        }
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun FantasySettingsScreenPreview() {
  Previews.Preview {
    FantasySettingsScreen(
      enabled = true,
      signedIn = true,
      onEnabledChanged = {},
      onSignInClick = {},
      onOpenClick = {},
      onSignOutClick = {},
      onNavigationClick = {}
    )
  }
}
