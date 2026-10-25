package com.lockguard

import android.content.Context

/** Single source of truth for the user-tunable settings. Backed by SharedPreferences. */
object Prefs {
    const val FILE = "lockguard_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    const val DEF_THRESHOLD = 3
    const val DEF_ALARM_SECS = 10
    const val DEF_REC_SECS = 30

    const val THRESHOLD_MIN = 1
    const val THRESHOLD_MAX = 10
    const val ALARM_MIN = 3
    const val ALARM_MAX = 60
    const val REC_MIN = 5
    const val REC_MAX = 120

    fun threshold(c: Context) = sp(c).getInt("threshold", DEF_THRESHOLD).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt("threshold", v).apply()

    fun alarmSecs(c: Context) = sp(c).getInt("alarm_secs", DEF_ALARM_SECS).coerceIn(ALARM_MIN, ALARM_MAX)
    fun setAlarmSecs(c: Context, v: Int) = sp(c).edit().putInt("alarm_secs", v).apply()

    fun recSecs(c: Context) = sp(c).getInt("rec_secs", DEF_REC_SECS).coerceIn(REC_MIN, REC_MAX)
    fun setRecSecs(c: Context, v: Int) = sp(c).edit().putInt("rec_secs", v).apply()

    fun stealth(c: Context) = sp(c).getBoolean("stealth_mode", false)
    fun setStealth(c: Context, v: Boolean) = sp(c).edit().putBoolean("stealth_mode", v).apply()

    fun strobe(c: Context) = sp(c).getBoolean("strobe", true)
    fun setStrobe(c: Context, v: Boolean) = sp(c).edit().putBoolean("strobe", v).apply()

    fun video(c: Context) = sp(c).getBoolean("video_mode", false)
    fun setVideo(c: Context, v: Boolean) = sp(c).edit().putBoolean("video_mode", v).apply()

    fun location(c: Context) = sp(c).getBoolean("location_enabled", false)
    fun setLocation(c: Context, v: Boolean) = sp(c).edit().putBoolean("location_enabled", v).apply()
}
