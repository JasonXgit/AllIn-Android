package org.thoughtcrime.securesms.googlevoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.signal.core.util.logging.Log
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * AllIn: watches Google Voice for new texts while AllIn is running, and hands them to [GoogleVoiceNotifications].
 *
 * Google won't push to AllIn through Firebase (Google blocks AllIn from Signal's Firebase project), so this keeps the
 * Google Voice website's realtime channel open instead; AllIn's process stays alive anyway for Signal's own
 * background connection. Any event on the channel triggers a check of the thread list. If the channel can't be
 * used, the thread list is checked every minute instead.
 */
object GoogleVoiceMonitor {

  private val TAG = Log.tag(GoogleVoiceMonitor::class.java)

  private val LIVE_CHECK_MS = TimeUnit.MINUTES.toMillis(5)
  private val FALLBACK_CHECK_MS = TimeUnit.MINUTES.toMillis(1)
  private val RESUBSCRIBE_MS = TimeUnit.HOURS.toMillis(1)
  private val MAX_BACKOFF_MS = TimeUnit.MINUTES.toMillis(10)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val checkRequests = Channel<Unit>(Channel.CONFLATED)

  private var job: Job? = null

  @Volatile
  private var live = false

  /** A short description of the connection, for the settings screen. */
  @Volatile
  var status: String = "Not running"
    private set

  /** Starts or stops watching to match the Google Voice settings. Safe to call any time. */
  @JvmStatic
  @Synchronized
  fun update() {
    if (GoogleVoiceInbox.isActive()) {
      if (job?.isActive != true) {
        Log.i(TAG, "Starting")
        job = scope.launch {
          launch { checkLoop() }
          realtimeLoop()
        }
      }
    } else if (job != null) {
      Log.i(TAG, "Stopping")
      job?.cancel()
      job = null
      live = false
      status = "Not running"
    }
  }

  /** Asks for a check of the thread list soon, e.g. after a realtime event. */
  fun requestCheck() {
    checkRequests.trySend(Unit)
  }

  private suspend fun checkLoop() {
    while (true) {
      val threads = GoogleVoiceInbox.refresh()
      if (threads != null) {
        GoogleVoiceNotifications.onThreads(threads)
      }
      withTimeoutOrNull(if (live) LIVE_CHECK_MS else FALLBACK_CHECK_MS) { checkRequests.receive() }
    }
  }

  private suspend fun realtimeLoop() {
    var failures = 0
    while (scope.isActive) {
      try {
        val gSessionId = GoogleVoiceApi.chooseRealtimeServer()
        val channelId = GoogleVoiceApi.createRealtimeChannel(gSessionId)
        val subscribedAt = System.currentTimeMillis()
        var ackId = 0L
        var reconnect = false
        Log.i(TAG, "Realtime channel open")

        while (!reconnect && System.currentTimeMillis() - subscribedAt < RESUBSCRIBE_MS) {
          GoogleVoiceApi.pollRealtimeChannel(gSessionId, channelId, ackId) { chunk ->
            for (event in parseChunk(chunk)) {
              ackId = maxOf(ackId, event.arrayId)
              when {
                event.reconnect -> reconnect = true
                !event.noop -> requestCheck()
              }
            }
          }
          if (!live) {
            Log.i(TAG, "Realtime channel connected")
          }
          live = true
          failures = 0
          status = "Live: new texts arrive right away"
        }
      } catch (e: GoogleVoiceException.NotSignedIn) {
        Log.w(TAG, "Signed out", e)
        live = false
        status = "Signed out by Google. Sign in again."
        delay(MAX_BACKOFF_MS)
      } catch (e: GoogleVoiceException.Failed) {
        failures++
        Log.w(TAG, "Realtime channel failed ($failures in a row)", e)
        live = false
        status = "Live updates unavailable, checking every minute"
        delay(min(failures * 15_000L, MAX_BACKOFF_MS))
      }
    }
  }

  private class Event(val arrayId: Long, val noop: Boolean, val reconnect: Boolean)

  /**
   * A chunk is a JSON array of events, each `[arrayId, payload]`. Keep-alives are `[n, ["noop"]]`; a payload of
   * `[[data, [true]]]` asks the client to reconnect; anything else is a change worth checking for.
   */
  private fun parseChunk(chunk: String): List<Event> {
    return try {
      val entries = JSONArray(chunk)
      (0 until entries.length()).mapNotNull { i ->
        val entry = entries.optJSONArray(i) ?: return@mapNotNull null
        val payload = entry.optJSONArray(1)
        val noop = payload?.optString(0) == "noop"
        val reconnect = payload != null && payload.length() == 1 && payload.optJSONArray(0)?.optJSONArray(1)?.optBoolean(0) == true
        Event(entry.optLong(0), noop, reconnect)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't parse realtime chunk", e)
      listOf(Event(0, noop = false, reconnect = false))
    }
  }
}
