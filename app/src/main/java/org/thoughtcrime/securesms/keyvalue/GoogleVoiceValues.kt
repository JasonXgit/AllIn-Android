package org.thoughtcrime.securesms.keyvalue

/**
 * AllIn: settings for the optional Google Voice integration. Off by default.
 */
class GoogleVoiceValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val ENABLED: String = "google_voice.enabled"
    private const val SIGNED_IN: String = "google_voice.signed_in"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  var enabled by booleanValue(ENABLED, false)

  var signedIn by booleanValue(SIGNED_IN, false)
}
