package org.thoughtcrime.securesms.googlevoice

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.websession.WebSignInActivity
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AllIn: signs outgoing Google Voice texts the way voice.google.com does, by running Google's BotGuard ("Waa")
 * script in a hidden WebView. Google uses the signature to tell real web clients from bots; texts sent without one
 * can be dropped or rate-limited.
 *
 * Same approach as mautrix-gvoice's Electron helper (pkg/connector/electron.go): a blank page on the
 * voice.google.com origin that may load nothing but the BotGuard script. Create on the main thread and [destroy]
 * when done.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class GoogleVoiceSigner @MainThread constructor(context: Context) {

  companion object {
    private val TAG = Log.tag(GoogleVoiceSigner::class.java)

    private const val PAGE_URL = "https://voice.google.com/about"
    private val PROGRAM_LIFETIME_MS = TimeUnit.MINUTES.toMillis(55)
    private const val TIMEOUT_MS = 20_000L
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val mutex = Mutex()
  private val requestIds = AtomicInteger()
  private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

  private var waa: GoogleVoiceApi.Waa? = null
  private var waaCreatedAt = 0L

  @Volatile
  private var scriptLoaded = CompletableDeferred<Boolean>()

  @Volatile
  private var allowedScriptUrl: String? = null

  private val webView = WebView(context.applicationContext).apply {
    settings.javaScriptEnabled = true
    settings.userAgentString = WebSignInActivity.chromeUserAgent(context)
    addJavascriptInterface(Bridge(), "AllInWaa")
    webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.toString() == allowedScriptUrl) {
          return null
        }
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
      }
    }
  }

  /** Loads the script ahead of time, so the first send doesn't wait for it. */
  fun warmUp() {
    scope.launch {
      try {
        mutex.withLock { withTimeout(TIMEOUT_MS) { ensureLoaded() } }
      } catch (e: Exception) {
        Log.w(TAG, "Warm-up failed", e)
      }
    }
  }

  /**
   * Returns the signature for a text to [threadId] (whose participants are [recipients]) with [transactionId],
   * or null if it couldn't be made.
   */
  suspend fun sign(threadId: String, recipients: List<String>, transactionId: Long): String? {
    val payload = JSONObject()
      .put("message_ids", sha256(transactionId.toString()))
      .put("destinations", sha256(recipients.joinToString("|")))
      .put("thread_id", sha256(threadId))

    return try {
      withContext(Dispatchers.Main) {
        mutex.withLock {
          withTimeout(TIMEOUT_MS) {
            ensureLoaded()
            run(payload.toString())
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't sign message", e)
      null
    }
  }

  @MainThread
  fun destroy() {
    scope.cancel()
    pending.values.forEach { it.cancel() }
    webView.destroy()
  }

  private suspend fun ensureLoaded() {
    if (waa != null && System.currentTimeMillis() - waaCreatedAt < PROGRAM_LIFETIME_MS && scriptLoaded.isCompleted) {
      return
    }

    val newWaa = withContext(Dispatchers.IO) { GoogleVoiceApi.createWaa() }
    waa = newWaa
    waaCreatedAt = System.currentTimeMillis()
    scriptLoaded = CompletableDeferred()
    allowedScriptUrl = newWaa.interpreterUrl

    val src = newWaa.interpreterUrl.replace("&", "&amp;").replace("\"", "&quot;")
    val html = "<!doctype html><html><head><script src=\"$src\" onload=\"AllInWaa.onLoaded(true)\" onerror=\"AllInWaa.onLoaded(false)\"></script></head><body></body></html>"
    webView.loadDataWithBaseURL(PAGE_URL, html, "text/html", "utf-8", null)

    if (!scriptLoaded.await()) {
      waa = null
      throw GoogleVoiceException.Failed("BotGuard script didn't load")
    }
    Log.i(TAG, "BotGuard program loaded")

    // Like the web app, report a blank signature once per program.
    val blank = run(null)
    scope.launch(Dispatchers.IO) {
      try {
        if (blank != null) GoogleVoiceApi.pingWaa(blank)
      } catch (e: GoogleVoiceException) {
        Log.w(TAG, "Waa ping failed", e)
      }
    }
  }

  /** Runs the loaded program on [payloadJson] (null for a blank payload) and waits for the signature. */
  private suspend fun run(payloadJson: String?): String? {
    val waa = requireNotNull(waa)
    val id = requestIds.incrementAndGet().toString()
    val result = CompletableDeferred<String?>()
    pending[id] = result

    // Mirrors the web app's call: a(program, init callback, ...) hands back functions, the first of which signs.
    val js = """
      (function(id, globalName, program, payload) {
        try {
          window[globalName].a(program, function(sign) {
            sign(function(result) { AllInWaa.onResult(id, result == null ? null : String(result)); }, [payload, undefined, undefined, undefined]);
          }, true, undefined, function() {});
        } catch (e) {
          AllInWaa.onError(id, String(e));
        }
      })(${JSONObject.quote(id)}, ${JSONObject.quote(waa.globalName)}, ${JSONObject.quote(waa.program)}, ${payloadJson ?: "undefined"});
    """.trimIndent()

    try {
      webView.evaluateJavascript(js, null)
      return result.await()?.takeIf { it.isNotEmpty() }
    } finally {
      pending.remove(id)
    }
  }

  private fun sha256(value: String): String {
    return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()), Base64.NO_WRAP)
  }

  /** Called from JavaScript on a WebView thread. */
  private inner class Bridge {
    @JavascriptInterface
    fun onLoaded(success: Boolean) {
      scriptLoaded.complete(success)
    }

    @JavascriptInterface
    fun onResult(id: String, result: String?) {
      pending[id]?.complete(result)
    }

    @JavascriptInterface
    fun onError(id: String, error: String) {
      Log.w(TAG, "BotGuard error: $error")
      pending[id]?.complete(null)
    }
  }
}
