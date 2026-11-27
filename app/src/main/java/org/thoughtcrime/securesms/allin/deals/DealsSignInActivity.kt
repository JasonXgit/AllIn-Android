package org.thoughtcrime.securesms.allin.deals

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.websession.WebSignInActivity

/**
 * AllIn: signs in to the Deal Reader on its own login page. After a good password the site sends you to "/" with the
 * dr.sid session cookie set; a wrong one goes back to /login?error=1.
 */
class DealsSignInActivity : WebSignInActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, DealsSignInActivity::class.java)
    }
  }

  override val signInUrl: String get() = "${DealsApi.baseUrl}/login"

  override val successMessage = "Signed in to Deals"

  override fun isSignedIn(uri: Uri): Boolean {
    val server = Uri.parse(DealsApi.baseUrl)
    val home = uri.path.isNullOrEmpty() || uri.path == "/"
    return uri.host == server.host && home && DealsApi.hasSession()
  }

  override fun onSignedIn() {
    SignalStore.deals.signedIn = true
    DealsSync.requestSync(force = true)
  }
}
