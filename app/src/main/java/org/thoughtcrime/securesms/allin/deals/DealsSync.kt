package org.thoughtcrime.securesms.allin.deals

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.FileOutputStream

/**
 * AllIn: keeps [DealsStore] in step with the Deal Reader.
 *
 * A sync first sends the stars and reads made on the phone, then downloads the newest unread deals (up to
 * [MAX_UNREAD]) and all starred ones (up to [MAX_STARRED]), and saves a small picture for each so the list works
 * offline. It runs every [INTERVAL_MS] while AllIn is running, whenever the phone gets a connection back, and when
 * the Deals screen or the chat list opens.
 */
object DealsSync {

  private val TAG = Log.tag(DealsSync::class.java)

  private const val MAX_UNREAD = 300
  private const val MAX_STARRED = 200
  private const val PAGE = 100
  private const val INTERVAL_MS = 30 * 60_000L

  /** A sync started by opening a screen is skipped if the last one was this recent (unless there are changes to send). */
  private const val FRESH_MS = 2 * 60_000L

  private const val IMAGE_MAX_PX = 320

  sealed interface Status {
    data object Idle : Status
    data object Syncing : Status
    data object Offline : Status
    data object SignedOut : Status
    data class Failed(val message: String) : Status
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private var started = false

  private val _status = MutableStateFlow<Status>(Status.Idle)
  val status: StateFlow<Status> = _status.asStateFlow()

  /** Bumped whenever new pictures were saved, so screens redraw them. */
  private val _imagesVersion = MutableStateFlow(0)
  val imagesVersion: StateFlow<Int> = _imagesVersion.asStateFlow()

  private val active: Boolean get() = SignalStore.deals.enabled && SignalStore.deals.signedIn

  /** Called at app start. Cheap and safe to call again (e.g. when Deals is switched on). */
  @JvmStatic
  fun start(context: Context) {
    synchronized(this) {
      if (started) return
      started = true
    }

    // Read the saved copy now (off the main thread), so the chat list doesn't wait for it.
    scope.launch { DealsStore.current() }

    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    try {
      connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && _status.value == Status.Offline) {
            Log.i(TAG, "Back online.")
            requestSync()
          }
        }
      })
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't watch the connection", e)
    }

    scope.launch {
      while (true) {
        if (active) sync(force = false)
        delay(INTERVAL_MS)
      }
    }
  }

  /** Sync soon in the background, unless one just ran and nothing changed on the phone. */
  fun requestSync(force: Boolean = false) {
    if (!active) return
    scope.launch { sync(force) }
  }

  fun isOnline(): Boolean {
    val connectivity = AppDependencies.application.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  private suspend fun sync(force: Boolean) {
    if (mutex.isLocked) return
    mutex.withLock {
      val before = DealsStore.current()
      if (!force && before.pendingCount == 0 && System.currentTimeMillis() - before.lastSync < FRESH_MS) return
      if (!isOnline()) {
        _status.value = Status.Offline
        return
      }

      _status.value = Status.Syncing
      _status.value = try {
        sendChanges(before)
        download()
        Status.Idle
      } catch (e: DealsException.NotSignedIn) {
        Log.w(TAG, "Deal Reader session ended.")
        Status.SignedOut
      } catch (e: DealsException.Offline) {
        Log.w(TAG, "Deal Reader unreachable: ${e.cause?.javaClass?.simpleName}")
        Status.Offline
      } catch (e: DealsException) {
        Log.w(TAG, "Deal Reader sync failed", e)
        Status.Failed(e.message ?: "Sync failed")
      }

      if (_status.value == Status.Idle) {
        saveImages()
      }
    }
  }

  private fun sendChanges(snapshot: DealsData) {
    val sentStars = snapshot.pendingStars
    val sentReads = snapshot.pendingReads
    if (sentStars.isEmpty() && sentReads.isEmpty()) return

    Log.i(TAG, "Sending ${sentStars.size} star(s) and ${sentReads.size} read(s).")
    if (sentReads.isNotEmpty()) DealsApi.markRead(sentReads)
    sentStars.forEach { (id, starred) -> DealsApi.setStarred(id, starred) }

    // Only forget what was sent; anything changed meanwhile stays queued.
    DealsStore.update { d ->
      d.copy(
        pendingStars = d.pendingStars.filterNot { (id, starred) -> sentStars[id] == starred },
        pendingReads = d.pendingReads - sentReads
      )
    }
  }

  private fun download() {
    val unread = fetch("unread", MAX_UNREAD)
    val starred = fetch("starred", MAX_STARRED)
    val unreadTotal = unreadCount()

    DealsStore.update { d ->
      // Re-apply anything the user changed while this sync was running.
      val merged = (unread + starred)
        .distinctBy { it.id }
        .sortedWith(compareByDescending<Deal> { it.fetchedAt }.thenByDescending { it.id })
        .map { deal ->
          var x = deal
          d.pendingStars[deal.id]?.let { x = x.copy(starred = it) }
          if (deal.id in d.pendingReads) x = x.copy(read = true)
          x
        }
      d.copy(deals = merged, lastSync = System.currentTimeMillis(), unreadOnServer = unreadTotal ?: unread.size)
    }
    Log.i(TAG, "Synced ${unread.size} unread and ${starred.size} starred deal(s).")
  }

  private fun fetch(view: String, max: Int): List<Deal> {
    val out = mutableListOf<Deal>()
    var before: String? = null
    while (out.size < max) {
      val page = DealsApi.items(view, before, minOf(PAGE, max - out.size))
      out += page.items
      val last = page.items.lastOrNull() ?: break
      if (!page.more) break
      before = "${last.fetchedAt}:${last.id}"
    }
    return out
  }

  /** Total unread on the server (can be more than the phone keeps), or null if the site didn't say. */
  private fun unreadCount(): Int? {
    return try {
      DealsApi.unreadTotal()
    } catch (e: DealsException.Failed) {
      null
    }
  }

  private suspend fun saveImages() {
    val deals = DealsStore.current().deals
    val keep = deals.map { "${it.id}.jpg" }.toSet()
    DealsStore.imageFile(0).parentFile?.listFiles()?.forEach { if (it.name !in keep) it.delete() }

    val missing = deals.filter { it.imageUrl != null && !DealsStore.imageFile(it.id).exists() }
    if (missing.isEmpty()) return

    val permits = Semaphore(4)
    var saved = 0
    missing.chunked(20).forEach { batch ->
      val results = batch.map { deal ->
        scope.async { permits.withPermit { saveImage(deal) } }
      }.awaitAll()
      saved += results.count { it }
      _imagesVersion.value++
    }
    Log.i(TAG, "Saved $saved of ${missing.size} picture(s).")
  }

  private fun saveImage(deal: Deal): Boolean {
    val url = deal.imageUrl ?: return false
    return try {
      val bytes = DealsApi.imageClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return false
        val body = response.body
        if (body.contentLength() > 15_000_000) return false
        body.bytes()
      }

      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
      var sample = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX_PX) sample *= 2

      val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
      val scale = IMAGE_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
      val bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
      } else {
        decoded
      }

      val file = DealsStore.imageFile(deal.id)
      val tmp = java.io.File(file.parentFile, "${deal.id}.tmp")
      FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
      tmp.renameTo(file)
    } catch (e: Exception) {
      false
    }
  }
}
