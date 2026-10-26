package org.thoughtcrime.securesms.allin.family

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import java.util.concurrent.TimeUnit

/** AllIn: what location access Family has; "always" is needed for reports while the app is in the background. */
object FamilyPermissions {
  fun hasForeground(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

  fun hasBackground(context: Context): Boolean =
    Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

  fun locationLevel(context: Context): String = when {
    !hasForeground(context) -> "none"
    !hasBackground(context) -> "while using"
    else -> "always"
  }
}

/**
 * AllIn: reports this phone's location to the family controller while Family is on: about every 2 minutes while
 * moving, every 15 minutes while still, and immediately on a "locate" command. Runs as a location foreground
 * service (Android only allows frequent background location that way), with a quiet ongoing notification.
 */
class FamilyLocationService : Service(), LocationListener {

  companion object {
    private val TAG = Log.tag(FamilyLocationService::class.java)

    private const val CHANNEL_ID = "allin_family"
    private const val NOTIFICATION_ID = 0x6710001
    private const val ACTION_LOCATE = "org.thoughtcrime.securesms.allin.family.LOCATE"

    private val MOVING_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2)
    private val STILL_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)
    private val UPDATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)
    private const val MOVING_METERS = 100f
    private const val MOVING_SPEED = 1.5f

    @Volatile
    private var running = false

    fun start(context: Context) {
      if (!FamilyPermissions.hasForeground(context)) {
        Family.status = "Location permission needed"
        return
      }
      try {
        ContextCompat.startForegroundService(context, Intent(context, FamilyLocationService::class.java))
      } catch (e: Exception) {
        // Android refuses to start location services from the background without "Allow all the time".
        Log.w(TAG, "Couldn't start", e)
        Family.status = "Open AllIn once to restart location sharing"
      }
    }

    fun stop(context: Context) {
      if (running) {
        context.stopService(Intent(context, FamilyLocationService::class.java))
      }
    }

    fun locateNow(context: Context) {
      if (running) {
        ContextCompat.startForegroundService(context, Intent(context, FamilyLocationService::class.java).setAction(ACTION_LOCATE))
      } else {
        start(context)
      }
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private var lastSent: Location? = null
  private var lastSentAt = 0L
  private var moving = false
  private var listening = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    running = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification(),
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
      )
    } catch (e: Exception) {
      Log.w(TAG, "Not allowed to run in the foreground", e)
      Family.status = "Open AllIn once to restart location sharing"
      stopSelf()
      return START_NOT_STICKY
    }

    if (!Family.isActive() || !FamilyPermissions.hasForeground(this)) {
      stopSelf()
      return START_NOT_STICKY
    }

    startListening()
    SignalExecutors.BOUNDED.execute { Family.helloIfDue(this) }

    if (intent?.action == ACTION_LOCATE) {
      Log.i(TAG, "Locate requested")
      lastKnown()?.let { report(it, force = true) }
      // A fresh fix usually follows within seconds; report it too.
      lastSentAt = 0
    }
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    stopListening()
    super.onDestroy()
  }

  @SuppressLint("MissingPermission")
  private fun startListening() {
    if (listening) return
    val manager = getSystemService(LocationManager::class.java) ?: return
    val providers = if (Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
      listOf(LocationManager.FUSED_PROVIDER)
    } else {
      listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
    }
    for (provider in providers) {
      try {
        manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper())
        listening = true
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't listen to $provider", e)
      }
    }
    // Make sure "still" phones still check in every 15 minutes even if no new fix arrives.
    handler.postDelayed(heartbeat, STILL_INTERVAL_MS)
  }

  private fun stopListening() {
    handler.removeCallbacks(heartbeat)
    if (listening) {
      getSystemService(LocationManager::class.java)?.removeUpdates(this)
      listening = false
    }
  }

  private val heartbeat: Runnable = object : Runnable {
    override fun run() {
      lastKnown()?.let { report(it, force = System.currentTimeMillis() - lastSentAt >= STILL_INTERVAL_MS) }
      handler.postDelayed(this, STILL_INTERVAL_MS)
    }
  }

  @SuppressLint("MissingPermission")
  private fun lastKnown(): Location? {
    val manager = getSystemService(LocationManager::class.java) ?: return null
    return manager.allProviders
      .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (e: Exception) { null } }
      .maxByOrNull { it.time }
  }

  override fun onLocationChanged(location: Location) {
    report(location, force = false)
  }

  /** Sends [location] if it's due: 2 minutes apart while moving, 15 while still. */
  private fun report(location: Location, force: Boolean) {
    val previous = lastSent
    val distance = previous?.distanceTo(location) ?: Float.MAX_VALUE
    moving = distance > MOVING_METERS || (location.hasSpeed() && location.speed > MOVING_SPEED)
    val interval = if (moving) MOVING_INTERVAL_MS else STILL_INTERVAL_MS
    val now = System.currentTimeMillis()
    if (!force && now - lastSentAt < interval) return

    lastSent = location
    lastSentAt = now
    SignalExecutors.BOUNDED.execute {
      val (battery, charging) = Family.battery(this)
      val json = JSONObject()
        .put("t", "loc")
        .put("lat", location.latitude)
        .put("lon", location.longitude)
        .put("acc", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
        .put("at", location.time)
        .put("speed", if (location.hasSpeed()) location.speed.toDouble() else JSONObject.NULL)
        .put("moving", moving)
        .put("battery", battery)
        .put("charging", charging)
      if (!Family.send(json)) {
        // Try again at the next fix.
        lastSentAt = 0
      }
    }
  }

  private fun notification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Family location sharing", NotificationManager.IMPORTANCE_MIN).apply {
          description = "Shown while AllIn shares this phone's location with your family"
          setShowBadge(false)
        }
      )
    }
    val open = PendingIntent.getActivity(
      this,
      0,
      FamilySettingsActivity.createIntent(this),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Sharing location with family")
      .setContentText("Find My Device is on")
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setOngoing(true)
      .setContentIntent(open)
      .build()
  }
}
