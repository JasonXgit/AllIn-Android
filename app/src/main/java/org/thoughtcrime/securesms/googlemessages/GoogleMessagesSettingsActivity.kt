package org.thoughtcrime.securesms.googlemessages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * AllIn: the phone texts settings screen: turn the feature on, pair with Google Messages, notifications, and
 * whether AllIn Desktop may use it.
 */
class GoogleMessagesSettingsActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(GoogleMessagesSettingsActivity::class.java)

    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, GoogleMessagesSettingsActivity::class.java)
  }

  /** Where pairing is, for the screen. */
  sealed interface PairState {
    data object Idle : PairState
    data object Starting : PairState
    data class Emoji(val emoji: String) : PairState
    data class Failed(val message: String) : PairState
  }

  private val theme = DynamicNoActionBarTheme()

  private var enabled by mutableStateOf(false)
  private var paired by mutableStateOf(false)
  private var notifications by mutableStateOf(true)
  private var relay by mutableStateOf(true)
  private var status by mutableStateOf(GoogleMessages.status)
  private var pairState by mutableStateOf<PairState>(PairState.Idle)

  private var pairJob: Job? = null
  private var wantPairAfterSignIn = false

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    setContent {
      SignalTheme {
        PhoneTextsSettingsScreen(
          enabled = enabled,
          paired = paired,
          notifications = notifications,
          relay = relay,
          status = status,
          pairState = pairState,
          onEnabledChanged = {
            SignalStore.googleMessages.enabled = it
            enabled = it
            GoogleMessages.update()
          },
          onPairClick = ::onPairClick,
          onUnpairClick = {
            GoogleMessages.unpair()
            paired = false
            pairState = PairState.Idle
          },
          onNotificationsChanged = {
            SignalStore.googleMessages.notificationsEnabled = it
            notifications = it
          },
          onRelayChanged = {
            SignalStore.googleMessages.relayToDesktop = it
            relay = it
          },
          onNavigationClick = { finish() }
        )
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
          status = GoogleMessages.status
          delay(2_000)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    enabled = SignalStore.googleMessages.enabled
    paired = SignalStore.googleMessages.paired
    notifications = SignalStore.googleMessages.notificationsEnabled
    relay = SignalStore.googleMessages.relayToDesktop
    if (wantPairAfterSignIn) {
      wantPairAfterSignIn = false
      if (GoogleMessagesSignInActivity.hasSession()) startPairing()
    }
  }

  private fun onPairClick() {
    if (GoogleMessagesSignInActivity.hasSession()) {
      startPairing()
    } else {
      wantPairAfterSignIn = true
      startActivity(GoogleMessagesSignInActivity.createIntent(this))
    }
  }

  private fun startPairing() {
    if (pairJob?.isActive == true) return
    pairState = PairState.Starting
    pairJob = lifecycleScope.launch {
      val auth = GmAuth.create()
      val client = GmClient(auth) { }
      try {
        val (emoji, pairing) = withContext(Dispatchers.IO) { client.startGaiaPairing() }
        pairState = PairState.Emoji(emoji)
        withContext(Dispatchers.IO) { client.finishGaiaPairing(pairing) }
        client.close()
        // Google Messages needs a moment to save the pairing before we reconnect.
        delay(2_000)
        GoogleMessages.onPaired(auth)
        paired = true
        enabled = true
        pairState = PairState.Idle
      } catch (e: CancellationException) {
        client.close()
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Pairing failed", e)
        client.close()
        pairState = PairState.Failed(
          when (e) {
            is GmException.NoPhone -> "Couldn't find Google Messages on this Google account. In Google Messages, open Settings › Device pairing and make sure you're signed in to the same Google account."
            is GmException.PairingFailed -> e.message ?: "Pairing didn't finish."
            is GmException.LoggedOut -> "Google signed you out. Tap to sign in again."
            else -> "Couldn't reach Google Messages on your phone. Make sure it's set as your texting app and try again."
          }
        )
      }
    }
  }
}

@Composable
private fun PhoneTextsSettingsScreen(
  enabled: Boolean,
  paired: Boolean,
  notifications: Boolean,
  relay: Boolean,
  status: String,
  pairState: GoogleMessagesSettingsActivity.PairState,
  onEnabledChanged: (Boolean) -> Unit,
  onPairClick: () -> Unit,
  onUnpairClick: () -> Unit,
  onNotificationsChanged: (Boolean) -> Unit,
  onRelayChanged: (Boolean) -> Unit,
  onNavigationClick: () -> Unit
) {
  Scaffolds.Settings(
    title = "Phone texts",
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(modifier = Modifier.padding(paddingValues)) {
      item {
        Rows.ToggleRow(
          checked = enabled,
          text = "Show phone texts",
          label = "Show your SMS, MMS and RCS texts in your chat list, and reply from AllIn. Needs Google Messages as your texting app. This works the way messages.google.com does, so it may stop working if Google changes it.",
          onCheckChanged = onEnabledChanged
        )
      }

      if (enabled) {
        item { Dividers.Default() }

        if (paired) {
          item {
            Rows.TextRow(text = status, label = "Tap to unpair from Google Messages", onClick = onUnpairClick)
          }
          item {
            Rows.ToggleRow(checked = notifications, text = "Notifications", label = "Notify for new texts in AllIn", onCheckChanged = onNotificationsChanged)
          }
          item {
            Rows.ToggleRow(
              checked = relay,
              text = "Use on AllIn Desktop",
              label = "Let AllIn on your computer show these texts and send replies through this phone, end-to-end encrypted through Signal.",
              onCheckChanged = onRelayChanged
            )
          }
        } else {
          when (pairState) {
            GoogleMessagesSettingsActivity.PairState.Idle -> item {
              Rows.TextRow(text = "Pair with Google Messages", label = "Sign in with the Google account Google Messages uses on this phone", onClick = onPairClick)
            }
            GoogleMessagesSettingsActivity.PairState.Starting -> item {
              Rows.TextRow(text = "Contacting Google Messages…")
            }
            is GoogleMessagesSettingsActivity.PairState.Emoji -> item {
              Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(text = pairState.emoji, fontSize = 72.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(
                  text = "Google Messages on your phone is asking to pair. Open it and tap this emoji.",
                  style = MaterialTheme.typography.bodyLarge,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )
              }
            }
            is GoogleMessagesSettingsActivity.PairState.Failed -> item {
              Rows.TextRow(text = "Try pairing again", label = pairState.message, onClick = onPairClick)
            }
          }
        }
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun PhoneTextsSettingsScreenPreview() {
  Previews.Preview {
    PhoneTextsSettingsScreen(
      enabled = true,
      paired = false,
      notifications = true,
      relay = true,
      status = "",
      pairState = GoogleMessagesSettingsActivity.PairState.Emoji("🦊"),
      onEnabledChanged = {},
      onPairClick = {},
      onUnpairClick = {},
      onNotificationsChanged = {},
      onRelayChanged = {},
      onNavigationClick = {}
    )
  }
}
