package org.thoughtcrime.securesms.keyvalue

/**
 * AllIn: settings for the optional Google Voice integration. Off by default.
 */
class GoogleVoiceValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val ENABLED: String = "google_voice.enabled"
    private const val SIGNED_IN: String = "google_voice.signed_in"
    private const val NOTIFICATIONS: String = "google_voice.notifications"
    private const val NOTIFIED_UP_TO: String = "google_voice.notified_up_to"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  var enabled by booleanValue(ENABLED, false)

  var signedIn by booleanValue(SIGNED_IN, false)

  var notificationsEnabled by booleanValue(NOTIFICATIONS, true)

  /** Timestamp of the newest incoming text already notified (or skipped); 0 until the first check. */
  var notifiedUpTo by longValue(NOTIFIED_UP_TO, 0)
}
