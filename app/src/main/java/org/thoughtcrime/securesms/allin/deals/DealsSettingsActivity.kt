package org.thoughtcrime.securesms.allin.deals

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import org.thoughtcrime.securesms.websession.WebProfiles

/**
 * AllIn: settings for the Deals entry, which is pinned above the chat list while enabled and signed in.
 */
class DealsSettingsActivity : BaseActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, DealsSettingsActivity::class.java)
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
        DealsSettingsScreen(
          enabled = enabled,
          signedIn = signedIn,
          server = DealsApi.baseUrl.removePrefix("https://"),
          onEnabledChanged = {
            SignalStore.deals.enabled = it
            enabled = it
            if (it) DealsSync.requestSync(force = true)
          },
          onSignInClick = { startActivity(DealsSignInActivity.createIntent(this)) },
          onOpenClick = { startActivity(DealsActivity.createIntent(this)) },
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
    enabled = SignalStore.deals.enabled
    signedIn = SignalStore.deals.signedIn && DealsApi.hasSession()
  }

  private fun signOut() {
    val cookies = WebProfiles.cookieManager(null)
    cookies.setCookie(DealsApi.baseUrl, "${DealsApi.SESSION_COOKIE}=; Max-Age=0; Path=/")
    cookies.flush()
    SignalStore.deals.signedIn = false
    DealsStore.clear()
  }
}

@Composable
private fun DealsSettingsScreen(
  enabled: Boolean,
  signedIn: Boolean,
  server: String,
  onEnabledChanged: (Boolean) -> Unit,
  onSignInClick: () -> Unit,
  onOpenClick: () -> Unit,
  onSignOutClick: () -> Unit,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = "Deals",
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(modifier = Modifier.padding(paddingValues)) {
      item {
        Rows.ToggleRow(
          checked = enabled,
          text = "Show Deals",
          label = "Pins your Deal Reader ($server) to the top of your chat list. Deals are saved on the phone, so you can " +
            "go through them and star the ones you want offline; changes are sent when you're back online.",
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
              text = "Open Deals",
              onClick = onOpenClick
            )
          }

          item {
            Rows.TextRow(
              text = "Signed in to $server",
              label = "Tap to sign out (removes the deals saved on this phone)",
              onClick = onSignOutClick
            )
          }
        } else {
          item {
            Rows.TextRow(
              text = "Sign in to $server",
              label = "Use your Deal Reader username and password",
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
private fun DealsSettingsScreenPreview() {
  Previews.Preview {
    DealsSettingsScreen(
      enabled = true,
      signedIn = true,
      server = "rss.jason.ky",
      onEnabledChanged = {},
      onSignInClick = {},
      onOpenClick = {},
      onSignOutClick = {},
      onNavigationClick = {}
    )
  }
}
