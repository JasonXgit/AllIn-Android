package org.thoughtcrime.securesms.googlemessages

import android.util.Base64
import android.webkit.CookieManager
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.EMPTY
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.googlemessages.proto.AckMessageRequest
import org.thoughtcrime.securesms.googlemessages.proto.ActionType
import org.thoughtcrime.securesms.googlemessages.proto.AttachmentInfo
import org.thoughtcrime.securesms.googlemessages.proto.AuthMessage
import org.thoughtcrime.securesms.googlemessages.proto.BrowserDetails
import org.thoughtcrime.securesms.googlemessages.proto.BrowserType
import org.thoughtcrime.securesms.googlemessages.proto.BugleRoute
import org.thoughtcrime.securesms.googlemessages.proto.Config
import org.thoughtcrime.securesms.googlemessages.proto.ConfigVersion
import org.thoughtcrime.securesms.googlemessages.proto.Conversation
import org.thoughtcrime.securesms.googlemessages.proto.Cursor
import org.thoughtcrime.securesms.googlemessages.proto.DeviceType
import org.thoughtcrime.securesms.googlemessages.proto.DownloadAttachmentRequest
import org.thoughtcrime.securesms.googlemessages.proto.EcP256PublicKey
import org.thoughtcrime.securesms.googlemessages.proto.EmptyArr
import org.thoughtcrime.securesms.googlemessages.proto.GaiaPairingErrorCode
import org.thoughtcrime.securesms.googlemessages.proto.GaiaPairingRequestContainer
import org.thoughtcrime.securesms.googlemessages.proto.GaiaPairingResponseContainer
import org.thoughtcrime.securesms.googlemessages.proto.GenericPublicKey
import org.thoughtcrime.securesms.googlemessages.proto.GetOrCreateConversationRequest
import org.thoughtcrime.securesms.googlemessages.proto.GetOrCreateConversationResponse
import org.thoughtcrime.securesms.googlemessages.proto.IncomingRPCMessage
import org.thoughtcrime.securesms.googlemessages.proto.ListConversationsRequest
import org.thoughtcrime.securesms.googlemessages.proto.ListConversationsResponse
import org.thoughtcrime.securesms.googlemessages.proto.ListMessagesRequest
import org.thoughtcrime.securesms.googlemessages.proto.ListMessagesResponse
import org.thoughtcrime.securesms.googlemessages.proto.LongPollingPayload
import org.thoughtcrime.securesms.googlemessages.proto.MediaContent
import org.thoughtcrime.securesms.googlemessages.proto.MessageReadRequest
import org.thoughtcrime.securesms.googlemessages.proto.MessageType
import org.thoughtcrime.securesms.googlemessages.proto.NotifyDittoActivityRequest
import org.thoughtcrime.securesms.googlemessages.proto.OutgoingRPCData
import org.thoughtcrime.securesms.googlemessages.proto.OutgoingRPCMessage
import org.thoughtcrime.securesms.googlemessages.proto.PublicKeyType
import org.thoughtcrime.securesms.googlemessages.proto.RPCMessageData
import org.thoughtcrime.securesms.googlemessages.proto.ReceiveMessagesRequest
import org.thoughtcrime.securesms.googlemessages.proto.RegisterRefreshRequest
import org.thoughtcrime.securesms.googlemessages.proto.RegisterRefreshResponse
import org.thoughtcrime.securesms.googlemessages.proto.SendMessageRequest
import org.thoughtcrime.securesms.googlemessages.proto.SendMessageResponse
import org.thoughtcrime.securesms.googlemessages.proto.SignInGaiaRequest
import org.thoughtcrime.securesms.googlemessages.proto.SignInGaiaResponse
import org.thoughtcrime.securesms.googlemessages.proto.StartMediaUploadRequest
import org.thoughtcrime.securesms.googlemessages.proto.TokenData
import org.thoughtcrime.securesms.googlemessages.proto.Ukey2ClientFinished
import org.thoughtcrime.securesms.googlemessages.proto.Ukey2ClientInit
import org.thoughtcrime.securesms.googlemessages.proto.Ukey2HandshakeCipher
import org.thoughtcrime.securesms.googlemessages.proto.Ukey2Message
import org.thoughtcrime.securesms.googlemessages.proto.Ukey2ServerInit
import org.thoughtcrime.securesms.googlemessages.proto.UpdateEvents
import org.thoughtcrime.securesms.googlemessages.proto.UploadMediaResponse
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.thoughtcrime.securesms.googlemessages.proto.Message as GmMessage

/**
 * AllIn: a client for Google Messages for web, ported from mautrix-gmessages' libgm (AGPL-3.0).
 *
 * AllIn acts as one more "web" session of the Google Messages app on the user's phone, paired through their
 * Google account. Requests go to Google's relay servers and from there to Google Messages on the phone, encrypted
 * with keys agreed at pairing. Events (new texts, conversation changes) arrive over a long poll.
 *
 * The protocol is reverse-engineered and Google can change it at any time; failures surface as [GmException].
 */
class GmClient(val auth: GmAuth, private val onEvent: (GmEvent) -> Unit) {

  companion object {
    private val TAG = Log.tag(GmClient::class.java)

    private const val API_KEY = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    private const val SEC_UA = "\"Google Chrome\";v=\"152\", \"Chromium\";v=\"152\", \"Not-A.Brand\";v=\"24\""
    private const val ORIGIN = "https://messages.google.com"
    private const val CONFIG_URL = "$ORIGIN/web/config"
    private const val GOOGLE_NETWORK = "GDitto"

    private const val IM = "https://instantmessaging-pa.googleapis.com"
    private const val IM_GOOGLE = "https://instantmessaging-pa.clients6.google.com"
    private const val UPLOAD_URL = "$IM/upload"
    private const val MESSAGING = "$IM_GOOGLE/\$rpc/google.internal.communications.instantmessaging.v1.Messaging"
    private const val RECEIVE_URL = "$MESSAGING/ReceiveMessages"
    private const val SEND_URL = "$MESSAGING/SendMessage"
    private const val ACK_URL = "$MESSAGING/AckMessages"
    private const val REGISTRATION = "$IM_GOOGLE/\$rpc/google.internal.communications.instantmessaging.v1.Registration"
    private const val SIGN_IN_GAIA_URL = "$REGISTRATION/SignInGaia"
    private const val REGISTER_REFRESH_URL = "$REGISTRATION/RegisterRefresh"

    private const val PBLITE = "application/json+protobuf"
    private const val PROTOBUF = "application/x-protobuf"

    val CONFIG_VERSION = ConfigVersion(Year = 2026, Month = 9, Day = 2, V1 = 4, V2 = 6)

    private val BROWSER_DETAILS = BrowserDetails(
      userAgent = USER_AGENT,
      browserType = BrowserType.OTHER,
      OS = "AllIn",
      deviceType = DeviceType.TABLET
    )

    private const val RESPONSE_TIMEOUT_MS = 60_000L
    private const val PING_INTERVAL_MS = 5 * 60_000L
    private val LOGGED_OUT_BYTES = byteArrayOf(0x72, 0x00)

    private val ENCRYPTION_KEY_INFO = byteArrayOf(
      130.toByte(), 170.toByte(), 85, 160.toByte(), 211.toByte(), 151.toByte(), 248.toByte(), 131.toByte(), 70, 202.toByte(), 28, 238.toByte(), 141.toByte(), 57, 9, 185.toByte(),
      95, 19, 250.toByte(), 125, 235.toByte(), 29, 74, 179.toByte(), 131.toByte(), 118, 184.toByte(), 37, 109, 168.toByte(), 85, 16
    )

    private const val PRIVATE_API_CONFIRMATION = "This is an undocumented API. Use or access of undocumented Google APIs without express authorization is prohibited per the Google API Terms of Service (https://developers.google.com/terms)."

    private val httpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    }

    /** Google keeps the long poll open for minutes, with heartbeats in between. */
    private val longPollClient: OkHttpClient by lazy {
      httpClient.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val waiters = ConcurrentHashMap<String, CompletableDeferred<Incoming>>()
  private val pendingAcks = mutableListOf<String>()

  @Volatile private var sessionId: String = UUID.randomUUID().toString()
  @Volatile private var pollCall: Call? = null
  @Volatile private var pollJob: Job? = null
  @Volatile private var skipCount = 0
  @Volatile private var conversationsFetched = false
  @Volatile private var closed = false

  /** True while the long poll is open. */
  @Volatile var connected = false
    private set

  /** A decoded response or event from the phone. */
  class Incoming(val action: ActionType, val decrypted: ByteArray?, val unencrypted: ByteArray?)

  // Connection

  /** Opens the long poll and starts the ack and ping loops. Needs a finished pairing. */
  fun connect() {
    check(auth.isPaired) { "Not paired" }
    refreshAuthToken()
    startLongPoll(loggedIn = true) { scope.launch { postConnect() } }
    scope.launch { ackLoop() }
    scope.launch { pingLoop() }
  }

  fun close() {
    closed = true
    pollCall?.cancel()
    pollJob?.cancel()
    scope.cancel()
    waiters.values.forEach { it.completeExceptionally(GmException.Failed("Disconnected")) }
    waiters.clear()
    connected = false
  }

  private suspend fun postConnect() {
    delay(2_000)
    sendAcks()
    delay(1_000)
    try {
      setActiveSession()
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't set the active session", e)
    }
  }

  /** Makes this the active web session, so the phone sends events here (Google allows one at a time). */
  fun setActiveSession() {
    sessionId = UUID.randomUUID().toString()
    sendNoResponse(ActionType.GET_UPDATES, null, requestId = sessionId, omitTtl = true)
  }

  private suspend fun ackLoop() {
    while (scope.isActive) {
      delay(5_000)
      sendAcks()
    }
  }

  private suspend fun pingLoop() {
    var failures = 0
    while (scope.isActive) {
      delay(PING_INTERVAL_MS)
      try {
        request(ActionType.NOTIFY_DITTO_ACTIVITY, NotifyDittoActivityRequest(success = true), timeoutMs = 30_000)
        failures = 0
      } catch (e: Exception) {
        failures++
        Log.w(TAG, "Phone didn't answer a ping ($failures in a row)")
        if (failures == 2) {
          runCatching { setActiveSession() }
        } else if (failures >= 3) {
          failures = 0
          pollCall?.cancel()
        }
      }
    }
  }

  private fun startLongPoll(loggedIn: Boolean, onFirstConnect: (() -> Unit)?) {
    pollJob?.cancel()
    pollJob = scope.launch {
      var firstConnect = onFirstConnect
      var errors = 0
      var disconnectedAt = 0L
      val listenId = UUID.randomUUID().toString()
      while (isActive && !closed) {
        try {
          if (loggedIn) refreshAuthToken()
          val payload = ReceiveMessagesRequest(
            auth = authMessage(listenId),
            unknown = ReceiveMessagesRequest.UnknownEmptyObject2(unknown = ReceiveMessagesRequest.UnknownEmptyObject1())
          )
          val call = (longPollClient).newCall(buildPost(RECEIVE_URL, payload, PBLITE))
          pollCall = call
          call.execute().use { response ->
            if (response.code == 401 || response.code == 403) {
              throw GmException.LoggedOut("Long poll rejected with ${response.code}")
            }
            if (!response.isSuccessful) throw GmException.Failed("Long poll returned ${response.code}")
            errors = 0
            connected = true
            onEvent(GmEvent.ConnectionChanged(true))
            if (loggedIn && disconnectedAt != 0L && System.currentTimeMillis() - disconnectedAt > 60_000) {
              scope.launch { runCatching { requestUpdates() } }
            }
            firstConnect?.let { it(); firstConnect = null }
            readLongPoll(response)
          }
        } catch (e: GmException.LoggedOut) {
          Log.w(TAG, "Logged out", e)
          connected = false
          onEvent(GmEvent.LoggedOut)
          return@launch
        } catch (e: Exception) {
          if (closed || !isActive) break
          errors++
          Log.w(TAG, "Long poll failed ($errors in a row): ${e.javaClass.simpleName}")
          if (connected) onEvent(GmEvent.ConnectionChanged(false))
          connected = false
          delay(minOf(errors * 5_000L, 60_000L))
        } finally {
          if (connected) disconnectedAt = System.currentTimeMillis()
          connected = false
        }
      }
    }
  }

  /** The body is `[[` + JSON arrays separated by commas + `]]`; each array is a pblite LongPollingPayload. */
  private fun readLongPoll(response: Response) {
    val source = response.body.source()
    val prefix = source.readUtf8(2)
    if (prefix != "[[") throw GmException.Failed("Unexpected long poll start")

    val item = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    while (!closed) {
      if (source.exhausted()) return
      val c = source.readUtf8CodePoint()
      if (depth == 0) {
        when (c) {
          '['.code -> { depth = 1; item.setLength(0); item.append('[') }
          ']'.code -> return
        }
        continue
      }
      item.appendCodePoint(c)
      if (inString) {
        when {
          escaped -> escaped = false
          c == '\\'.code -> escaped = true
          c == '"'.code -> inString = false
        }
        continue
      }
      when (c) {
        '"'.code -> inString = true
        '['.code -> depth++
        ']'.code -> {
          depth--
          if (depth == 0) handlePayload(item.toString())
        }
      }
    }
  }

  private fun handlePayload(json: String) {
    val payload = try {
      Pblite.decode(json, LongPollingPayload::class.java)
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't parse long poll payload", e)
      return
    }
    when {
      payload.data_ != null -> handleRpc(payload.data_)
      payload.ack != null -> skipCount = payload.ack.count ?: 0
    }
  }

  private fun handleRpc(raw: IncomingRPCMessage) {
    synchronized(pendingAcks) { pendingAcks += raw.responseID }
    if (raw.bugleRoute != BugleRoute.DataEvent) {
      if (raw.bugleRoute == BugleRoute.PairEvent) Log.i(TAG, "Pair event")
      return
    }

    val data = try {
      RPCMessageData.ADAPTER.decode(raw.messageData)
    } catch (e: Exception) {
      Log.w(TAG, "Bad data event", e)
      return
    }
    val decrypted = try {
      if (data.encryptedData.size > 0) auth.crypto.decrypt(data.encryptedData.toByteArray()) else null
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't decrypt an event", e)
      return
    }
    val incoming = Incoming(data.action, decrypted, data.unencryptedData.takeIf { it.size > 0 }?.toByteArray())

    // A response to one of our requests carries the request's ID. Google also sends unencrypted echoes first,
    // which aren't the response.
    val isPairing = data.action == ActionType.CREATE_GAIA_PAIRING_CLIENT_INIT || data.action == ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED
    if (isPairing || !(incoming.unencrypted != null && incoming.decrypted == null)) {
      waiters.remove(data.sessionID)?.let {
        it.complete(incoming)
        return
      }
    }

    var isOld = false
    if (skipCount > 0) {
      skipCount--
      isOld = true
    }

    if (data.action != ActionType.GET_UPDATES) return
    if (decrypted == null) {
      if (incoming.unencrypted?.contentEquals(LOGGED_OUT_BYTES) == true) onEvent(GmEvent.LoggedOut)
      return
    }
    val update = try {
      UpdateEvents.ADAPTER.decode(decrypted)
    } catch (e: Exception) {
      Log.w(TAG, "Bad update", e)
      return
    }
    when {
      update.conversationEvent != null -> if (!isOld) update.conversationEvent.data_.forEach { onEvent(GmEvent.ConversationUpdated(it)) }
      update.messageEvent != null -> update.messageEvent.data_.forEach { onEvent(GmEvent.MessageUpdated(it, isOld)) }
      update.settingsEvent != null -> onEvent(GmEvent.SettingsUpdated(update.settingsEvent))
      update.browserPresenceCheckEvent != null -> scope.launch { runCatching { sendNoResponse(ActionType.ACK_BROWSER_PRESENCE, null) } }
      update.userAlertEvent != null -> if (!isOld) onEvent(GmEvent.UserAlert(update.userAlertEvent.alertType.value))
    }
  }

  private fun sendAcks() {
    val ids = synchronized(pendingAcks) { pendingAcks.toList().also { pendingAcks.clear() } }
    if (ids.isEmpty()) return
    val payload = AckMessageRequest(
      authData = authMessage(UUID.randomUUID().toString()),
      emptyArr = EmptyArr(),
      acks = ids.map { AckMessageRequest.Message(requestID = it, device = auth.browser) }
    )
    try {
      post(ACK_URL, payload, PBLITE).close()
    } catch (e: Exception) {
      Log.w(TAG, "Acks failed, will retry")
      synchronized(pendingAcks) { if (pendingAcks.size < 1024) pendingAcks.addAll(0, ids) }
    }
  }

  /** Asks the phone to resend what happened while we weren't listening. */
  fun requestUpdates() {
    sendNoResponse(ActionType.GET_UPDATES, null, requestId = sessionId, omitTtl = true)
  }

  // Requests to the phone

  private fun buildRpc(
    action: ActionType,
    data: Message<*, *>?,
    requestId: String,
    messageType: MessageType,
    omitTtl: Boolean,
    customTtl: Long,
    encrypt: Boolean
  ): OutgoingRPCMessage {
    val serialized = data?.let { (it.adapter as ProtoAdapter<Message<*, *>>).encode(it) }
    val rpcData = OutgoingRPCData(
      requestID = requestId,
      action = action,
      unencryptedProtoData = if (!encrypt && serialized != null) serialized.bs() else EMPTY,
      encryptedProtoData = if (encrypt && serialized != null) auth.crypto.encrypt(serialized).bs() else EMPTY,
      sessionID = sessionId
    )
    return OutgoingRPCMessage(
      mobile = auth.mobile,
      data_ = OutgoingRPCMessage.Data(
        requestID = requestId,
        bugleRoute = BugleRoute.DataEvent,
        messageData = rpcData.encode().bs(),
        messageTypeData = OutgoingRPCMessage.Data.Type(emptyArr = EmptyArr(), messageType = messageType)
      ),
      auth = OutgoingRPCMessage.Auth(
        requestID = requestId,
        tachyonAuthToken = (auth.tachyonToken ?: ByteArray(0)).bs(),
        configVersion = CONFIG_VERSION
      ),
      TTL = when {
        customTtl != 0L -> customTtl
        omitTtl -> 0L
        else -> auth.tachyonTtlMicros
      },
      destRegistrationIDs = listOfNotNull(auth.destRegId?.toString())
    )
  }

  private fun sendNoResponse(action: ActionType, data: Message<*, *>?, requestId: String = UUID.randomUUID().toString(), omitTtl: Boolean = false, messageType: MessageType = MessageType.BUGLE_MESSAGE, customTtl: Long = 0, encrypt: Boolean = true) {
    post(SEND_URL, buildRpc(action, data, requestId, messageType, omitTtl, customTtl, encrypt), PBLITE).close()
  }

  /** Sends a request to the phone and waits for its answer. */
  suspend fun request(
    action: ActionType,
    data: Message<*, *>?,
    messageType: MessageType = MessageType.BUGLE_MESSAGE,
    customTtl: Long = 0,
    encrypt: Boolean = true,
    timeoutMs: Long = RESPONSE_TIMEOUT_MS
  ): Incoming {
    val requestId = UUID.randomUUID().toString()
    val waiter = CompletableDeferred<Incoming>()
    waiters[requestId] = waiter
    try {
      post(SEND_URL, buildRpc(action, data, requestId, messageType, false, customTtl, encrypt), PBLITE).close()
      return withTimeout(timeoutMs) { waiter.await() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      throw GmException.Failed("Phone didn't answer ${action.name}")
    } finally {
      waiters.remove(requestId)
    }
  }

  private suspend fun <T> requestTyped(action: ActionType, data: Message<*, *>?, adapter: ProtoAdapter<T>, messageType: MessageType = MessageType.BUGLE_MESSAGE): T {
    val response = request(action, data, messageType)
    val bytes = response.decrypted ?: throw GmException.Failed("Empty answer to ${action.name}")
    return adapter.decode(bytes)
  }

  suspend fun listConversations(count: Int): ListConversationsResponse {
    val type = if (conversationsFetched) MessageType.BUGLE_MESSAGE else MessageType.BUGLE_ANNOTATION
    conversationsFetched = true
    return requestTyped(ActionType.LIST_CONVERSATIONS, ListConversationsRequest(count = count.toLong(), folder = ListConversationsRequest.Folder.INBOX), ListConversationsResponse.ADAPTER, type)
  }

  suspend fun fetchMessages(conversationId: String, count: Int, cursor: Cursor? = null): ListMessagesResponse {
    return requestTyped(ActionType.LIST_MESSAGES, ListMessagesRequest(conversationID = conversationId, count = count.toLong(), cursor = cursor), ListMessagesResponse.ADAPTER)
  }

  suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
    return requestTyped(ActionType.SEND_MESSAGE, request, SendMessageResponse.ADAPTER)
  }

  suspend fun markRead(conversationId: String, messageId: String) {
    request(ActionType.MESSAGE_READ, MessageReadRequest(conversationID = conversationId, messageID = messageId))
  }

  suspend fun getOrCreateConversation(request: GetOrCreateConversationRequest): GetOrCreateConversationResponse {
    return requestTyped(ActionType.GET_OR_CREATE_CONVERSATION, request, GetOrCreateConversationResponse.ADAPTER)
  }

  // Media

  /** Uploads [data] encrypted and returns the content to put in a message. */
  fun uploadMedia(data: ByteArray, fileName: String, mime: String): MediaContent {
    val key = GmCrypto.randomBytes(32)
    val encrypted = GmCrypto.encryptMedia(key, data)

    val start = StartMediaUploadRequest(attachmentType = 1, authData = authMessage(UUID.randomUUID().toString()), mobile = auth.mobile)
    val startRequest = uploadHeaders(Request.Builder(), encrypted.size, mime)
      .header("x-goog-upload-protocol", "resumable")
      .header("x-goog-upload-command", "start")
      .url(UPLOAD_URL)
      .post(Base64.encodeToString(start.encode(), Base64.NO_WRAP).toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType()))
      .build()
    val uploadUrl = call(startRequest) { it.header("x-goog-upload-url") } ?: throw GmException.Failed("No upload URL")

    val finish = uploadHeaders(Request.Builder(), encrypted.size, mime)
      .header("x-goog-upload-command", "upload, finalize")
      .header("x-goog-upload-offset", "0")
      .url(uploadUrl)
      .post(encrypted.toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType()))
      .build()
    var body = call(finish) { it.body.bytes() }
    val text = String(body)
    if (text.length % 4 == 0 && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
      body = Base64.decode(text, Base64.DEFAULT)
    }
    val uploaded = UploadMediaResponse.ADAPTER.decode(body).media ?: throw GmException.Failed("Upload returned no media")

    return MediaContent(
      format = GmMedia.formatFor(mime),
      mediaID = uploaded.mediaID,
      mediaName = fileName,
      size = data.size.toLong(),
      decryptionKey = key.bs(),
      mimeType = mime
    )
  }

  fun downloadMedia(mediaId: String, key: ByteString): ByteArray {
    val metadata = DownloadAttachmentRequest(
      info = AttachmentInfo(attachmentID = mediaId, encrypted = true),
      authData = authMessage(UUID.randomUUID().toString())
    )
    val request = Request.Builder()
      .url(UPLOAD_URL)
      .header("x-goog-download-metadata", Base64.encodeToString(metadata.encode(), Base64.NO_WRAP))
      .header("sec-ch-ua", SEC_UA)
      .header("sec-ch-ua-mobile", "?1")
      .header("sec-ch-ua-platform", "\"Android\"")
      .header("user-agent", USER_AGENT)
      .header("accept", "*/*")
      .header("origin", ORIGIN)
      .header("referer", "$ORIGIN/")
      .header("sec-fetch-site", "cross-site")
      .header("sec-fetch-mode", "cors")
      .header("sec-fetch-dest", "empty")
      .get()
      .build()
    val encrypted = call(request) { it.body.bytes() }
    return GmCrypto.decryptMedia(key.toByteArray(), encrypted)
  }

  private fun uploadHeaders(builder: Request.Builder, size: Int, mime: String): Request.Builder {
    return builder
      .header("sec-ch-ua", SEC_UA)
      .header("x-goog-upload-header-content-length", size.toString())
      .header("x-goog-upload-header-content-type", mime)
      .header("sec-ch-ua-mobile", "?1")
      .header("user-agent", USER_AGENT)
      .header("sec-ch-ua-platform", "\"Android\"")
      .header("accept", "*/*")
      .header("origin", ORIGIN)
      .header("referer", "$ORIGIN/")
      .header("sec-fetch-site", "cross-site")
      .header("sec-fetch-mode", "cors")
      .header("sec-fetch-dest", "empty")
  }

  // Tokens and config

  private fun authMessage(requestId: String) = AuthMessage(
    requestID = requestId,
    network = GOOGLE_NETWORK,
    tachyonAuthToken = (auth.tachyonToken ?: ByteArray(0)).bs(),
    configVersion = CONFIG_VERSION
  )

  private fun updateToken(data: TokenData?) {
    val token = data?.tachyonAuthToken?.takeIf { it.size > 0 } ?: throw GmException.Failed("No token in response")
    auth.tachyonToken = token.toByteArray()
    val ttlMicros = data.TTL.takeIf { it > 0 } ?: TimeUnit.HOURS.toMicros(24)
    auth.tachyonTtlMicros = ttlMicros
    auth.tachyonExpiryMs = System.currentTimeMillis() + ttlMicros / 1000
    onEvent(GmEvent.AuthChanged)
  }

  /** Renews the session token when it's within an hour of expiring. */
  @Synchronized
  fun refreshAuthToken() {
    if (auth.browser == null || auth.tachyonExpiryMs - System.currentTimeMillis() > TimeUnit.HOURS.toMillis(1)) return
    val requestId = UUID.randomUUID().toString()
    val timestamp = System.currentTimeMillis() * 1000
    val signature = GmCrypto.sign(GmCrypto.privateKey(auth.refreshPrivate), "$requestId:$timestamp".toByteArray())

    val payload = RegisterRefreshRequest(
      messageAuth = authMessage(requestId),
      currBrowserDevice = auth.browser,
      unixTimestamp = timestamp,
      signature = signature.bs(),
      parameters = RegisterRefreshRequest.Parameters(emptyArr = EmptyArr()),
      messageType = 2
    )
    val response = decode(post(REGISTER_REFRESH_URL, payload, PBLITE), RegisterRefreshResponse::class.java)
    updateToken(response.tokenData)
    Log.i(TAG, "Refreshed the session token")
  }

  fun fetchConfig(): Config {
    val request = Request.Builder()
      .url(CONFIG_URL)
      .apply { commonHeaders(this, null) }
      .header("sec-fetch-site", "same-origin")
      .removeHeader("x-user-agent")
      .removeHeader("origin")
      .get()
      .build()
    val config = call(request) { response -> decodeBody(response, Config::class.java) }
    config.deviceInfo?.deviceID?.takeIf { it.isNotBlank() }?.let {
      runCatching { auth.sessionId = UUID.fromString(it) }
    }
    return config
  }

  // Pairing through the Google account

  class Pairing internal constructor(
    val attemptId: UUID,
    val startMs: Long,
    val keys: java.security.KeyPair,
    val destPhoneTs: Long,
    val initPayload: ByteArray,
    val finishPayload: ByteArray
  ) {
    internal var serverInit: GaiaPairingResponseContainer? = null
    internal var nextKey: ByteArray? = null
  }

  private class PrimaryDevice(val regId: String, val unknownInt: Long, var unknownTs: Long = 0, var lastSeenMicros: Long = 0)

  /**
   * Starts pairing with Google Messages on the phone signed in to the same Google account, and returns the emoji the
   * user must tap there. Needs the Google cookies from sign-in.
   */
  suspend fun startGaiaPairing(): Pair<String, Pairing> {
    fetchConfig()

    val sessionBytes = auth.sessionId?.let { uuidBytes(it) } ?: ByteArray(16)
    val signIn = SignInGaiaRequest(
      authMessage = AuthMessage(requestID = UUID.randomUUID().toString(), network = GOOGLE_NETWORK, configVersion = CONFIG_VERSION),
      inner_ = SignInGaiaRequest.Inner(
        deviceID = SignInGaiaRequest.Inner.DeviceID(unknownInt1 = 3, deviceID = "messages-web-" + hex(sessionBytes)),
        someData = SignInGaiaRequest.Inner.Data(someData = auth.refreshPublic.bs())
      ),
      network = GOOGLE_NETWORK
    )
    val response = decode(post(SIGN_IN_GAIA_URL, signIn, PBLITE), SignInGaiaResponse::class.java)
    updateToken(response.tokenData)
    val device = response.deviceData?.deviceWrapper?.device ?: throw GmException.Failed("No device in sign-in response")
    auth.browser = device
    auth.mobile = device.copy(sourceID = device.sourceID.lowercase())

    val devices = mutableMapOf<String, PrimaryDevice>()
    response.deviceData.unknownItems2.filter { it.unknownInt4 == 1 }.forEach {
      devices[it.destOrSourceUUID] = PrimaryDevice(it.destOrSourceUUID, it.unknownBigInt7)
    }
    response.deviceData.unknownItems3.forEach { item ->
      devices[item.destOrSourceUUID]?.let {
        it.lastSeenMicros = item.unknownTimestampMicroseconds
        it.unknownTs = item.item8?.unknownTimestamp ?: 0
      }
    }
    val phone = devices.values.maxByOrNull { it.lastSeenMicros } ?: throw GmException.NoPhone()
    auth.destRegId = UUID.fromString(phone.regId)

    val connectedSignal = CompletableDeferred<Unit>()
    startLongPoll(loggedIn = false) { connectedSignal.complete(Unit) }
    withTimeout(30_000) { connectedSignal.await() }

    val keys = GmCrypto.generateP256()
    val (x, y) = GmCrypto.coordinates(keys.public)
    val finish = Ukey2Message(
      message_type = Ukey2Message.Type.CLIENT_FINISH,
      message_data = Ukey2ClientFinished(
        public_key = GenericPublicKey(
          type = PublicKeyType.EC_P256,
          ec_p256_public_key = EcP256PublicKey(x = (byteArrayOf(0) + x).bs(), y = (byteArrayOf(0) + y).bs())
        )
      ).encode().bs()
    ).encode()
    val init = Ukey2Message(
      message_type = Ukey2Message.Type.CLIENT_INIT,
      message_data = Ukey2ClientInit(
        version = 1,
        random = GmCrypto.randomBytes(32).bs(),
        cipher_commitments = listOf(Ukey2ClientInit.CipherCommitment(handshake_cipher = Ukey2HandshakeCipher.P256_SHA512, commitment = GmCrypto.sha512(finish).bs())),
        next_protocol = "AES_256_CBC-HMAC_SHA256"
      ).encode().bs()
    ).encode()

    val pairing = Pairing(UUID.randomUUID(), System.currentTimeMillis(), keys, phone.unknownTs, init, finish)
    val serverInit = sendGaiaPairing(pairing, ActionType.CREATE_GAIA_PAIRING_CLIENT_INIT, init, 20_000)
    return processServerInit(pairing, serverInit) to pairing
  }

  /** Waits for the user to tap the emoji on the phone, then saves the keys. */
  suspend fun finishGaiaPairing(pairing: Pairing) {
    val response = sendGaiaPairing(pairing, ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED, pairing.finishPayload, 5 * 60_000)
    if (response.finishErrorType != 0) {
      throw when (response.finishErrorCode) {
        GaiaPairingErrorCode.WRONG_VERIFICATION_CODE_SELECTED -> GmException.PairingFailed("The wrong emoji was tapped on the phone.")
        GaiaPairingErrorCode.USER_CANCELED_VERIFICATION, GaiaPairingErrorCode.USER_DENIED_VERIFICATION_NOT_ME -> GmException.PairingFailed("Pairing was cancelled on the phone.")
        else -> GmException.PairingFailed("Pairing didn't finish (${response.finishErrorCode.name}).")
      }
    }

    val nextKey = pairing.nextKey!!
    val clientKey = GmCrypto.hkdf(nextKey, ENCRYPTION_KEY_INFO, "client".toByteArray())
    val serverKey = GmCrypto.hkdf(nextKey, ENCRYPTION_KEY_INFO, "server".toByteArray())
    when (pairing.serverInit!!.confirmedKeyDerivationVersion) {
      0 -> {
        auth.aesKey = clientKey
        auth.hmacKey = serverKey
      }
      1 -> {
        val (first, second) = if (clientKey.contentHashCode() < serverKey.contentHashCode()) clientKey to serverKey else serverKey to clientKey
        val hash = GmCrypto.sha256(ENCRYPTION_KEY_INFO + first + second)
        auth.aesKey = GmCrypto.hkdf(hash, "Ditto salt 1".toByteArray(), "Ditto info 1".toByteArray())
        auth.hmacKey = GmCrypto.hkdf(hash, "Ditto salt 2".toByteArray(), "Ditto info 2".toByteArray())
      }
      else -> throw GmException.PairingFailed("Unsupported key version")
    }
    auth.pairingId = pairing.attemptId
    auth.destPhoneId = pairing.destPhoneTs
    onEvent(GmEvent.AuthChanged)
  }

  /** Tells the phone to forget this pairing. */
  fun unpair() {
    val pairingId = auth.pairingId ?: return
    sendNoResponse(ActionType.UNPAIR_GAIA_PAIRING, org.thoughtcrime.securesms.googlemessages.proto.RevokeGaiaPairingRequest(pairingAttemptID = pairingId.toString()))
  }

  private suspend fun sendGaiaPairing(pairing: Pairing, action: ActionType, data: ByteArray, timeoutMs: Long): GaiaPairingResponseContainer {
    val finishing = action == ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED
    val container = GaiaPairingRequestContainer(
      pairingAttemptID = pairing.attemptId.toString(),
      browserDetails = BROWSER_DETAILS,
      startTimestamp = pairing.startMs,
      data_ = data.bs(),
      proposedVerificationCodeVersion = if (finishing) 0 else 1,
      proposedKeyDerivationVersion = if (finishing) 0 else 1,
      privateAPIConfirmation = if (finishing) PRIVATE_API_CONFIRMATION else ""
    )
    val response = request(
      action,
      container,
      messageType = if (finishing) MessageType.BUGLE_MESSAGE else MessageType.GAIA_2,
      customTtl = TimeUnit.SECONDS.toMicros(300),
      encrypt = false,
      timeoutMs = timeoutMs
    )
    val bytes = response.unencrypted ?: throw GmException.Failed("Empty pairing answer")
    return GaiaPairingResponseContainer.ADAPTER.decode(bytes)
  }

  private fun processServerInit(pairing: Pairing, response: GaiaPairingResponseContainer): String {
    pairing.serverInit = response
    val message = Ukey2Message.ADAPTER.decode(response.data_)
    if (message.message_type != Ukey2Message.Type.SERVER_INIT) throw GmException.PairingFailed("Unexpected pairing message")
    val serverInit = Ukey2ServerInit.ADAPTER.decode(message.message_data)
    val key = serverInit.public_key?.ec_p256_public_key ?: throw GmException.PairingFailed("No key from the phone")
    val x = key.x.toByteArray().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
    val y = key.y.toByteArray().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
    val serverKey = GmCrypto.publicKeyFromCoordinates(x, y, pairing.keys.public)
    val shared = GmCrypto.sha256(GmCrypto.ecdh(pairing.keys.private, serverKey))
    val authInfo = pairing.initPayload + response.data_.toByteArray()
    val authKey = GmCrypto.hkdf(shared, "UKEY2 v1 auth".toByteArray(), authInfo)
    pairing.nextKey = GmCrypto.hkdf(shared, "UKEY2 v1 next".toByteArray(), authInfo)

    val number = ByteBuffer.wrap(authKey, 0, 4).int.toLong() and 0xFFFFFFFFL
    val emoji = when (response.confirmedVerificationCodeVersion) {
      0 -> GmEmoji.V0
      1 -> GmEmoji.V1
      else -> throw GmException.PairingFailed("Unsupported emoji version")
    }
    // Go converts the uint32 to int first; on 64-bit that keeps it positive.
    return emoji[(number % emoji.size).toInt()]
  }

  // HTTP

  private fun commonHeaders(builder: Request.Builder, contentType: String?) {
    builder
      .header("sec-ch-ua", SEC_UA)
      .header("x-user-agent", "grpc-web-javascript/0.1")
      .header("x-goog-api-key", API_KEY)
      .header("sec-ch-ua-mobile", "?1")
      .header("user-agent", USER_AGENT)
      .header("sec-ch-ua-platform", "\"Android\"")
      .header("accept", "*/*")
      .header("origin", ORIGIN)
      .header("sec-fetch-site", "cross-site")
      .header("sec-fetch-mode", "cors")
      .header("sec-fetch-dest", "empty")
      .header("referer", "$ORIGIN/")
      .header("accept-language", "en-US,en;q=0.9")
    contentType?.let { builder.header("content-type", it) }

    val cookies = CookieManager.getInstance().getCookie(ORIGIN) ?: throw GmException.LoggedOut("No Google cookies")
    builder.header("Cookie", cookies)
    val sapisid = cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("SAPISID=") }?.substringAfter('=')
      ?: throw GmException.LoggedOut("Not signed in to Google")
    builder.header("Authorization", sapisidHash(sapisid))
  }

  private fun buildPost(url: String, message: Message<*, *>, contentType: String): Request {
    val body = if (contentType == PBLITE) {
      Pblite.encode(message).toByteArray()
    } else {
      (message.adapter as ProtoAdapter<Message<*, *>>).encode(message)
    }
    return Request.Builder()
      .url(url)
      .apply { commonHeaders(this, contentType) }
      .post(body.toRequestBody(contentType.toMediaType()))
      .build()
  }

  private fun post(url: String, message: Message<*, *>, contentType: String): Response {
    val request = buildPost(url, message, contentType)
    var attempt = 0
    while (true) {
      attempt++
      val response = try {
        httpClient.newCall(request).execute()
      } catch (e: IOException) {
        throw GmException.Failed("Request failed: ${e.javaClass.simpleName}", e)
      }
      if (response.code < 500 || attempt >= 3) {
        if (response.code == 401 || response.code == 403) {
          response.close()
          throw GmException.LoggedOut("Google rejected the request (${response.code})")
        }
        if (!response.isSuccessful) {
          val error = response.body.string().take(300)
          response.close()
          throw GmException.Failed("Google returned ${response.code}: $error")
        }
        return response
      }
      response.close()
      Thread.sleep(attempt * 1_000L)
    }
  }

  private fun <T> call(request: Request, read: (Response) -> T): T {
    try {
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw GmException.Failed("Google returned ${response.code}")
        return read(response)
      }
    } catch (e: IOException) {
      throw GmException.Failed("Request failed: ${e.javaClass.simpleName}", e)
    }
  }

  private fun <T> decode(response: Response, cls: Class<T>): T = response.use { decodeBody(it, cls) }

  @Suppress("UNCHECKED_CAST")
  private fun <T> decodeBody(response: Response, cls: Class<T>): T {
    val bytes = response.body.bytes()
    val type = response.header("Content-Type").orEmpty()
    return if (type.startsWith(PROTOBUF)) {
      (cls.getField("ADAPTER").get(null) as ProtoAdapter<T>).decode(bytes)
    } else {
      Pblite.decode(String(bytes), cls)
    }
  }

  private fun sapisidHash(sapisid: String): String {
    val timestamp = System.currentTimeMillis() / 1000
    val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $sapisid $ORIGIN".toByteArray())
    return "SAPISIDHASH ${timestamp}_${hex(digest)}"
  }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}

/** What the client reports to [GoogleMessages]. */
sealed interface GmEvent {
  data class ConnectionChanged(val connected: Boolean) : GmEvent
  data class ConversationUpdated(val conversation: Conversation) : GmEvent
  data class MessageUpdated(val message: GmMessage, val isOld: Boolean) : GmEvent
  data class SettingsUpdated(val settings: org.thoughtcrime.securesms.googlemessages.proto.Settings) : GmEvent
  data class UserAlert(val type: Int) : GmEvent
  data object AuthChanged : GmEvent
  data object LoggedOut : GmEvent
}

sealed class GmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class LoggedOut(message: String) : GmException(message)
  class NoPhone : GmException("No phone with Google Messages was found on this Google account")
  class PairingFailed(message: String) : GmException(message)
  class Failed(message: String, cause: Throwable? = null) : GmException(message, cause)
}
