package org.thoughtcrime.securesms.keyvalue

/**
 * AllIn: settings for the optional ESPN fantasy football entry in the chat list. Off by default.
 */
class FantasyValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val ENABLED: String = "fantasy.enabled"
    private const val SIGNED_IN: String = "fantasy.espn_signed_in"
    private const val SELECTED_LEAGUE: String = "fantasy.selected_league"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  var enabled by booleanValue(ENABLED, false)

  var signedIn by booleanValue(SIGNED_IN, false)

  /** The ESPN league ID last shown, so the screen reopens on the same league. 0 when none. */
  var selectedLeague by longValue(SELECTED_LEAGUE, 0)
}
