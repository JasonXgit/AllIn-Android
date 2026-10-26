package org.thoughtcrime.securesms.allin.family

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import java.util.concurrent.TimeUnit

/**
 * AllIn: Family (Find My Device). When the user turns it on, this phone checks in with a controller (JasonBot, the
 * family's Signal bot on the widgets server), reports its location, and obeys the controller's commands: ring
 * (loudly, even on silent), stop, and locate now.
 *
 * Everything travels as ordinary Signal messages whose text starts with [PREFIX] followed by JSON. They are never
 * shown: incoming ones from the controller are handled in [handleIncoming] before Signal stores them, and outgoing
 * ones are sent with [org.whispersystems.signalservice.api.SignalServiceMessageSender.sendDataMessageWithoutSync],
 * which skips the database and this account's other devices.
 *
 * Messages (JSON "t" field):
 *  - phone -> controller: hello {name, app, model, battery, charging, location}, loc {lat, lon, acc, at, speed,
 *    battery, charging}, ack {cmd, ok, note}
 *  - controller -> phone: cmd {cmd: ring | stop | locate | hello}
 */
object Family {

  private val TAG = Log.tag(Family::class.java)

  /** An invisible separator, then a tag no person would type. */
  const val PREFIX = "⁣AllIn:"

  /** JasonBot (signal-cli on the widgets server). */
  const val DEFAULT_CONTROLLER = "+18594288765"

  private const val PREFS = "allin_family"
  private val HELLO_EVERY_MS = TimeUnit.HOURS.toMillis(6)

  private fun prefs(context: Context = AppDependencies.application) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  var enabled: Boolean
    get() = prefs().getBoolean("enabled", false)
    set(value) = prefs().edit().putBoolean("enabled", value).apply()

  var controller: String
    get() = prefs().getString("controller", DEFAULT_CONTROLLER) ?: DEFAULT_CONTROLLER
    set(value) = prefs().edit().putString("controller", value).apply()

  var lastHelloAt: Long
    get() = prefs().getLong("last_hello", 0)
    set(value) = prefs().edit().putLong("last_hello", value).apply()

  var lastReportAt: Long
    get() = prefs().getLong("last_report", 0)
    set(value) = prefs().edit().putLong("last_report", value).apply()

  /** Short status for the settings screen, e.g. "Last check-in 2 min ago". */
  var status: String
    get() = prefs().getString("status", "") ?: ""
    set(value) = prefs().edit().putString("status", value).apply()

  /** Only the main device of a registered account takes part (linked devices would double-report). */
  fun isActive(): Boolean = enabled && SignalStore.account.isRegistered && SignalStore.account.isPrimaryDevice

  /** Starts or stops the location service to match the setting. Safe to call any time. */
  @JvmStatic
  fun update(context: Context) {
    if (isActive()) {
      FamilyLocationService.start(context)
    } else {
      FamilyLocationService.stop(context)
    }
  }

  /**
   * Called for every incoming 1:1 text before it's stored. Returns true if it was a Family message from the
   * controller (handled here and NOT shown).
   */
  @JvmStatic
  fun handleIncoming(context: Context, sender: Recipient, body: String?): Boolean {
    if (body == null || !body.startsWith(PREFIX)) return false
    if (sender.e164.orElse(null) != controller) {
      // Looks like a Family message but not from our controller: drop it rather than show gibberish.
      Log.w(TAG, "Ignoring a Family message from someone who isn't the controller.")
      return true
    }
    if (!isActive()) {
      Log.i(TAG, "Family is off; ignoring a command.")
      return true
    }

    val json = try {
      JSONObject(body.removePrefix(PREFIX))
    } catch (e: Exception) {
      Log.w(TAG, "Bad Family message", e)
      return true
    }
    if (json.optString("t") != "cmd") return true

    val command = json.optString("cmd")
    Log.i(TAG, "Command: $command")
    when (command) {
      "ring" -> {
        FamilyRinger.start(context)
        sendAsync(JSONObject().put("t", "ack").put("cmd", "ring").put("ok", true))
      }
      "stop" -> {
        FamilyRinger.stop(context)
        sendAsync(JSONObject().put("t", "ack").put("cmd", "stop").put("ok", true))
      }
      "locate" -> FamilyLocationService.locateNow(context)
      "hello" -> SignalExecutors.BOUNDED.execute { sendHello(context) }
      else -> sendAsync(JSONObject().put("t", "ack").put("cmd", command).put("ok", false).put("note", "unknown command"))
    }
    return true
  }

  /** Who this phone is, for the server's device list. */
  @WorkerThread
  fun sendHello(context: Context) {
    val (battery, charging) = battery(context)
    val json = JSONObject()
      .put("t", "hello")
      .put("name", Recipient.self().getDisplayName(context))
      .put("app", BuildConfig.VERSION_NAME)
      .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
      .put("android", Build.VERSION.RELEASE)
      .put("battery", battery)
      .put("charging", charging)
      .put("location", FamilyPermissions.locationLevel(context))
    if (send(json)) {
      lastHelloAt = System.currentTimeMillis()
    }
  }

  /** Sends a hello now and then, so the server sees the app version and permissions stay current. */
  @WorkerThread
  fun helloIfDue(context: Context) {
    if (System.currentTimeMillis() - lastHelloAt > HELLO_EVERY_MS) {
      sendHello(context)
    }
  }

  fun sendAsync(json: JSONObject) {
    SignalExecutors.BOUNDED.execute { send(json) }
  }

  /** Sends a hidden Family message to the controller. Returns true on success. */
  @WorkerThread
  fun send(json: JSONObject): Boolean {
    val context = AppDependencies.application
    return try {
      var recipient = Recipient.external(controller) ?: return false
      if (!recipient.hasServiceId) {
        ContactDiscovery.refresh(context, recipient, false)
        recipient = recipient.fresh()
      }
      if (!recipient.hasServiceId) {
        status = "Can't find the family bot on Signal"
        return false
      }

      val message = SignalServiceDataMessage.newBuilder()
        .withTimestamp(System.currentTimeMillis())
        .withBody(PREFIX + json.toString())
        .build()
      AppDependencies.signalServiceMessageSender.sendDataMessageWithoutSync(
        RecipientUtil.toSignalServiceAddress(context, recipient),
        SealedSenderAccessUtil.getSealedSenderAccessFor(recipient),
        message
      )
      lastReportAt = System.currentTimeMillis()
      status = ""
      true
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't send a Family message", e)
      status = "Couldn't reach the family bot. Will retry."
      false
    }
  }

  fun battery(context: Context): Pair<Int, Boolean> {
    val intent: Intent? = ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to plugged
  }
}
