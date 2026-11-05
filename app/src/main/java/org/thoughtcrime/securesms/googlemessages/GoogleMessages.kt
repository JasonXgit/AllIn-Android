package org.thoughtcrime.securesms.googlemessages

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.googlemessages.proto.ContactNumber
import org.thoughtcrime.securesms.googlemessages.proto.Conversation
import org.thoughtcrime.securesms.googlemessages.proto.ConversationStatus
import org.thoughtcrime.securesms.googlemessages.proto.ConversationType
import org.thoughtcrime.securesms.googlemessages.proto.GetOrCreateConversationRequest
import org.thoughtcrime.securesms.googlemessages.proto.MediaContent
import org.thoughtcrime.securesms.googlemessages.proto.MessageContent
import org.thoughtcrime.securesms.googlemessages.proto.MessageInfo
import org.thoughtcrime.securesms.googlemessages.proto.MessagePayload
import org.thoughtcrime.securesms.googlemessages.proto.MessageStatusType
import org.thoughtcrime.securesms.googlemessages.proto.SIMCard
import org.thoughtcrime.securesms.googlemessages.proto.SendMessageRequest
import org.thoughtcrime.securesms.googlemessages.proto.SendMessageResponse
import org.thoughtcrime.securesms.googlevoice.GoogleVoiceInbox
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.thoughtcrime.securesms.googlemessages.proto.Message as GmMessage

/**
 * AllIn: the user's phone texts (SMS, MMS and RCS), through Google Messages on their phone.
 *
 * Runs while the feature is on and paired, keeping one [GmClient] connected. Conversations and recent messages are
 * kept in memory only, never written to Signal's database or sent to Signal's servers (except to the user's own
 * linked AllIn Desktop, end-to-end encrypted, see [GoogleMessagesRelay]).
 */
object GoogleMessages {

  private val TAG = Log.tag(GoogleMessages::class.java)

  private const val CONVERSATION_COUNT = 50
  private const val MESSAGE_COUNT = 40

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** One text in a conversation, as AllIn shows it. */
  data class Text(
    val id: String,
    val conversationId: String,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val status: Int,
    val attachments: List<Attachment>,
    val senderName: String?,
    val tmpId: String
  ) {
    val failed: Boolean get() = outgoing && status in FAILED_STATUSES
    val sending: Boolean get() = outgoing && status in SENDING_STATUSES
  }

  data class Attachment(val mediaId: String, val key: String, val mime: String, val name: String, val isImage: Boolean)

  @Volatile private var client: GmClient? = null
  @Volatile private var job: Job? = null

  private val conversations = ConcurrentHashMap<String, Conversation>()
  private val messages = ConcurrentHashMap<String, List<Text>>()
  @Volatile private var sims: List<SIMCard> = emptyList()

  /** Bumped whenever anything shown changes; screens watch it. */
  private val _changes = MutableStateFlow(0L)
  val changes: StateFlow<Long> = _changes

  @Volatile
  var status: String = "Not running"
    private set

  fun isActive(): Boolean = SignalStore.googleMessages.enabled && SignalStore.googleMessages.paired

  /** Starts or stops the connection to match the settings. Safe to call any time. */
  @JvmStatic
  @Synchronized
  fun update() {
    if (isActive()) {
      if (client == null) start()
    } else {
      stop()
    }
  }

  private fun start() {
    val json = SignalStore.googleMessages.authJson ?: return
    val auth = try {
      GmAuth.fromJson(json)
    } catch (e: Exception) {
      Log.w(TAG, "Saved pairing is unreadable", e)
      SignalStore.googleMessages.authJson = null
      return
    }
    Log.i(TAG, "Starting")
    val newClient = GmClient(auth) { onEvent(auth, it) }
    client = newClient
    status = "Connecting…"
    job = scope.launch {
      try {
        runCatching { newClient.fetchConfig() }.onFailure { Log.w(TAG, "Config fetch failed", it) }
        newClient.connect()
        delay(3_000)
        syncConversations()
      } catch (e: GmException.LoggedOut) {
        onLoggedOut()
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't connect", e)
        status = "Couldn't reach Google Messages. Retrying…"
      }
    }
  }

  @Synchronized
  private fun stop() {
    if (client == null) return
    Log.i(TAG, "Stopping")
    job?.cancel()
    client?.close()
    client = null
    conversations.clear()
    messages.clear()
    status = "Not running"
    changed()
  }

  /** Forgets the pairing (and tells the phone, if it can be reached). */
  fun unpair() {
    val old = client
    scope.launch { runCatching { old?.unpair() } }
    SignalStore.googleMessages.authJson = null
    stop()
  }

  /** Saves a finished pairing and connects. */
  fun onPaired(auth: GmAuth) {
    SignalStore.googleMessages.authJson = auth.toJson()
    SignalStore.googleMessages.enabled = true
    update()
  }

  private fun onEvent(auth: GmAuth, event: GmEvent) {
    when (event) {
      GmEvent.AuthChanged -> if (client?.auth === auth) SignalStore.googleMessages.authJson = auth.toJson()
      GmEvent.LoggedOut -> onLoggedOut()
      is GmEvent.ConnectionChanged -> {
        status = if (event.connected) "Connected · new texts arrive right away" else "Reconnecting…"
        if (event.connected && conversations.isNotEmpty()) scope.launch { runCatching { syncConversations() } }
      }
      is GmEvent.ConversationUpdated -> {
        conversations[event.conversation.conversationID] = event.conversation
        changed()
        GoogleMessagesRelay.onThreadsChanged()
      }
      is GmEvent.MessageUpdated -> onMessage(event.message, event.isOld)
      is GmEvent.SettingsUpdated -> sims = event.settings.SIMCards
      is GmEvent.UserAlert -> Unit
    }
  }

  private fun onLoggedOut() {
    Log.w(TAG, "Google Messages ended the session")
    status = "Unpaired by Google Messages. Pair again in Settings."
    SignalStore.googleMessages.authJson = null
    scope.launch { stop() }
  }

  private fun onMessage(message: GmMessage, isOld: Boolean) {
    val text = message.toText() ?: return
    val existing = messages[text.conversationId]
    if (existing != null) {
      messages[text.conversationId] = (existing.filterNot { it.id == text.id || (text.tmpId.isNotEmpty() && it.tmpId == text.tmpId) } + text).sortedBy { it.timestamp }.takeLast(200)
    }
    changed()
    GoogleMessagesRelay.onMessage(text)
    if (!isOld && !text.outgoing) {
      GoogleMessagesNotifications.onIncoming(text, conversationName(text.conversationId))
    }
  }

  private suspend fun syncConversations() {
    val c = client ?: return
    val response = c.listConversations(CONVERSATION_COUNT)
    response.conversations.forEach { conversations[it.conversationID] = it }
    if (SignalStore.googleMessages.notifiedUpTo == 0L) {
      SignalStore.googleMessages.notifiedUpTo = System.currentTimeMillis()
    }
    status = "Connected · ${response.conversations.size} conversations"
    changed()
    GoogleMessagesRelay.onThreadsChanged()
  }

  private fun changed() {
    _changes.value = _changes.value + 1
    AppDependencies.databaseObserver.notifyConversationListListeners()
  }

  // For the chat list

  fun threads(): List<GoogleVoiceInbox.ThreadSummary> {
    if (!isActive()) return emptyList()
    return conversationList().map { conv ->
      GoogleVoiceInbox.ThreadSummary(
        listId = listIdFor(conv.conversationID),
        threadId = conv.conversationID,
        name = conv.name.ifBlank { "Text" },
        snippet = conv.latestMessage?.displayContent.orEmpty(),
        dateMs = conv.lastMessageTimestamp / 1000,
        unread = conv.unread,
        lastMessageOutgoing = conv.latestMessage?.fromMe == 1L,
        source = GoogleVoiceInbox.Source.PHONE,
        rcs = conv.type == ConversationType.RCS
      )
    }
  }

  fun conversationList(): List<Conversation> {
    return conversations.values
      .filter { it.status == ConversationStatus.ACTIVE || it.status == ConversationStatus.UNKNOWN_CONVERSATION_STATUS }
      .sortedByDescending { it.lastMessageTimestamp }
  }

  fun conversation(id: String): Conversation? = conversations[id]

  fun conversationName(id: String): String = conversations[id]?.name?.ifBlank { null } ?: "Text"

  fun cachedMessages(conversationId: String): List<Text>? = messages[conversationId]

  // Actions

  private fun requireClient(): GmClient = client ?: throw GmException.Failed("Not connected to Google Messages")

  /** Loads the newest messages of a conversation from the phone. */
  suspend fun loadMessages(conversationId: String): List<Text> {
    val response = requireClient().fetchMessages(conversationId, MESSAGE_COUNT)
    val loaded = response.messages.mapNotNull { it.toText() }.sortedBy { it.timestamp }
    messages[conversationId] = loaded
    changed()
    return loaded
  }

  suspend fun markRead(conversationId: String) {
    val conv = conversations[conversationId] ?: return
    if (!conv.unread) return
    conversations[conversationId] = conv.copy(unread = false)
    changed()
    GoogleMessagesNotifications.cancel(conversationId)
    val latest = conv.latestMessageID.ifBlank { messages[conversationId]?.lastOrNull()?.id } ?: return
    requireClient().markRead(conversationId, latest)
  }

  /** Sends a text (and optionally one photo or file) into a conversation. Throws if the phone refuses. */
  suspend fun send(conversationId: String, text: String, media: ByteArray?, mime: String?, fileName: String?) {
    val c = requireClient()
    val conv = conversations[conversationId]
    val tmpId = "tmp_" + UUID.randomUUID().toString().replace("-", "").take(12)
    val parts = mutableListOf<MessageInfo>()
    if (media != null && mime != null) {
      val content: MediaContent = c.uploadMedia(media, fileName ?: "photo.jpg", mime)
      parts += MessageInfo(mediaContent = content)
    }
    if (text.isNotEmpty()) {
      parts += MessageInfo(messageContent = MessageContent(content = text))
    }
    val outgoingId = conv?.defaultOutgoingID.orEmpty()
    val sim = sims.firstOrNull { it.gmessages_settings_SIMParticipant?.ID == outgoingId } ?: sims.firstOrNull()
    val request = SendMessageRequest(
      conversationID = conversationId,
      messagePayload = MessagePayload(
        tmpID = tmpId,
        conversationID = conversationId,
        participantID = outgoingId,
        messageInfo = parts,
        tmpID2 = tmpId
      ),
      gmessages_client_SIMPayload = sim?.gmessages_settings_SIMData?.gmessages_settings_SIMPayload,
      tmpID = tmpId
    )
    val response = c.sendMessage(request)
    if (response.status != SendMessageResponse.Status.SUCCESS) {
      throw GmException.Failed("Google Messages didn't send it (${response.status.name})")
    }
  }

  /** Finds or starts the conversation with [numbers] (E.164) and returns its ID. */
  suspend fun conversationFor(numbers: List<String>): String {
    val response = requireClient().getOrCreateConversation(
      GetOrCreateConversationRequest(numbers = numbers.map { ContactNumber(mysteriousInt = 2, number = it, number2 = it) })
    )
    val conv = response.conversation ?: throw GmException.Failed("Couldn't start that conversation")
    conversations[conv.conversationID] = conv
    changed()
    return conv.conversationID
  }

  fun downloadMedia(attachment: Attachment): ByteArray {
    return requireClient().downloadMedia(attachment.mediaId, okio.ByteString.of(*Base64.decode(attachment.key, Base64.DEFAULT)))
  }

  // Conversion

  private fun GmMessage.toText(): Text? {
    val code = messageStatus?.status?.value ?: return null
    if (code >= 200 || code == MessageStatusType.MESSAGE_DELETED.value) return null
    val textParts = mutableListOf<String>()
    subject?.takeIf { it.isNotBlank() }?.let { textParts += it }
    val attachments = mutableListOf<Attachment>()
    for (info in messageInfo) {
      info.messageContent?.content?.takeIf { it.isNotEmpty() }?.let { textParts += it }
      info.mediaContent?.let { m ->
        if (m.mediaID.isNotEmpty()) {
          attachments += Attachment(m.mediaID, Base64.encodeToString(m.decryptionKey.toByteArray(), Base64.NO_WRAP), m.mimeType, m.mediaName, GmMedia.isImage(m.format, m.mimeType))
        }
      }
      info.locationContent?.messageContent?.content?.takeIf { it.isNotEmpty() }?.let { textParts += it }
    }
    val pending = when (messageStatus.status) {
      MessageStatusType.INCOMING_YET_TO_MANUAL_DOWNLOAD -> "Picture message (download it in Google Messages)"
      MessageStatusType.INCOMING_AUTO_DOWNLOADING, MessageStatusType.INCOMING_MANUAL_DOWNLOADING,
      MessageStatusType.INCOMING_RETRYING_AUTO_DOWNLOAD, MessageStatusType.INCOMING_RETRYING_MANUAL_DOWNLOAD -> "Downloading…"
      MessageStatusType.INCOMING_DOWNLOAD_FAILED -> "Picture message didn't download"
      else -> null
    }
    pending?.let { textParts += it }
    if (textParts.isEmpty() && attachments.isEmpty()) return null
    val outgoing = code < 100
    return Text(
      id = messageID,
      conversationId = conversationID,
      text = textParts.joinToString("\n"),
      timestamp = timestamp / 1000,
      outgoing = outgoing,
      status = code,
      attachments = attachments,
      senderName = if (outgoing) null else senderParticipant?.fullName?.ifBlank { null },
      tmpId = tmpID
    )
  }

  private val FAILED_STATUSES: Set<Int> = MessageStatusType.entries.filter { it.name.startsWith("OUTGOING_FAILED") || it == MessageStatusType.OUTGOING_CANCELED }.map { it.value }.toSet()

  private val SENDING_STATUSES: Set<Int> = setOf(
    MessageStatusType.OUTGOING_YET_TO_SEND, MessageStatusType.OUTGOING_SENDING, MessageStatusType.OUTGOING_RESENDING,
    MessageStatusType.OUTGOING_AWAITING_RETRY, MessageStatusType.OUTGOING_SEND_AFTER_PROCESSING, MessageStatusType.OUTGOING_VALIDATING
  ).map { it.value }.toSet()

  fun listIdFor(conversationId: String): Long = -(3_000_000_000L + (conversationId.hashCode().toLong() and 0x7fffffffL))
}
