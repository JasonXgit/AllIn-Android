package org.thoughtcrime.securesms.googlevoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.websession.WebSignInActivity

/**
 * AllIn: signs in to Google Voice. Finishes as soon as voice.google.com loads with a session.
 */
class GoogleVoiceSignInActivity : WebSignInActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, GoogleVoiceSignInActivity::class.java)
    }
  }

  override val signInUrl = GoogleVoiceSession.SIGN_IN_URL

  override val successMessage = "Signed in to Google Voice"

  override fun isSignedIn(uri: Uri): Boolean {
    return uri.host == GoogleVoiceSession.VOICE_HOST && GoogleVoiceSession.hasSession()
  }

  override fun onSignedIn() {
    SignalStore.googleVoice.signedIn = true
  }
}
