package org.thoughtcrime.securesms.fantasy

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.websession.WebSignInActivity

/**
 * AllIn: signs in to ESPN. Finishes once ESPN has set the espn_s2 cookie, which it only does for a signed-in user
 * (SWID alone is also set for anonymous visitors).
 */
class EspnSignInActivity : WebSignInActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, EspnSignInActivity::class.java)
    }
  }

  /**
   * ESPN's login is a Disney overlay opened from the site header rather than a stable URL, and the fantasy hub
   * answers mobile browsers with "Page not found", so start on the homepage and let the user tap Log In.
   */
  override val signInUrl = "https://www.espn.com/"

  override val successMessage = "Signed in to ESPN"

  override fun isSignedIn(uri: Uri): Boolean = EspnApi.hasSession()

  override fun onSignedIn() {
    SignalStore.fantasy.signedIn = true
  }
}
