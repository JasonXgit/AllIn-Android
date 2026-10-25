package org.thoughtcrime.securesms.googlevoice

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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * AllIn: the Google Voice settings screen. The feature stays hidden everywhere else until it is enabled here.
 */
class GoogleVoiceSettingsActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(GoogleVoiceSettingsActivity::class.java)

    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, GoogleVoiceSettingsActivity::class.java)
    }
  }

  private val theme = DynamicNoActionBarTheme()

  private var enabled by mutableStateOf(false)
  private var signedIn by mutableStateOf(false)
  private var connectionStatus by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    setContent {
      SignalTheme {
        GoogleVoiceSettingsScreen(
          enabled = enabled,
          signedIn = signedIn,
          connectionStatus = connectionStatus,
          onEnabledChanged = {
            SignalStore.googleVoice.enabled = it
            enabled = it
            if (it && signedIn) {
              checkConnection()
            }
          },
          onSignInClick = { startActivity(GoogleVoiceSignInActivity.createIntent(this)) },
          onSignOutClick = {
            GoogleVoiceSession.signOut()
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
    enabled = SignalStore.googleVoice.enabled
    signedIn = SignalStore.googleVoice.signedIn && GoogleVoiceSession.hasSession()
    if (enabled && signedIn) {
      checkConnection()
    }
  }

  private fun checkConnection() {
    connectionStatus = "Checking connection…"
    lifecycleScope.launch {
      connectionStatus = withContext(Dispatchers.IO) {
        try {
          val threads = GoogleVoiceApi.listThreads().threads
          val textThreads = threads.count { it.isText }
          Log.i(TAG, "Connection check: ${threads.size} threads, $textThreads text")
          "Connected · $textThreads recent text conversations"
        } catch (e: GoogleVoiceException) {
          Log.w(TAG, "Connection check failed", e)
          when (e) {
            is GoogleVoiceException.NotSignedIn -> "Signed out by Google. Sign in again."
            is GoogleVoiceException.Failed -> "Couldn't reach Google Voice"
          }
        }
      }
    }
  }
}

@Composable
private fun GoogleVoiceSettingsScreen(
  enabled: Boolean,
  signedIn: Boolean,
  connectionStatus: String?,
  onEnabledChanged: (Boolean) -> Unit,
  onSignInClick: () -> Unit,
  onSignOutClick: () -> Unit,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = "Google Voice",
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(modifier = Modifier.padding(paddingValues)) {
      item {
        Rows.ToggleRow(
          checked = enabled,
          text = "Use Google Voice",
          label = "Show your Google Voice texts in your chat list. This works through the Google Voice website, so it may stop working if Google changes it.",
          onCheckChanged = onEnabledChanged
        )
      }

      if (enabled) {
        item {
          Dividers.Default()
        }

        item {
          if (signedIn) {
            Rows.TextRow(
              text = connectionStatus ?: "Signed in",
              label = "Tap to sign out of Google Voice",
              onClick = onSignOutClick
            )
          } else {
            Rows.TextRow(
              text = "Sign in to Google Voice",
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
private fun GoogleVoiceSettingsScreenPreview() {
  Previews.Preview {
    GoogleVoiceSettingsScreen(
      enabled = true,
      signedIn = false,
      connectionStatus = null,
      onEnabledChanged = {},
      onSignInClick = {},
      onSignOutClick = {},
      onNavigationClick = {}
    )
  }
}
