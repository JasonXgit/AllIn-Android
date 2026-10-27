package org.thoughtcrime.securesms.allin.family

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * AllIn: Settings › Family. Turns Find My Device and location sharing on or off and walks through the permissions
 * Android needs for it (location "Allow all the time", notifications, full-screen ring alert).
 */
class FamilySettingsActivity : BaseActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, FamilySettingsActivity::class.java)
  }

  private val theme = DynamicNoActionBarTheme()

  private var enabled by mutableStateOf(false)
  private var location by mutableStateOf("none")
  private var notifications by mutableStateOf(true)
  private var fullScreen by mutableStateOf(true)
  private var status by mutableStateOf("")

  private val requestForeground = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    refresh()
    if (FamilyPermissions.hasForeground(this)) askNext()
  }
  private val requestSingle = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
    refresh()
    askNext()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    setContent {
      SignalTheme {
        Scaffolds.Settings(
          title = "Family",
          onNavigationClick = { finish() },
          navigationIcon = SignalIcons.ArrowStart.imageVector
        ) { paddingValues ->
          LazyColumn(modifier = Modifier.padding(paddingValues)) {
            item {
              Rows.ToggleRow(
                checked = enabled,
                text = "Find My Device & location sharing",
                label = "Lets your family's AllIn bot see where this phone is and ring it if it's lost. " +
                  "It only takes orders from the family bot (${Family.controller}).",
                onCheckChanged = ::onToggle
              )
            }

            if (enabled) {
              item { Dividers.Default() }
              item {
                Rows.TextRow(
                  text = "Location: " + when (location) {
                    "always" -> "allowed all the time"
                    "while using" -> "only while using AllIn"
                    else -> "not allowed"
                  },
                  label = if (location == "always") "Good" else "Tap and choose \"Allow all the time\", or family can't see this phone when AllIn is closed",
                  onClick = ::askNext
                )
              }
              if (!notifications) {
                item {
                  Rows.TextRow(text = "Notifications are off", label = "Tap to allow them, so ringing shows a Stop button", onClick = ::askNext)
                }
              }
              if (!fullScreen) {
                item {
                  Rows.TextRow(text = "Full-screen ring alert is off", label = "Tap to allow it, so the Stop screen shows over the lock screen", onClick = ::openFullScreenSettings)
                }
              }
              item {
                Rows.TextRow(
                  text = "Send a check-in now",
                  label = status,
                  onClick = {
                    status = "Sending…"
                    SignalExecutors.BOUNDED.execute {
                      Family.sendHello(this@FamilySettingsActivity)
                      FamilyLocationService.locateNow(this@FamilySettingsActivity)
                      runOnUiThread { refresh() }
                    }
                  }
                )
              }
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    refresh()
    Family.update(this)
  }

  private fun refresh() {
    enabled = Family.enabled
    location = FamilyPermissions.locationLevel(this)
    notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fullScreen = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    val last = Family.lastReportAt
    status = Family.status.ifBlank {
      if (last > 0) "Last check-in " + DateUtils.getRelativeTimeSpanString(last) else "Not checked in yet"
    }
  }

  private fun onToggle(on: Boolean) {
    if (on && !SignalStore.account.isPrimaryDevice) {
      status = "Only works on the phone where AllIn is your main Signal app."
      return
    }
    if (on) {
      // Google Play requires this explanation, in the app, before asking for background location.
      MaterialAlertDialogBuilder(this)
        .setTitle("Share this phone's location?")
        .setMessage(
          "AllIn collects this phone's location to let your family find it and see where it is, even when the app is " +
            "closed or not in use. It's sent only to your family's bot (${Family.controller}) over Signal, end-to-end " +
            "encrypted, and is used for nothing else. You can turn this off here at any time."
        )
        .setPositiveButton("Continue") { _, _ -> applyEnabled(true) }
        .setNegativeButton("Not now", null)
        .show()
    } else {
      applyEnabled(false)
    }
  }

  private fun applyEnabled(on: Boolean) {
    Family.enabled = on
    refresh()
    if (on) {
      askNext()
      SignalExecutors.BOUNDED.execute { Family.sendHello(this) }
    }
    Family.update(this)
  }

  /** Asks for the next missing permission, one at a time, as Android requires. */
  private fun askNext() {
    when {
      !FamilyPermissions.hasForeground(this) ->
        requestForeground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
      Build.VERSION.SDK_INT >= 33 && !notifications ->
        requestSingle.launch(Manifest.permission.POST_NOTIFICATIONS)
      !FamilyPermissions.hasBackground(this) && Build.VERSION.SDK_INT >= 29 ->
        // Android 11+ shows this as its own settings page: choose "Allow all the time".
        requestSingle.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
      else -> Family.update(this)
    }
  }

  private fun openFullScreenSettings() {
    if (Build.VERSION.SDK_INT >= 34) {
      startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
    }
  }
}

/** Restarts location sharing after the phone reboots or AllIn updates. */
class FamilyBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      Family.update(context)
    }
  }
}
