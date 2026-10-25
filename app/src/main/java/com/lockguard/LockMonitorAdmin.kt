package com.lockguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import androidx.core.content.ContextCompat

/**
 * Device-admin receiver. With the `watch-login` policy declared in
 * res/xml/device_admin.xml, Android calls [onPasswordFailed] every time a wrong
 * lock-screen credential is entered, and [onPasswordSucceeded] on a correct one.
 *
 * We keep a running count of consecutive failures and, at [THRESHOLD] or more,
 * signal [MonitorService] to sound the alarm.
 */
class LockMonitorAdmin : DeviceAdminReceiver() {

    companion object {
        const val PREFS = "lockguard_prefs"
        const val KEY_FAILS = "fail_count"
        const val THRESHOLD = 3
    }

    override fun onEnabled(context: Context, intent: Intent) {
        ensureMonitorRunning(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        prefs.edit().putInt(KEY_FAILS, fails).apply()

        ensureMonitorRunning(context)

        if (fails >= THRESHOLD) {
            // Warm path: the monitor is already a running foreground service,
            // so a package-scoped broadcast reaches its registered receiver.
            context.sendBroadcast(
                Intent(MonitorService.ACTION_TRIGGER_ALARM).setPackage(context.packageName)
            )
            // Cold path: (re)start the service directly with the trigger action,
            // in case it was killed. Guarded because a background FGS start can
            // be refused on a non-systemized install (see README).
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FAILS, 0).apply()
    }

    private fun ensureMonitorRunning(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, MonitorService::class.java)
            )
        }
    }
}
