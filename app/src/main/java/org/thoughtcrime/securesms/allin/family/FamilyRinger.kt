package org.thoughtcrime.securesms.allin.family

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.R
import java.util.concurrent.TimeUnit

/**
 * AllIn: rings the phone loudly for Find My Device, even on silent or vibrate: it plays the alarm sound on the
 * alarm stream at full volume (the alarm stream ignores the ringer switch). Stops after [RING_MS], from the
 * notification's Stop button, from [FamilyRingActivity], or on the controller's "stop" command.
 */
object FamilyRinger {

  private val TAG = Log.tag(FamilyRinger::class.java)

  private const val CHANNEL_ID = "allin_family_ring"
  private const val NOTIFICATION_ID = 0x6710002
  private val RING_MS = TimeUnit.MINUTES.toMillis(2)

  private val handler = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private var savedVolume = -1

  val isRinging: Boolean get() = player != null

  fun start(context: Context) {
    handler.post { startOnMain(context.applicationContext) }
  }

  fun stop(context: Context) {
    handler.post { stopOnMain(context.applicationContext) }
  }

  private fun startOnMain(context: Context) {
    if (player != null) return
    Log.i(TAG, "Ringing")

    val audio = context.getSystemService(AudioManager::class.java)
    savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
    audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    player = try {
      MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        setDataSource(context, sound)
        isLooping = true
        prepare()
        start()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't play the alarm sound", e)
      null
    }

    val vibrator = context.getSystemService(Vibrator::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
      vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    showNotification(context)
    handler.postDelayed({ stopOnMain(context) }, RING_MS)
  }

  private fun stopOnMain(context: Context) {
    handler.removeCallbacksAndMessages(null)
    player?.let {
      try {
        it.stop()
      } catch (e: Exception) {
        // Already stopped.
      }
      it.release()
    }
    player = null
    context.getSystemService(Vibrator::class.java)?.cancel()
    if (savedVolume >= 0) {
      context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
      savedVolume = -1
    }
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }

  private fun showNotification(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Find My Device", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "When your family rings this phone"
          setSound(null, null)
        }
      )
    }
    val stopIntent = PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, FamilyStopReceiver::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val screen = PendingIntent.getActivity(
      context,
      0,
      Intent(context, FamilyRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Your family is ringing this phone")
      .setContentText("Tap Stop to silence it")
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setOngoing(true)
      .setContentIntent(screen)
      .setFullScreenIntent(screen, true)
      .addAction(0, "Stop", stopIntent)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
      Log.w(TAG, "Not allowed to notify", e)
    }
  }
}

class FamilyStopReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    FamilyRinger.stop(context)
  }
}

/** Full-screen "Stop" page shown while the phone rings, also over the lock screen. */
class FamilyRingActivity : BaseActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }
    setContent {
      SignalTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
          ) {
            Text("Find My Device", style = MaterialTheme.typography.headlineMedium)
            Text(
              "Your family is ringing this phone.",
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )
            Button(
              onClick = {
                FamilyRinger.stop(this@FamilyRingActivity)
                finish()
              },
              modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
              Text("Stop", fontSize = 24.sp)
            }
          }
        }
      }
    }
  }
}
