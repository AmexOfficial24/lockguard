package com.lockguard

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** In-app settings: thresholds, durations, and feature toggles. Saved immediately. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "LockGuard settings"
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(section("Trigger"))
        root.addView(
            slider(
                "Alarm/capture after %d failed unlocks",
                Prefs.THRESHOLD_MIN, Prefs.THRESHOLD_MAX, Prefs.threshold(this)
            ) { Prefs.setThreshold(this, it) }
        )

        root.addView(section("Durations"))
        root.addView(
            slider(
                "Alarm length: %ds", Prefs.ALARM_MIN, Prefs.ALARM_MAX, Prefs.alarmSecs(this)
            ) { Prefs.setAlarmSecs(this, it) }
        )
        root.addView(
            slider(
                "Recording length: %ds", Prefs.REC_MIN, Prefs.REC_MAX, Prefs.recSecs(this)
            ) { Prefs.setRecSecs(this, it) }
        )

        root.addView(section("Features"))
        root.addView(
            toggle("Stealth mode (silent capture, no alarm)", Prefs.stealth(this)) {
                Prefs.setStealth(this, it)
            }
        )
        root.addView(
            toggle("Flash strobe during alarm", Prefs.strobe(this)) {
                Prefs.setStrobe(this, it)
            }
        )
        root.addView(
            toggle("Record video instead of photos", Prefs.video(this)) {
                Prefs.setVideo(this, it)
            }
        )
        root.addView(
            toggle("Log location on trigger (needs Location permission)", Prefs.location(this)) { on ->
                Prefs.setLocation(this, on)
                if (on) ensureLocationPermission()
            }
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun ensureLocationPermission() {
        val p = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(p, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        }
    }

    // ---- UI builders ----

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 4)
    }

    private fun slider(
        labelFmt: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit
    ): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = String.format(labelFmt, initial)
            textSize = 14f
        }
        val bar = SeekBar(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) this.min = min
            this.max = max
            progress = initial.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    val v = value.coerceIn(min, max)
                    label.text = String.format(labelFmt, v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addView(label)
        col.addView(bar)
        return col
    }

    private fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val label = TextView(this).apply {
            this.text = text
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(label)
        row.addView(sw)
        return row
    }
}
