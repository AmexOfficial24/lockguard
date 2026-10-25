package com.lockguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import androidx.core.content.ContextCompat

/**
 * Device-admin receiver. With the `watch-login` policy declared in
 * res/xml/device_admin.xml, Android calls [onPasswordFailed] on every wrong
 * lock-screen attempt and [onPasswordSucceeded] on a correct one.
 *
 * Every failure is logged with a timestamp. At the configured threshold,
 * [MonitorService] is signalled to respond (alarm/strobe and intruder capture).
 *
 * Note: Android never exposes the actual credential the user typed to any app,
 * so the attempted passcode cannot be — and is not — recorded.
 */
class LockMonitorAdmin : DeviceAdminReceiver() {

    companion object {
        const val PREFS = "lockguard_prefs"
        const val KEY_FAILS = "fail_count"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        AttemptLog.append(context, "Device admin enabled")
        ensureMonitorRunning(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        prefs.edit().putInt(KEY_FAILS, fails).apply()

        AttemptLog.append(context, "Failed unlock attempt #$fails")
        ensureMonitorRunning(context)

        val threshold = Prefs.threshold(context)
        if (fails >= threshold) {
            AttemptLog.append(
                context,
                "Threshold reached ($threshold) — response triggered"
            )
            context.sendBroadcast(
                Intent(MonitorService.ACTION_TRIGGER_ALARM).setPackage(context.packageName)
            )
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_TRIGGER_ALARM)
                )
            }
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getInt(KEY_FAILS, 0)
        prefs.edit().putInt(KEY_FAILS, 0).apply()
        if (prev > 0) {
            AttemptLog.append(context, "Unlocked (after $prev failed attempt(s)) — counter reset")
        }
    }

    private fun ensureMonitorRunning(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, MonitorService::class.java)
            )
        }
    }
}
