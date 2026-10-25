package com.lockguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Persistent foreground service. Being already in the foreground is what lets it
 * play audio on demand even while the device is locked, without tripping the
 * background-start restrictions that would block a cold service start.
 *
 * It listens for [ACTION_TRIGGER_ALARM] (sent by [LockMonitorAdmin]) and, on
 * receiving it, forces the alarm stream to max and plays the default alarm tone
 * for [ALARM_DURATION_MS] under a wake lock.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.lockguard.ACTION_TRIGGER_ALARM"
        const val ALARM_DURATION_MS = 10_000L
        private const val CHANNEL_ID = "lockguard_monitor"
        private const val NOTIF_ID = 1001
    }

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var alarmActive = false

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TRIGGER_ALARM) soundAlarm()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        ContextCompat.registerReceiver(
            this,
            triggerReceiver,
            IntentFilter(ACTION_TRIGGER_ALARM),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER_ALARM) soundAlarm()
        return START_STICKY
    }

    private fun soundAlarm() {
        if (alarmActive) return
        alarmActive = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lockguard:alarm").apply {
            setReferenceCounted(false)
            acquire(ALARM_DURATION_MS + 2_000L)
        }

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@MonitorService, uri)
                isLooping = true
                setOnPreparedListener { start() }
                prepareAsync()
            }
        }.onFailure { alarmActive = false }

        handler.postDelayed({ stopAlarm() }, ALARM_DURATION_MS)
    }

    private fun stopAlarm() {
        handler.removeCallbacksAndMessages(null)
        player?.run { runCatching { if (isPlaying) stop() }; release() }
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        alarmActive = false
    }

    override fun onDestroy() {
        stopAlarm()
        runCatching { unregisterReceiver(triggerReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LockGuard active")
            .setContentText("Watching for failed unlock attempts")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
