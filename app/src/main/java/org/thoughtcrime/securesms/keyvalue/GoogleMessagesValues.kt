package org.thoughtcrime.securesms.keyvalue

/**
 * AllIn: settings for the optional phone texts (Google Messages) integration. Off by default.
 */
class GoogleMessagesValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val ENABLED: String = "google_messages.enabled"
    private const val AUTH: String = "google_messages.auth"
    private const val NOTIFICATIONS: String = "google_messages.notifications"
    private const val NOTIFIED_UP_TO: String = "google_messages.notified_up_to"
    private const val RELAY: String = "google_messages.relay"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  var enabled by booleanValue(ENABLED, false)

  /** The pairing with Google Messages (keys and tokens) as JSON, or null when not paired. Never backed up. */
  var authJson: String? by stringValue(AUTH, null)

  var notificationsEnabled by booleanValue(NOTIFICATIONS, true)

  /** Timestamp (ms) of the newest incoming text already notified; 0 until the first sync. */
  var notifiedUpTo by longValue(NOTIFIED_UP_TO, 0)

  /** Whether AllIn on your computer may read and send these texts through this phone. */
  var relayToDesktop by booleanValue(RELAY, true)

  val paired: Boolean
    get() = authJson != null
}
