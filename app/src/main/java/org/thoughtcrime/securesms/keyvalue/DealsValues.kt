package org.thoughtcrime.securesms.keyvalue

/**
 * AllIn: settings for the optional Deals entry (Jason's Deal Reader RSS site) in the chat list. Off by default.
 */
class DealsValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val ENABLED: String = "deals.enabled"
    private const val SIGNED_IN: String = "deals.signed_in"
    private const val SERVER_URL: String = "deals.server_url"

    const val DEFAULT_SERVER_URL = "https://rss.jason.ky"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  var enabled by booleanValue(ENABLED, false)

  var signedIn by booleanValue(SIGNED_IN, false)

  /** The Deal Reader's address, without a trailing slash. */
  var serverUrl by stringValue(SERVER_URL, DEFAULT_SERVER_URL)
}
