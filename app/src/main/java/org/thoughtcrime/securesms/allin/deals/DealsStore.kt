package org.thoughtcrime.securesms.allin.deals

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.File

/** One deal card, as last seen on the Deal Reader, with any local changes applied. */
@Serializable
data class Deal(
  val id: Long,
  val title: String,
  val link: String? = null,
  val price: String? = null,
  val feedTitle: String? = null,
  val summary: String? = null,
  val imageUrl: String? = null,
  val publishedAt: Long = 0,
  val fetchedAt: Long = 0,
  val read: Boolean = false,
  val starred: Boolean = false,
  val expired: Boolean = false,
  val score: Int? = null,
  val comments: Int? = null,
  val stores: List<StoreLink> = emptyList()
)

@Serializable
data class StoreLink(val url: String, val host: String? = null)

/** Everything kept on the phone: the cards, changes made offline that the server hasn't heard about yet, sync status. */
@Serializable
data class DealsData(
  val deals: List<Deal> = emptyList(),
  /** Deal ID → starred, for stars changed since the last successful sync. */
  val pendingStars: Map<Long, Boolean> = emptyMap(),
  /** Deals marked read since the last successful sync. */
  val pendingReads: Set<Long> = emptySet(),
  val lastSync: Long = 0,
  val unreadOnServer: Int? = null
) {
  val pendingCount: Int get() = pendingStars.size + pendingReads.size
}

/**
 * AllIn: the offline copy of the Deal Reader. Kept as one JSON file in the app's private storage, plus small pictures
 * (see [DealsSync]), so the list can be read and starred without a connection. Changes are applied here at once and
 * queued for [DealsSync] to send.
 */
object DealsStore {

  private val TAG = Log.tag(DealsStore::class.java)

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

  private val lock = Any()

  private val _data = MutableStateFlow(DealsData())
  private var loaded = false

  val data: StateFlow<DealsData>
    get() {
      ensureLoaded()
      return _data.asStateFlow()
    }

  private val context: Context get() = AppDependencies.application

  fun dir(): File = File(context.filesDir, "allin-deals").apply { mkdirs() }

  fun imageFile(id: Long): File = File(File(dir(), "img").apply { mkdirs() }, "$id.jpg")

  private fun file(): File = File(dir(), "deals.json")

  private fun ensureLoaded() {
    synchronized(lock) {
      if (loaded) return
      loaded = true
      val f = file()
      if (!f.exists()) return
      try {
        _data.value = json.decodeFromString(DealsData.serializer(), f.readText())
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read saved deals; starting empty", e)
      }
    }
  }

  fun current(): DealsData = data.value

  /** Applies [change] and saves the result. */
  fun update(change: (DealsData) -> DealsData) {
    ensureLoaded()
    synchronized(lock) {
      val next = change(_data.value)
      _data.value = next
      save(next)
    }
  }

  fun setStarred(id: Long, starred: Boolean) {
    update { d ->
      d.copy(
        deals = d.deals.map { if (it.id == id) it.copy(starred = starred) else it },
        pendingStars = d.pendingStars + (id to starred)
      )
    }
  }

  fun markRead(ids: Collection<Long>) {
    val wanted = ids.toSet()
    update { d ->
      val newly = d.deals.filter { it.id in wanted && !it.read }.map { it.id }.toSet()
      if (newly.isEmpty()) {
        d
      } else {
        d.copy(
          deals = d.deals.map { if (it.id in newly) it.copy(read = true) else it },
          pendingReads = d.pendingReads + newly,
          unreadOnServer = d.unreadOnServer?.let { (it - newly.size).coerceAtLeast(0) }
        )
      }
    }
  }

  /** Signing out: forget everything, including unsent changes. */
  fun clear() {
    synchronized(lock) {
      loaded = true
      _data.value = DealsData()
      dir().deleteRecursively()
    }
  }

  private fun save(data: DealsData) {
    try {
      val f = file()
      val tmp = File(f.parentFile, "deals.json.tmp")
      tmp.writeText(json.encodeToString(DealsData.serializer(), data))
      if (!tmp.renameTo(f)) {
        f.delete()
        tmp.renameTo(f)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't save deals", e)
    }
  }
}
