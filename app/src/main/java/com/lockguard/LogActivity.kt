package com.lockguard

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Shows the timestamped attempt log and thumbnails of captured intruder evidence. */
class LogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(header("Attempt log"))
        val logText = AttemptLog.read(this).ifBlank { "No attempts logged yet." }
        root.addView(TextView(this).apply {
            text = logText
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad)
        })

        val sessions = AttemptLog.sessions(this)
        root.addView(header("Captures (${sessions.size})"))
        if (sessions.isEmpty()) {
            root.addView(TextView(this).apply { text = "No captures yet."; textSize = 13f })
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        for (s in sessions.take(20)) {
            root.addView(TextView(this).apply {
                text = s.name
                textSize = 15f
                setPadding(0, pad, 0, 4)
            })

            // location
            val loc = File(s, "location.txt")
            if (loc.exists()) {
                root.addView(TextView(this).apply {
                    text = runCatching { loc.readText().trim() }.getOrDefault("")
                    textSize = 12f
                    setTextIsSelectable(true)
                    setPadding(0, 0, 0, 4)
                })
            }

            // photos
            for (name in listOf("front.jpg", "back.jpg")) {
                val f = File(s, name)
                if (f.exists() && f.length() > 0) {
                    root.addView(TextView(this).apply { text = name; textSize = 12f })
                    val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull()
                    if (bmp != null) root.addView(imageView(bmp, pad))
                }
            }

            // video (show first-frame thumbnail + path)
            val video = File(s, "video.mp4")
            if (video.exists() && video.length() > 0) {
                root.addView(TextView(this).apply {
                    text = "video.mp4 (${video.length() / 1024} KB)\n${video.absolutePath}"
                    textSize = 11f
                    setTextIsSelectable(true)
                })
                val frame = runCatching {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(video.absolutePath)
                    val b = mmr.frameAtTime
                    mmr.release()
                    b
                }.getOrNull()
                if (frame != null) root.addView(imageView(frame, pad))
            }

            // audio
            val audio = File(s, "audio.m4a")
            if (audio.exists() && audio.length() > 0) {
                root.addView(TextView(this).apply {
                    text = "audio.m4a (${audio.length() / 1024} KB)\n${audio.absolutePath}"
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(0, 0, 0, pad)
                })
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun imageView(bmp: android.graphics.Bitmap, pad: Int) = ImageView(this).apply {
        setImageBitmap(bmp)
        adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = pad / 2 }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setPadding(0, 0, 0, 4)
    }
}
