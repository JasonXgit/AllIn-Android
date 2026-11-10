package org.thoughtcrime.securesms.websession

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AllIn: separate WebView cookie stores ("profiles"), so one feature can be signed in to a different Google account
 * than another (phone texts vs. Google Voice). Falls back to the shared store on WebViews without profile support.
 */
object WebProfiles {
  private val TAG = Log.tag(WebProfiles::class.java)

  /** Profile for phone texts (Google Messages for web). */
  const val PHONE_TEXTS = "allin-phone-texts"

  private val managers = ConcurrentHashMap<String, CookieManager>()

  fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

  /** The cookie store for [profile] (null = the shared default). Safe to call from any thread. */
  fun cookieManager(profile: String?): CookieManager {
    if (profile == null || !isSupported()) return CookieManager.getInstance()
    managers[profile]?.let { return it }

    // ProfileStore must be used on the main thread.
    val manager = if (Looper.myLooper() == Looper.getMainLooper()) {
      ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager
    } else {
      var result: CookieManager? = null
      val latch = CountDownLatch(1)
      Handler(Looper.getMainLooper()).post {
        try {
          result = ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't open WebView profile", e)
        } finally {
          latch.countDown()
        }
      }
      latch.await(10, TimeUnit.SECONDS)
      result ?: return CookieManager.getInstance()
    }
    managers[profile] = manager
    return manager
  }

  /** Signs [profile] out of everything. Never touches the shared store. */
  fun clear(profile: String) {
    if (!isSupported()) return
    val manager = cookieManager(profile)
    manager.removeAllCookies(null)
    manager.flush()
  }
}
