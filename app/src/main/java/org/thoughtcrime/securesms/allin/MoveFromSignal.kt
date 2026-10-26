package org.thoughtcrime.securesms.allin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.registration.ui.RegistrationIntents

/**
 * AllIn: helps people replace the real Signal app with AllIn on the same phone.
 *
 * Android doesn't let one app read another's data, so Signal itself has to make a backup. Before AllIn's own
 * registration starts, [MoveFromSignalActivity] explains how (and opens Signal); Signal's registration flow, which
 * AllIn inherits, then restores that backup. Once AllIn is set up, [offerUninstall] offers to remove Signal.
 */
object MoveFromSignal {

  const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"

  private const val PREFS = "allin_move_from_signal"
  private const val KEY_WIZARD_SHOWN = "wizard_shown"
  private const val KEY_UNINSTALL_ASKED = "uninstall_asked"

  fun isSignalInstalled(context: Context): Boolean {
    return try {
      context.packageManager.getPackageInfo(SIGNAL_PACKAGE, 0)
      true
    } catch (e: PackageManager.NameNotFoundException) {
      false
    }
  }

  /** True once, before registration, when Signal is on this phone. */
  @JvmStatic
  fun shouldShowWizard(context: Context): Boolean {
    return isSignalInstalled(context) && !prefs(context).getBoolean(KEY_WIZARD_SHOWN, false)
  }

  /** Once AllIn is registered, asks (one time) whether to uninstall Signal. */
  @JvmStatic
  fun offerUninstall(activity: Activity) {
    if (!isSignalInstalled(activity) || prefs(activity).getBoolean(KEY_UNINSTALL_ASKED, false)) return
    prefs(activity).edit().putBoolean(KEY_UNINSTALL_ASKED, true).apply()

    AlertDialog.Builder(activity)
      .setTitle("Remove Signal?")
      .setMessage("AllIn is set up, so you don't need the Signal app on this phone any more. Check your chats came over first.")
      .setPositiveButton("Uninstall Signal") { _, _ ->
        activity.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$SIGNAL_PACKAGE")))
      }
      .setNegativeButton("Not now", null)
      .show()
  }

  internal fun markWizardShown(context: Context) {
    prefs(context).edit().putBoolean(KEY_WIZARD_SHOWN, true).apply()
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class MoveFromSignalActivity : BaseActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, MoveFromSignalActivity::class.java)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SignalTheme {
        MoveFromSignalScreen(
          onOpenSignal = {
            packageManager.getLaunchIntentForPackage(MoveFromSignal.SIGNAL_PACKAGE)?.let { startActivity(it) }
          },
          onContinue = {
            MoveFromSignal.markWizardShown(this)
            startActivity(RegistrationIntents.newIntentForNewRegistration(this))
            finish()
          }
        )
      }
    }
  }
}

@Composable
private fun MoveFromSignalScreen(onOpenSignal: () -> Unit, onContinue: () -> Unit) {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier
        .systemBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(24.dp)
    ) {
      Text("Move from Signal to AllIn", style = MaterialTheme.typography.headlineSmall)
      Text(
        "Signal is on this phone. AllIn can bring your chats over, then Signal can be removed. It takes a few minutes.",
        style = MaterialTheme.typography.bodyLarge
      )

      Step(
        "1. Make a backup in Signal",
        "Open Signal, then go to Settings › Backups › On-device backups (older versions: Settings › Chats › Chat backups). " +
          "Turn backups on, choose a folder, and write down the code Signal shows you. Tap \"Back up now\" (or \"Create backup\") " +
          "and wait until it finishes."
      )
      OutlinedButton(onClick = onOpenSignal, modifier = Modifier.fillMaxWidth()) { Text("Open Signal") }

      Step(
        "2. Set up AllIn",
        "Come back here and tap Continue. Enter your phone number. When AllIn asks about your old messages, " +
          "choose to restore from a backup on this phone, pick the backup, and enter the code."
      )
      Step(
        "3. Remove Signal",
        "Once your chats are in AllIn, AllIn will offer to uninstall Signal. " +
          "Setting up AllIn with your number signs Signal out on this phone, so make the backup first."
      )

      Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
      Text(
        "No backup? Tap Continue anyway to start AllIn without your old messages.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun Step(title: String, body: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
