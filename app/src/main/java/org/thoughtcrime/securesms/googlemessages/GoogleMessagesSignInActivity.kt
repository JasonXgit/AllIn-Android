package org.thoughtcrime.securesms.googlemessages

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thoughtcrime.securesms.websession.WebSignInActivity

/**
 * AllIn: signs in to Google for phone texts. Finishes once messages.google.com has a session (the OSID cookie that
 * Google Messages for web needs). Pairing with the phone happens afterwards, in [GoogleMessagesSettingsActivity].
 */
class GoogleMessagesSignInActivity : WebSignInActivity() {

  companion object {
    const val SIGN_IN_URL = "https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Fmessages.google.com%2Fweb%2Fconfig"
    private const val ORIGIN = "https://messages.google.com"

    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, GoogleMessagesSignInActivity::class.java)

    fun hasSession(): Boolean = cookie(ORIGIN, "SAPISID") != null && cookie(ORIGIN, "OSID") != null
  }

  override val signInUrl = SIGN_IN_URL

  override val successMessage = "Signed in to Google"

  override fun isSignedIn(uri: Uri): Boolean = uri.host == "messages.google.com" && hasSession()

  override fun onSignedIn() = Unit
}
