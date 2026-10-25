package com.lockguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Persistent foreground service. Being already in the foreground is what lets it
 * use audio, the camera and the microphone on demand while the device is locked,
 * without tripping the background-start restrictions that would block a cold
 * service start.
 *
 * On [ACTION_TRIGGER_ALARM] (a real intruder): plays the siren (unless stealth
 * mode is on) and captures the intruder (front + back photos, 30s mic audio).
 * [ACTION_TEST_ALARM] always plays the siren (for testing, ignores stealth).
 * [ACTION_STOP_ALARM] stops the siren.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.lockguard.ACTION_TRIGGER_ALARM"
        const val ACTION_TEST_ALARM = "com.lockguard.ACTION_TEST_ALARM"
        const val ACTION_STOP_ALARM = "com.lockguard.ACTION_STOP_ALARM"
        const val ALARM_DURATION_MS = 10_000L
        const val CAPTURE_AUDIO_MS = 30_000L
        // Extra gain on top of max volume, in millibels (100 mB = 1 dB).
        const val LOUDNESS_GAIN_MB = 2000
        private const val CHANNEL_ID = "lockguard_monitor"
        private const val NOTIF_ID = 1001
    }

    private var player: MediaPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var alarmActive = false

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TRIGGER_ALARM -> onTrigger()
                ACTION_TEST_ALARM -> soundAlarm()
                ACTION_STOP_ALARM -> stopAlarm()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        val filter = IntentFilter().apply {
            addAction(ACTION_TRIGGER_ALARM)
            addAction(ACTION_TEST_ALARM)
            addAction(ACTION_STOP_ALARM)
        }
        ContextCompat.registerReceiver(
            this, triggerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TRIGGER_ALARM -> onTrigger()
            ACTION_TEST_ALARM -> soundAlarm()
            ACTION_STOP_ALARM -> stopAlarm()
        }
        return Service.START_STICKY
    }

    /** Real intruder response: siren (unless stealth) + capture. */
    private fun onTrigger() {
        val stealth = getSharedPreferences(LockMonitorAdmin.PREFS, Context.MODE_PRIVATE)
            .getBoolean(LockMonitorAdmin.KEY_STEALTH, false)
        if (stealth) {
            AttemptLog.append(this, "Stealth mode — alarm suppressed, capturing silently")
        } else {
            soundAlarm()
        }
        captureIntruder()
    }

    /** Starts the foreground service declaring only the types we currently hold permission for. */
    private fun goForeground() {
        runCatching { startForeground(NOTIF_ID, buildNotification(), foregroundType()) }
            .onFailure { runCatching { startForeground(NOTIF_ID, buildNotification()) } }
    }

    private fun foregroundType(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasPerm(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return t
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------- Alarm ----------

    private fun soundAlarm() {
        if (alarmActive) return
        alarmActive = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lockguard:alarm").apply {
            setReferenceCounted(false)
            acquire(ALARM_DURATION_MS + 2_000L)
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val siren = runCatching { sirenFile() }.getOrNull()
                if (siren != null) {
                    setDataSource(siren.absolutePath)
                } else {
                    val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    setDataSource(this@MonitorService, fallback)
                }
                isLooping = true
                setVolume(1f, 1f)
                setOnPreparedListener { start() }
                prepareAsync()
            }
            runCatching {
                loudness = LoudnessEnhancer(player!!.audioSessionId).apply {
                    setTargetGain(LOUDNESS_GAIN_MB)
                    enabled = true
                }
            }
        }.onFailure { alarmActive = false }

        handler.postDelayed({ stopAlarm() }, ALARM_DURATION_MS)
    }

    private fun stopAlarm() {
        handler.removeCallbacksAndMessages(null)
        loudness?.let { runCatching { it.enabled = false }; runCatching { it.release() } }
        loudness = null
        player?.run { runCatching { if (isPlaying) stop() }; release() }
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        alarmActive = false
    }

    private fun sirenFile(): java.io.File {
        val f = java.io.File(cacheDir, "siren.wav")
        if (!f.exists() || f.length() == 0L) {
            resources.openRawResource(R.raw.siren).use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return f
    }

    // ---------- Intruder capture ----------

    private fun captureIntruder() {
        val cam = hasPerm(Manifest.permission.CAMERA)
        val mic = hasPerm(Manifest.permission.RECORD_AUDIO)
        if (!cam && !mic) {
            AttemptLog.append(this, "Capture skipped — camera & microphone not granted")
            return
        }
        val dir = AttemptLog.newSession(this)
        // Keep the CPU awake for the whole capture, even when the alarm (and its
        // wake lock) isn't running — e.g. in stealth mode with the screen off.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            captureWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lockguard:capture").apply {
                setReferenceCounted(false)
                acquire(CAPTURE_AUDIO_MS + 5_000L)
            }
        }
        AttemptLog.append(
            this,
            "Capture started [${dir.name}] camera=${if (cam) "on" else "off"} mic=${if (mic) "on" else "off"}"
        )
        runCatching {
            IntruderCapture(this) { line -> AttemptLog.append(this, line) }
                .run(dir, withCamera = cam, withAudio = mic, audioMs = CAPTURE_AUDIO_MS)
        }.onFailure { AttemptLog.append(this, "Capture error (${it.javaClass.simpleName})") }
    }

    // ---------- Service plumbing ----------

    override fun onDestroy() {
        stopAlarm()
        captureWakeLock?.let { if (it.isHeld) it.release() }
        captureWakeLock = null
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
