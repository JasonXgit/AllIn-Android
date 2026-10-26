package org.thoughtcrime.securesms.googlevoice

import android.webkit.CookieManager
import android.webkit.WebStorage
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.websession.WebSignInActivity

/**
 * AllIn: the Google Voice web session, kept in the app's WebView cookie store.
 *
 * Google Voice has no public API, so the integration drives voice.google.com's own API. If Google changes the site
 * or starts refusing the WebView, this and [GoogleVoiceApi] are the places to adapt.
 */
object GoogleVoiceSession {

  /** "grandcentral" is Google Voice's service name in Google's account system. */
  const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=grandcentral&continue=https%3A%2F%2Fvoice.google.com%2Fu%2F0%2Fmessages"

  const val VOICE_HOST = "voice.google.com"

  /** True when the cookie store holds a signed-in Google session that voice.google.com can use. */
  fun hasSession(): Boolean {
    return WebSignInActivity.cookie("https://$VOICE_HOST", "SAPISID") != null
  }

  /** Signs out of Google entirely in AllIn's WebView (other web sessions, such as ESPN, are kept). */
  fun signOut() {
    val cookies = CookieManager.getInstance()
    for (url in listOf("https://google.com", "https://accounts.google.com", "https://voice.google.com", "https://clients6.google.com")) {
      cookies.getCookie(url)?.split(';')?.map { it.trim().substringBefore('=') }?.forEach { name ->
        cookies.setCookie(url, "$name=; Max-Age=0; Domain=.google.com; Path=/; Secure")
        cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
      }
    }
    cookies.flush()
    WebStorage.getInstance().deleteOrigin("https://voice.google.com")
    SignalStore.googleVoice.signedIn = false
    GoogleVoiceMonitor.update()
  }
}
