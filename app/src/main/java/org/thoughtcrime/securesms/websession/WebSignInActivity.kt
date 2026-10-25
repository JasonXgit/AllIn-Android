package org.thoughtcrime.securesms.websession

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * AllIn: signs in to a website inside a WebView and finishes once [isSignedIn] sees the session cookies.
 * The session then lives in the app's shared WebView cookie store, where the matching API client reads it.
 *
 * The WebView presents itself as regular Chrome, because Google and others refuse to sign in embedded WebViews.
 * Logs only hosts and paths, never query strings or cookies.
 */
abstract class WebSignInActivity : BaseActivity() {

  private val tag = Log.tag(javaClass)
  private val theme = DynamicNoActionBarTheme()
  private lateinit var webView: WebView

  /** The page that starts the sign-in flow. */
  protected abstract val signInUrl: String

  /** Shown when sign-in completes, e.g. "Signed in to ESPN". */
  protected abstract val successMessage: String

  /** Called after every page load; return true once the session is usable. */
  protected abstract fun isSignedIn(uri: Uri): Boolean

  /** Persist that the user is signed in. */
  protected abstract fun onSignedIn()

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    // WebView ignores its own padding when drawing page content, so the system-bar insets go on a container instead;
    // otherwise sites' top menus end up under the status bar.
    webView = WebView(this)
    val container = FrameLayout(this)
    container.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    setContentView(container)

    ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }

    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.userAgentString = chromeUserAgent(this)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

    webView.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView, url: String) {
        val uri = Uri.parse(url)
        Log.i(tag, "Loaded ${uri.host}${uri.path}")

        if (!isFinishing && isSignedIn(uri)) {
          Log.i(tag, "Sign-in succeeded.")
          CookieManager.getInstance().flush()
          onSignedIn()
          Toast.makeText(this@WebSignInActivity, successMessage, Toast.LENGTH_SHORT).show()
          finish()
        }
      }
    }

    onBackPressedDispatcher.addCallback(this) {
      if (webView.canGoBack()) webView.goBack() else finish()
    }

    webView.loadUrl(signInUrl)
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  override fun onDestroy() {
    if (::webView.isInitialized) {
      webView.destroy()
    }
    super.onDestroy()
  }

  companion object {
    /** Returns the value of cookie [name] that the WebView would send to [url], if any. */
    fun cookie(url: String, name: String): String? {
      return CookieManager.getInstance().getCookie(url)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
    }

    /**
     * The system WebView user agent with the WebView markers removed ("; wv" and "Version/4.0"),
     * which makes it identical to Chrome on the same device.
     */
    fun chromeUserAgent(context: Context): String {
      return WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
    }
  }
}
