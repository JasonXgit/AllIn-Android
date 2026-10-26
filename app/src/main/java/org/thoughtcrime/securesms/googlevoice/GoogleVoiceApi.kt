package org.thoughtcrime.securesms.googlevoice

import android.util.Base64
import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

  /** ReqSendSMS.Media.Type.JPEG */
  private const val MEDIA_TYPE_JPEG = 1

  private const val REALTIME_BASE = "https://signaler-pa.clients6.google.com"
  private const val REALTIME_CHOOSE_SERVER = "[[null,null,null,[7,5],null,[null,[null,1],[[[\"3\"]]]]]]"

  /** The event sources the web app subscribes to, each with its kind (from mautrix-gvoice's channel.go). */
  private val REALTIME_SOURCES = listOf("1" to "2", "2" to "3", "3" to "3", "4" to "1", "5" to "1", "6" to "1", "9" to "1")

  private const val CONTENT_TYPE_PBLITE = "application/json+protobuf"
  private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"

  /** Our own client: the shared Signal client would add a Signal user agent, which Google should never see. */
  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  /** Google holds realtime requests open for minutes, sending a keep-alive about every 30 seconds. */
  private val longPollClient: OkHttpClient by lazy {
    client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
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
   * Sends a text, optionally with a JPEG [photo], into an existing conversation. [trackingData] is the BotGuard
   * signature from [GoogleVoiceSigner]; without one Google may silently drop or rate-limit the message.
   */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun sendSms(threadId: String, text: String, transactionId: Long, trackingData: String, photo: ByteArray? = null): RespSendSMS {
    val media = photo?.let { pblite(MEDIA_TYPE_JPEG, it) }
    val body = pblite(null, null, null, null, text, threadId, null, null, pblite(transactionId), media, pblite(trackingData))
    return RespSendSMS.ADAPTER.decode(post("api2thread/sendsms", body))
  }

  /** Marks a conversation as read, here and in Google's own apps. */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun markRead(threadId: String) {
    // ReqUpdateAttributes: the thread's new attributes, then which attributes to change ("read"), then 1.
    post("thread/updateattributes", pblite(pblite(threadId, null, null, true), pblite(null, null, null, true), 1))
  }

  /** Downloads a picture from an MMS message, at full size. */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun downloadAttachment(attachmentId: String): ByteArray {
    return execute("attachment", null) {
      url("$ORIGIN/u/0/a/i/$attachmentId?s=1")
      header("Sec-Fetch-Site", "same-origin")
    }
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

  // Realtime channel ("WebChannel" long polling), used by GoogleVoiceMonitor to hear about new messages right away.

  /** Asks Google which realtime server to use; returns its session ID ("gsessionid"). */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun chooseRealtimeServer(): String {
    val json = String(
      execute("realtime/chooseServer", PbLite(REALTIME_CHOOSE_SERVER)) {
        url("$REALTIME_BASE/punctual/v1/chooseServer?key=$API_KEY")
      }
    )
    try {
      return JSONArray(json).getString(0)
    } catch (e: Exception) {
      throw GoogleVoiceException.Failed("Unexpected chooseServer response", e)
    }
  }

  /** Opens a realtime channel subscribed to the web app's event sources; returns the channel's ID ("SID"). */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun createRealtimeChannel(gSessionId: String): String {
    val form = FormBody.Builder().add("count", REALTIME_SOURCES.size.toString()).add("ofs", "0")
    REALTIME_SOURCES.forEachIndexed { i, (source, kind) ->
      form.add("req${i}___data__", "[[[\"$source\",[null,null,null,[7,5],null,[null,[null,1],[[[\"$kind\"]]]],null,1,2],null,3]]]")
    }
    val request = signedInRequest()
      .url("$REALTIME_BASE/punctual/multi-watch/channel?VER=8&gsessionid=$gSessionId&RID=${Random.nextInt(100_000)}&CVER=22&t=1&key=$API_KEY")
      .header("X-WebChannel-Content-Type", CONTENT_TYPE_PBLITE)
      .post(form.build())
      .build()

    val body = call("realtime/create", request) { it.body.string() }
    try {
      // "<length>\n[[0,["c","<SID>","",8,12,30000]]]"
      return JSONArray(body.substringAfter('\n')).getJSONArray(0).getJSONArray(1).getString(1)
    } catch (e: Exception) {
      throw GoogleVoiceException.Failed("Unexpected channel response", e)
    }
  }

  /**
   * Long-polls the channel. Google holds the request open and streams length-prefixed JSON chunks as events happen;
   * [onChunk] gets each one. Returns when Google ends the response (normally every few minutes).
   */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun pollRealtimeChannel(gSessionId: String, channelId: String, ackId: Long, onChunk: (String) -> Unit) {
    val request = signedInRequest()
      .url("$REALTIME_BASE/punctual/multi-watch/channel?VER=8&gsessionid=$gSessionId&RID=rpc&SID=$channelId&AID=$ackId&CI=0&TYPE=xmlhttp&t=1&key=$API_KEY")
      .get()
      .build()

    call("realtime/poll", request, longPollClient) { response ->
      // Lengths count UTF-16 code units, which is what a Java char is.
      val reader = response.body.charStream().buffered()
      while (true) {
        val length = StringBuilder()
        while (true) {
          val c = reader.read()
          if (c == -1) return@call
          if (c == '\n'.code) break
          length.append(c.toChar())
        }
        val count = length.toString().trim().toIntOrNull() ?: throw GoogleVoiceException.Failed("Bad realtime chunk")
        val chunk = CharArray(count)
        var read = 0
        while (read < count) {
          val n = reader.read(chunk, read, count - read)
          if (n == -1) return@call
          read += n
        }
        onChunk(String(chunk))
      }
    }
  }

  /** A request with the cookies and headers every signed-in call needs. */
  private fun signedInRequest(): Request.Builder {
    val cookies = CookieManager.getInstance().getCookie(API_BASE) ?: throw GoogleVoiceException.NotSignedIn()
    val sapisid = WebSignInActivity.cookie(API_BASE, "SAPISID") ?: throw GoogleVoiceException.NotSignedIn()

    return Request.Builder()
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
  }

  /** Sends [body] as a POST, or a GET when it's null. */
  private fun execute(endpoint: String, body: PbLite?, configure: Request.Builder.() -> Unit): ByteArray {
    val request = signedInRequest()
      .apply(configure)
      .apply { if (body != null) post(body.json.toRequestBody(CONTENT_TYPE_PBLITE.toMediaType())) }
      .build()

    return call(endpoint, request) { response ->
      val bytes = response.body.bytes()
      val safetyType = response.header("X-Goog-Safety-Content-Type")
      if (safetyType?.startsWith(CONTENT_TYPE_PROTOBUF) == true && response.header("Content-Type")?.startsWith("text/plain") == true) {
        Base64.decode(bytes, Base64.DEFAULT)
      } else {
        bytes
      }
    }
  }

  /** Runs [request], turning auth failures, error codes and network errors into [GoogleVoiceException]s. */
  private fun <T> call(endpoint: String, request: Request, httpClient: OkHttpClient = client, read: (Response) -> T): T {
    try {
      httpClient.newCall(request).execute().use { response ->
        if (response.code == 401 || response.code == 403) {
          Log.w(TAG, "[$endpoint] Rejected with ${response.code}, session expired.")
          throw GoogleVoiceException.NotSignedIn()
        }
        if (!response.isSuccessful) {
          // Google's error body names the bad field; it holds no account data.
          val error = response.body.string().take(500)
          Log.w(TAG, "[$endpoint] ${response.code}: $error")
          throw GoogleVoiceException.Failed("$endpoint returned ${response.code}", httpCode = response.code, body = error)
        }
        return read(response)
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
          is ByteArray -> JSONObject.quote(Base64.encodeToString(field, Base64.NO_WRAP))
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
  class Failed(message: String, cause: Throwable? = null, val httpCode: Int = 0, val body: String = "") : GoogleVoiceException(message, cause)
}
