package com.lockguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Full-screen deterrent shown on an intruder trigger. Appears over the lock
 * screen, warns that the phone is being tracked/recorded, and can only be
 * dismissed by authenticating with the device passcode or biometrics (handled
 * by the system via BiometricPrompt — the app never sees the credential).
 */
class AlertActivity : AppCompatActivity() {

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MonitorService.ACTION_ALERT_DISMISS) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(MonitorService.ACTION_ALERT_DISMISS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val white = 0xFFFFFFFF.toInt()
        val pad = (28 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text = "⚠"
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(white)
        })
        root.addView(TextView(this).apply {
            text = "SECURITY ALERT"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        root.addView(TextView(this).apply {
            text = "This phone has had failed passcode attempts and is now being tracked and recorded.\n\n" +
                "Enter the correct passcode or use biometrics to stop."
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(0, 0, 0, pad)
        })
        root.addView(MaterialButton(this).apply {
            text = "Stop — authenticate"
            textSize = 16f
            setOnClickListener { authenticate() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        setContentView(root)
    }

    private fun authenticate() {
        runCatching {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        stopAndFinish()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Stop LockGuard")
                .setSubtitle("Authenticate to stop the alarm and tracking")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        }
    }

    private fun stopAndFinish() {
        sendBroadcast(Intent(MonitorService.ACTION_STOP_ALARM).setPackage(packageName))
        finish()
    }

    @Deprecated("Blocked on purpose — must authenticate to leave")
    override fun onBackPressed() {
        // Intentionally does nothing: the screen can't be dismissed without authenticating.
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onDestroy()
    }
}
