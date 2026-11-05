package org.thoughtcrime.securesms.googlevoice

import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.googlevoice.proto.Message
import org.thoughtcrime.securesms.googlevoice.proto.Thread
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * AllIn: the user's recent Google Voice text threads, shown in the chat list next to Signal chats.
 *
 * Kept in memory only and refreshed while the chat list is open, so Google Voice messages are never written to
 * disk unencrypted and never touch Signal's database, storage service or servers.
 */
object GoogleVoiceInbox {

  private val TAG = Log.tag(GoogleVoiceInbox::class.java)

  data class ThreadSummary(
    /** Negative, so it can never collide with a Signal thread ID in the chat list. */
    val listId: Long,
    val threadId: String,
    val name: String,
    val snippet: String,
    val dateMs: Long,
    val unread: Boolean,
    val lastMessageOutgoing: Boolean,
    val source: Source = Source.GOOGLE_VOICE,
    /** Phone texts only: the conversation uses RCS (chat features) rather than SMS/MMS. */
    val rcs: Boolean = false
  )

  /** Where a chat-list text thread comes from. */
  enum class Source { GOOGLE_VOICE, PHONE }

  @Volatile
  private var threads: List<ThreadSummary> = emptyList()

  fun isActive(): Boolean = SignalStore.googleVoice.enabled && SignalStore.googleVoice.signedIn

  /** Threads to show, newest first; empty while the feature is off. */
  fun threads(): List<ThreadSummary> = if (isActive()) threads else emptyList()

  fun find(listId: Long): ThreadSummary? = threads.firstOrNull { it.listId == listId }

  /** AllIn: Google Voice threads and phone texts together, newest first, for the chat list. */
  @JvmStatic
  fun allTextThreads(): List<ThreadSummary> {
    val phone = org.thoughtcrime.securesms.googlemessages.GoogleMessages.threads()
    return if (phone.isEmpty()) threads() else (threads() + phone).sortedByDescending { it.dateMs }
  }

  @JvmStatic
  fun findAny(listId: Long): ThreadSummary? = find(listId) ?: org.thoughtcrime.securesms.googlemessages.GoogleMessages.threads().firstOrNull { it.listId == listId }

  /** Fetches the latest threads and redraws the chat list if anything changed. Returns the threads, or null on failure. */
  @WorkerThread
  fun refresh(): List<Thread>? {
    if (!isActive()) {
      update(emptyList())
      return null
    }

    return try {
      val threads = GoogleVoiceApi.listThreads().threads
      val summaries = threads
        .filter { it.isText }
        .mapNotNull { it.toSummary() }
        .sortedByDescending { it.dateMs }
      update(summaries)
      threads
    } catch (e: GoogleVoiceException) {
      Log.w(TAG, "Refresh failed", e)
      null
    }
  }

  /** Shows [threadId] as read straight away, without waiting for the next refresh. */
  fun markReadLocally(threadId: String) {
    update(threads.map { if (it.threadId == threadId) it.copy(unread = false) else it })
  }

  private fun update(newThreads: List<ThreadSummary>) {
    if (newThreads != threads) {
      threads = newThreads
      AppDependencies.databaseObserver.notifyConversationListListeners()
    }
  }

  private fun Thread.toSummary(): ThreadSummary? {
    val latest = messages.maxByOrNull { it.timestamp } ?: return null
    val names = if (contacts.isNotEmpty()) {
      contacts.map { contact -> GoogleVoiceContacts.displayName(contact.phoneNumber, contact.name) }
    } else {
      phoneNumbers.map { GoogleVoiceContacts.displayName(it, null) }
    }.filter { it.isNotBlank() }

    return ThreadSummary(
      listId = listIdFor(ID),
      threadId = ID,
      name = names.joinToString(", ").ifBlank { "Google Voice" },
      snippet = latest.snippet(),
      dateMs = latest.timestamp,
      unread = !read,
      lastMessageOutgoing = latest.type == Message.Type.SMS_OUT
    )
  }

  fun Message.snippet(): String {
    val mms = MMS
    return when {
      text.isNotBlank() -> text
      mms != null && mms.text.isNotBlank() -> mms.text
      mms != null && mms.attachments.isNotEmpty() -> "Photo"
      else -> ""
    }
  }

  private fun listIdFor(threadId: String): Long = -(1_000_000_000L + (threadId.hashCode().toLong() and 0x7fffffffL))
}
