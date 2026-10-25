package com.lockguard

import android.Manifest
import android.annotation.SuppressLint
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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
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
import java.io.File

/**
 * Persistent foreground service. Being already in the foreground is what lets it
 * use audio, camera, mic and location on demand while the device is locked.
 *
 * On [ACTION_TRIGGER_ALARM] (a real intruder): plays the siren + strobes the flash
 * (unless stealth mode or the strobe toggle are off), logs the location (if
 * enabled), and captures the intruder — a front video, or front+back photos +
 * audio, per settings. Durations and the strobe come from [Prefs].
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.lockguard.ACTION_TRIGGER_ALARM"
        const val ACTION_TEST_ALARM = "com.lockguard.ACTION_TEST_ALARM"
        const val ACTION_STOP_ALARM = "com.lockguard.ACTION_STOP_ALARM"
        const val STROBE_INTERVAL_MS = 150L
        const val LOUDNESS_GAIN_MB = 4000
        private const val CHANNEL_ID = "lockguard_monitor"
        private const val NOTIF_ID = 1001
    }

    private var player: MediaPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var alarmActive = false

    private var strobeCamId: String? = null
    private var strobeOn = false
    private val strobeRunnable = object : Runnable {
        override fun run() {
            val id = strobeCamId ?: return
            strobeOn = !strobeOn
            runCatching {
                (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, strobeOn)
            }
            handler.postDelayed(this, STROBE_INTERVAL_MS)
        }
    }

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

    private fun onTrigger() {
        if (Prefs.stealth(this)) {
            AttemptLog.append(this, "Stealth mode — alarm + flash suppressed, capturing silently")
        } else {
            soundAlarm()
        }
        captureIntruder()
    }

    private fun goForeground() {
        runCatching { startForeground(NOTIF_ID, buildNotification(), foregroundType()) }
            .onFailure { runCatching { startForeground(NOTIF_ID, buildNotification()) } }
    }

    private fun foregroundType(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasPerm(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return t
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------- Alarm ----------

    private fun soundAlarm() {
        if (alarmActive) return
        alarmActive = true
        val alarmMs = Prefs.alarmSecs(this) * 1000L

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lockguard:alarm").apply {
            setReferenceCounted(false)
            acquire(alarmMs + 2_000L)
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

        if (Prefs.strobe(this)) startStrobe()
        handler.postDelayed({ stopAlarm() }, alarmMs)
    }

    private fun stopAlarm() {
        handler.removeCallbacksAndMessages(null)
        stopStrobe()
        loudness?.let { runCatching { it.enabled = false }; runCatching { it.release() } }
        loudness = null
        player?.run { runCatching { if (isPlaying) stop() }; release() }
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        alarmActive = false
    }

    // ---------- Flash strobe ----------

    private fun startStrobe() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        strobeCamId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (strobeCamId == null) return
        strobeOn = false
        handler.post(strobeRunnable)
    }

    private fun stopStrobe() {
        handler.removeCallbacks(strobeRunnable)
        strobeCamId?.let { id ->
            runCatching {
                (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, false)
            }
        }
        strobeOn = false
    }

    private fun sirenFile(): File {
        val f = File(cacheDir, "siren.wav")
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
        val recMs = Prefs.recSecs(this) * 1000L

        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            captureWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lockguard:capture").apply {
                setReferenceCounted(false)
                acquire(recMs + 5_000L)
            }
        }

        logLocation(dir)

        val cap = IntruderCapture(this) { line -> AttemptLog.append(this, line) }
        if (Prefs.video(this) && cam) {
            AttemptLog.append(
                this,
                "Capture [${dir.name}]: front video ${Prefs.recSecs(this)}s${if (mic) " + audio" else ""}"
            )
            runCatching { cap.runVideo(dir, recMs, withAudio = mic) }
                .onFailure { AttemptLog.append(this, "Video capture error (${it.javaClass.simpleName})") }
        } else {
            AttemptLog.append(
                this,
                "Capture [${dir.name}]: photos${if (mic) " + ${Prefs.recSecs(this)}s audio" else ""}"
            )
            runCatching { cap.run(dir, withCamera = cam, withAudio = mic, audioMs = recMs) }
                .onFailure { AttemptLog.append(this, "Capture error (${it.javaClass.simpleName})") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun logLocation(dir: File) {
        if (!Prefs.location(this) || !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            if (best != null) {
                val b = best!!
                val ageS = (System.currentTimeMillis() - b.time) / 1000
                val line = "Location ${b.latitude},${b.longitude} ±${b.accuracy.toInt()}m (age ${ageS}s)"
                AttemptLog.append(this, line)
                runCatching {
                    File(dir, "location.txt").writeText(
                        "$line\nhttps://maps.google.com/?q=${b.latitude},${b.longitude}\n"
                    )
                }
            } else {
                AttemptLog.append(this, "Location: no fix available")
            }
        }.onFailure { AttemptLog.append(this, "Location error (${it.javaClass.simpleName})") }
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
