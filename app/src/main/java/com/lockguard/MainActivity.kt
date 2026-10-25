package com.lockguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Minimal setup/control UI. Built programmatically to keep the project lean —
 * no layout XML or view binding needed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, LockMonitorAdmin::class.java)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(spacer(pad))

        root.addView(button("1 · Enable device admin") { enableAdmin() })
        root.addView(button("2 · Start protection service") { startMonitor() })
        root.addView(button("3 · Ignore battery optimisations") { requestIgnoreBattery() })
        root.addView(spacer(pad))
        root.addView(button("Test alarm now") { testAlarm() })
        root.addView(button("Stop alarm") { stopAlarm() })
        root.addView(button("Disable device admin") { disableAdmin() })

        setContentView(root)
        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val active = dpm.isAdminActive(admin)
        status.text = buildString {
            append("Device admin: ")
            append(if (active) "ENABLED ✓" else "not enabled ✗")
            append("\nTrigger: alarm after ")
            append(LockMonitorAdmin.THRESHOLD)
            append(" failed unlocks")
            append("\nAlarm length: ")
            append(MonitorService.ALARM_DURATION_MS / 1000)
            append("s on the alarm stream (max volume)")
        }
    }

    private fun enableAdmin() {
        if (dpm.isAdminActive(admin)) {
            toast("Already enabled")
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_description)
            )
        }
        startActivity(intent)
    }

    private fun disableAdmin() {
        if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        refreshStatus()
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        toast("Protection service started")
    }

    private fun testAlarm() {
        startMonitor()
        sendBroadcast(Intent(MonitorService.ACTION_TRIGGER_ALARM).setPackage(packageName))
    }

    private fun stopAlarm() {
        sendBroadcast(Intent(MonitorService.ACTION_STOP_ALARM).setPackage(packageName))
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else {
                toast("Already ignoring battery optimisations")
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
