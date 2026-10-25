package com.lockguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider

/** Single-screen control panel, built with Material components. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, LockMonitorAdmin::class.java)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(20), dpi(16), dpi(24))
        }

        root.addView(TextView(this).apply {
            text = "LockGuard"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dpi(4), 0, 0)
        }
        root.addView(status)

        root.addView(card("SETUP") { c ->
            c.addView(filledButton("Enable device admin") { enableAdmin() })
            c.addView(gap())
            c.addView(filledButton("Start protection service") { startMonitor() })
            c.addView(gap())
            c.addView(filledButton("Ignore battery optimisations") { requestIgnoreBattery() })
        })

        root.addView(card("FEATURES") { c ->
            c.addView(switchRow("Stealth mode", "Silent capture — no alarm, flash or screen", Prefs.stealth(this)) {
                Prefs.setStealth(this, it); refreshStatus()
            })
            c.addView(divider())
            c.addView(switchRow("Deterrent lock screen", "Full-screen warning that needs your passcode/biometrics to stop", Prefs.overlay(this)) {
                Prefs.setOverlay(this, it)
            })
            c.addView(divider())
            c.addView(switchRow("Flash strobe", "Strobe the camera flash during the alarm", Prefs.strobe(this)) {
                Prefs.setStrobe(this, it)
            })
            c.addView(divider())
            c.addView(switchRow("Video capture", "Record a front-camera video instead of photos", Prefs.video(this)) {
                Prefs.setVideo(this, it); refreshStatus()
            })
            c.addView(divider())
            c.addView(switchRow("Location logging", "Log GPS on each trigger (needs Location permission)", Prefs.location(this)) {
                Prefs.setLocation(this, it); if (it) ensureLocation()
            })
        })

        root.addView(card("TUNING") { c ->
            c.addView(sliderRow("Trigger after %d failed unlocks", Prefs.THRESHOLD_MIN, Prefs.THRESHOLD_MAX, Prefs.threshold(this)) {
                Prefs.setThreshold(this, it); refreshStatus()
            })
            c.addView(sliderRow("Alarm length: %ds", Prefs.ALARM_MIN, Prefs.ALARM_MAX, Prefs.alarmSecs(this)) {
                Prefs.setAlarmSecs(this, it); refreshStatus()
            })
            c.addView(sliderRow("Recording length: %ds", Prefs.REC_MIN, Prefs.REC_MAX, Prefs.recSecs(this)) {
                Prefs.setRecSecs(this, it); refreshStatus()
            })
        })

        root.addView(card("") { c ->
            c.addView(filledButton("Test alarm") { testAlarm() })
            c.addView(gap())
            c.addView(filledButton("Stop alarm") { stopAlarm() })
            c.addView(gap())
            c.addView(filledButton("View log / captures") { startActivity(Intent(this, LogActivity::class.java)) })
            c.addView(gap())
            c.addView(outlinedButton("Disable device admin") { disableAdmin() })
        })

        setContentView(ScrollView(this).apply { addView(root) })
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val active = dpm.isAdminActive(admin)
        status.text = buildString {
            append(if (active) "Device admin ON" else "Device admin OFF — tap Enable")
            append("  ·  ")
            append(if (Prefs.stealth(this@MainActivity)) "Stealth" else "Alarm")
            append(if (Prefs.video(this@MainActivity)) " · video" else " · photos")
            append("  ·  after ")
            append(Prefs.threshold(this@MainActivity))
            append(" fails")
        }
    }

    // ---- actions ----

    private fun enableAdmin() {
        if (dpm.isAdminActive(admin)) { toast("Already enabled"); return }
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
        })
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
        sendBroadcast(Intent(MonitorService.ACTION_TEST_ALARM).setPackage(packageName))
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
            } else toast("Already ignoring battery optimisations")
        }
    }

    private fun ensureLocation() {
        val p = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(p, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ---- UI builders ----

    private fun card(title: String, build: (LinearLayout) -> Unit): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
        }
        if (title.isNotEmpty()) inner.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.6f
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dpi(10))
        })
        build(inner)
        return MaterialCardView(this).apply {
            radius = dp(18f)
            cardElevation = dp(1f)
            useCompatPadding = true
            addView(inner)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(12) }
        }
    }

    private fun switchRow(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(10), 0, dpi(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply { text = title; textSize = 15f })
        col.addView(TextView(this).apply {
            text = subtitle; textSize = 12f; alpha = 0.6f; setPadding(0, dpi(2), 0, 0)
        })
        val sw = SwitchCompat(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(col); row.addView(sw)
        return row
    }

    private fun sliderRow(fmt: String, from: Int, to: Int, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(6), 0, dpi(6)) }
        val label = TextView(this).apply { text = String.format(fmt, initial); textSize = 14f }
        val slider = Slider(this).apply {
            valueFrom = from.toFloat()
            valueTo = to.toFloat()
            stepSize = 1f
            value = initial.coerceIn(from, to).toFloat()
            addOnChangeListener { _, value, _ ->
                val v = value.toInt()
                label.text = String.format(fmt, v)
                onChange(v)
            }
        }
        col.addView(label); col.addView(slider)
        return col
    }

    private fun filledButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun outlinedButton(text: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x33888888)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1))
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(8))
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
