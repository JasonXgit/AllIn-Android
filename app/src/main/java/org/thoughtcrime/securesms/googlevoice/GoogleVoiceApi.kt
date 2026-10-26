package org.thoughtcrime.securesms.googlevoice

import android.util.Base64
import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.googlevoice.proto.RespGetThread
import org.thoughtcrime.securesms.googlevoice.proto.RespListThreads
import org.thoughtcrime.securesms.googlevoice.proto.RespSendSMS
import org.thoughtcrime.securesms.googlevoice.proto.Thread
import org.thoughtcrime.securesms.googlevoice.proto.ThreadFolder
import org.thoughtcrime.securesms.websession.WebSignInActivity
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * AllIn: client for the private web API that voice.google.com uses, authenticated with the WebView's Google cookies.
 *
 * Request bodies are "pblite" (a protobuf message written as a JSON array, where index i holds field i + 1) and
 * responses are binary protobuf. The protocol is reverse-engineered (see protowire/googlevoice), so Google can
 * change it at any time; failures surface as [GoogleVoiceException].
 */
object GoogleVoiceApi {

  private val TAG = Log.tag(GoogleVoiceApi::class.java)

  private const val ORIGIN = "https://voice.google.com"
  private const val API_BASE = "https://clients6.google.com/voice/v1/voiceclient"
  private const val API_KEY = "AIzaSyDTYc1N4xiODyrQYK0Kl6g_y279LjYkrBg"

  /** Waa issues the BotGuard program whose signature Google requires on sent texts (see [GoogleVoiceSigner]). */
  private const val WAA_BASE = "https://waa-pa.clients6.google.com/\$rpc/google.internal.waa.v1.Waa"
  private const val WAA_API_KEY = "AIzaSyBGb5fGAyC-pRcRU6MUHb__b_vKha71HRE"
  private const val WAA_REQUEST_KEY = "/JR8jsAkqotcKsEKhXic"

  /** The voice.google.com web client build the protocol was captured from. */
  private const val CLIENT_VERSION = "665865172"

  private const val CONTENT_TYPE_PBLITE = "application/json+protobuf"
  private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"

  /** Our own client: the shared Signal client would add a Signal user agent, which Google should never see. */
  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  /**
   * Lists conversations (texts, calls and voicemail), newest first, each with its most recent messages.
   * Callers filter on [Thread.isText]; the web app's text-only folder is not accepted by this endpoint.
   */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun listThreads(paginationToken: String? = null): RespListThreads {
    val body = pblite(ThreadFolder.ALL_THREADS.value, 20, 15, paginationToken, null, pblite(null, 1, 1))
    return RespListThreads.ADAPTER.decode(post("api2thread/list", body))
  }

  /** Loads one conversation with up to [messageCount] of its messages. */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun getThread(threadId: String, messageCount: Int = 100, paginationToken: String? = null): Thread? {
    val body = pblite(threadId, messageCount, paginationToken, pblite(null, 1, 1))
    return RespGetThread.ADAPTER.decode(post("api2thread/get", body)).thread
  }

  /**
   * Sends a text into an existing conversation. [trackingData] is the BotGuard signature from [GoogleVoiceSigner];
   * without one Google may silently drop or rate-limit the message.
   */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun sendSms(threadId: String, text: String, transactionId: Long, trackingData: String): RespSendSMS {
    val body = pblite(null, null, null, null, text, threadId, null, null, pblite(transactionId), null, pblite(trackingData))
    return RespSendSMS.ADAPTER.decode(post("api2thread/sendsms", body))
  }

  /** A random ID like the web app's, small enough to survive a round trip through JavaScript numbers. */
  fun newTransactionId(): Long = Random.nextLong(100_000_000_000_000)

  data class Waa(val interpreterUrl: String, val program: String, val globalName: String)

  /** Asks Google for a fresh BotGuard program and the script that runs it. Programs are good for about an hour. */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun createWaa(): Waa {
    val json = String(postWaa("Create", pblite(WAA_REQUEST_KEY)))
    try {
      // pblite: [[respID, null, [null, null, null, url], hash, program, globalName, ...]]
      val waa = JSONArray(json).getJSONArray(0)
      val url = waa.getJSONArray(2).getString(3)
      return Waa(
        interpreterUrl = if (url.startsWith("//")) "https:$url" else url,
        program = waa.getString(4),
        globalName = waa.getString(5)
      )
    } catch (e: Exception) {
      throw GoogleVoiceException.Failed("Unexpected Waa response", e)
    }
  }

  /** The web app reports one blank signature after loading a program; mirrors that so ours looks the same. */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun pingWaa(signature: String) {
    postWaa("Ping", pblite(WAA_REQUEST_KEY, signature, 72, Random.nextLong(2_000_000_000)))
  }

  private fun post(endpoint: String, body: PbLite): ByteArray {
    return execute(endpoint, body) {
      url("$API_BASE/$endpoint?alt=proto&key=$API_KEY")
      header("X-Requested-With", "XMLHttpRequest")
      header("X-Client-Version", CLIENT_VERSION)
      header("X-JavaScript-User-Agent", "google-api-javascript-client/1.1.0")
      // Google only returns protobuf to cookie-authenticated requests base64-wrapped, which execute() unwraps.
      header("X-Goog-Encode-Response-If-Executable", "base64")
    }
  }

  private fun postWaa(method: String, body: PbLite): ByteArray {
    return execute("waa/$method", body) {
      url("$WAA_BASE/$method")
      header("X-Goog-Api-Key", WAA_API_KEY)
      header("X-User-Agent", "grpc-web-javascript/0.1")
    }
  }

  private fun execute(endpoint: String, body: PbLite, configure: Request.Builder.() -> Unit): ByteArray {
    val cookies = CookieManager.getInstance().getCookie(API_BASE) ?: throw GoogleVoiceException.NotSignedIn()
    val sapisid = WebSignInActivity.cookie(API_BASE, "SAPISID") ?: throw GoogleVoiceException.NotSignedIn()

    val request = Request.Builder()
      .header("Cookie", cookies)
      .header("Authorization", sapisidHash(sapisid))
      .header("User-Agent", WebSignInActivity.chromeUserAgent(AppDependencies.application))
      .header("Origin", ORIGIN)
      .header("Referer", "$ORIGIN/")
      .header("X-Goog-AuthUser", "0")
      // Google refuses cookie-authenticated requests that don't look like a browser's cross-site fetch ("Request unsafe
      // for trusted domain").
      .header("Sec-Fetch-Site", "same-site")
      .header("Sec-Fetch-Mode", "cors")
      .header("Sec-Fetch-Dest", "empty")
      .apply(configure)
      .post(body.json.toRequestBody(CONTENT_TYPE_PBLITE.toMediaType()))
      .build()

    try {
      client.newCall(request).execute().use { response ->
        if (response.code == 401 || response.code == 403) {
          Log.w(TAG, "[$endpoint] Rejected with ${response.code}, session expired.")
          throw GoogleVoiceException.NotSignedIn()
        }
        if (!response.isSuccessful) {
          // Google's error body names the bad field; it holds no account data.
          Log.w(TAG, "[$endpoint] ${response.code}: ${response.body.string().take(500)}")
          throw GoogleVoiceException.Failed("$endpoint returned ${response.code}")
        }

        val bytes = response.body.bytes()
        val safetyType = response.header("X-Goog-Safety-Content-Type")
        return if (safetyType?.startsWith(CONTENT_TYPE_PROTOBUF) == true && response.header("Content-Type")?.startsWith("text/plain") == true) {
          Base64.decode(bytes, Base64.DEFAULT)
        } else {
          bytes
        }
      }
    } catch (e: IOException) {
      throw GoogleVoiceException.Failed("$endpoint failed: ${e.javaClass.simpleName}", e)
    }
  }

  /** The Authorization header google.com web apps send: SHA-1 of "timestamp SAPISID origin". */
  private fun sapisidHash(sapisid: String): String {
    val timestamp = System.currentTimeMillis() / 1000
    val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $sapisid $ORIGIN".toByteArray())
    return "SAPISIDHASH ${timestamp}_${digest.joinToString("") { "%02x".format(it) }}"
  }

  /** Writes a pblite array. Nested messages are themselves [pblite] results. */
  private fun pblite(vararg fields: Any?): PbLite {
    return PbLite(
      fields.joinToString(",", "[", "]") { field ->
        when (field) {
          null -> "null"
          is PbLite -> field.json
          is String -> JSONObject.quote(field)
          is Int, is Long, is Boolean -> field.toString()
          else -> throw IllegalArgumentException("Unsupported pblite field: ${field.javaClass}")
        }
      }
    )
  }

  @JvmInline
  private value class PbLite(val json: String)
}

sealed class GoogleVoiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class NotSignedIn : GoogleVoiceException("Not signed in to Google Voice")
  class Failed(message: String, cause: Throwable? = null) : GoogleVoiceException(message, cause)
}
