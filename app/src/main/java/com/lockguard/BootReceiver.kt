package com.lockguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the monitor service after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                runCatching {
                    ContextCompat.startForegroundService(
                        context, Intent(context, MonitorService::class.java)
                    )
                }
            }
        }
    }
}
