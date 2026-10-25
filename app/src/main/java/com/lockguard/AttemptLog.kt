package com.lockguard

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Timestamped text log of unlock attempts, plus the on-disk capture folders. */
object AttemptLog {

    private fun base(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun logFile(context: Context): File = File(base(context), "attempts.log")

    fun capturesDir(context: Context): File =
        File(base(context), "captures").apply { mkdirs() }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())

    @Synchronized
    fun append(context: Context, line: String) {
        runCatching { logFile(context).appendText("${now()} — $line\n") }
    }

    fun newSession(context: Context): File {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(capturesDir(context), name).apply { mkdirs() }
    }

    fun read(context: Context): String =
        runCatching { logFile(context).readText() }.getOrDefault("")

    fun sessions(context: Context): List<File> =
        capturesDir(context).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()
}
