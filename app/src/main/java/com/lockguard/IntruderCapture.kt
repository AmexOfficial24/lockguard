package com.lockguard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless intruder capture from a foreground service. Two modes:
 *  - [run]: a still photo from the front camera, then the back camera, plus a
 *    fixed-length microphone recording.
 *  - [runVideo]: a front-camera video clip (with audio if the mic is granted).
 *
 * Everything is wrapped in runCatching so a failure on one device/sensor never
 * crashes the app; failures are reported through [log].
 */
class IntruderCapture(
    private val context: Context,
    private val log: (String) -> Unit
) {
    private val thread = HandlerThread("intruder-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var recorder: MediaRecorder? = null
    private var cameraDone = false
    private var audioDone = false

    // ================= Photo + audio mode =================

    fun run(dir: File, withCamera: Boolean, withAudio: Boolean, audioMs: Long) {
        handler.post {
            if (withAudio) startAudio(File(dir, "audio.m4a"), audioMs) else audioDone = true
            if (withCamera) {
                captureFacing(CameraCharacteristics.LENS_FACING_FRONT, File(dir, "front.jpg")) {
                    captureFacing(CameraCharacteristics.LENS_FACING_BACK, File(dir, "back.jpg")) {
                        log("Capture: photos finished")
                        cameraDone = true
                        maybeQuit()
                    }
                }
            } else {
                cameraDone = true
                maybeQuit()
            }
        }
    }

    private fun maybeQuit() {
        if (cameraDone && audioDone) runCatching { thread.quitSafely() }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(out: File, durationMs: Long) {
        runCatching {
            val r = newRecorder()
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            log("Capture: mic recording started (${durationMs / 1000}s)")
            handler.postDelayed({
                runCatching { r.stop() }
                runCatching { r.release() }
                recorder = null
                log("Capture: mic recording saved")
                audioDone = true
                maybeQuit()
            }, durationMs)
        }.onFailure {
            log("Capture: mic failed (${it.javaClass.simpleName})")
            runCatching { recorder?.release() }
            recorder = null
            audioDone = true
            maybeQuit()
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureFacing(facing: Int, out: File, onDone: () -> Unit) {
        val done = AtomicBoolean(false)
        fun finish() { if (done.compareAndSet(false, true)) onDone() }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            }
        }.getOrNull()
        if (camId == null) {
            log("Capture: no ${facingName(facing)} camera")
            finish(); return
        }

        val chars = cm.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        val size = jpegSizes?.minByOrNull {
            kotlin.math.abs(it.width.toLong() * it.height - 1920L * 1080L)
        } ?: Size(1280, 720)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        val previewSt = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val previewSurface = Surface(previewSt)

        val cleaned = AtomicBoolean(false)
        fun cleanup(camera: CameraDevice?, session: CameraCaptureSession?) {
            if (!cleaned.compareAndSet(false, true)) return
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader.close() }
            runCatching { previewSurface.release() }
            runCatching { previewSt.release() }
        }

        val watchdog = Runnable {
            if (!done.get()) {
                log("Capture: ${facingName(facing)} timed out")
                cleanup(null, null)
                finish()
            }
        }
        handler.postDelayed(watchdog, 8_000L)

        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    out.outputStream().use { it.write(bytes) }
                    log("Capture: ${facingName(facing)} photo saved")
                }
            }.onFailure { log("Capture: ${facingName(facing)} save failed") }
        }, handler)

        runCatching {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    runCatching {
                        camera.createCaptureSession(
                            listOf(previewSurface, reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    runCatching {
                                        val preview = camera
                                            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply {
                                                addTarget(previewSurface)
                                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                        session.setRepeatingRequest(preview.build(), null, handler)
                                        handler.postDelayed({
                                            runCatching {
                                                runCatching { session.stopRepeating() }
                                                val still = camera
                                                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                                    .apply {
                                                        addTarget(reader.surface)
                                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                        set(
                                                            CaptureRequest.JPEG_ORIENTATION,
                                                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                                        )
                                                    }
                                                session.capture(
                                                    still.build(),
                                                    object : CameraCaptureSession.CaptureCallback() {
                                                        override fun onCaptureCompleted(
                                                            s: CameraCaptureSession,
                                                            rq: CaptureRequest,
                                                            result: TotalCaptureResult
                                                        ) {
                                                            handler.removeCallbacks(watchdog)
                                                            cleanup(camera, session); finish()
                                                        }

                                                        override fun onCaptureFailed(
                                                            s: CameraCaptureSession,
                                                            rq: CaptureRequest,
                                                            f: CaptureFailure
                                                        ) {
                                                            log("Capture: ${facingName(facing)} capture failed")
                                                            handler.removeCallbacks(watchdog)
                                                            cleanup(camera, session); finish()
                                                        }
                                                    },
                                                    handler
                                                )
                                            }.onFailure {
                                                handler.removeCallbacks(watchdog)
                                                cleanup(camera, session); finish()
                                            }
                                        }, 1200L)
                                    }.onFailure {
                                        handler.removeCallbacks(watchdog)
                                        cleanup(camera, session); finish()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    log("Capture: ${facingName(facing)} session config failed")
                                    handler.removeCallbacks(watchdog)
                                    cleanup(camera, session); finish()
                                }
                            },
                            handler
                        )
                    }.onFailure {
                        handler.removeCallbacks(watchdog)
                        cleanup(camera, null); finish()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    handler.removeCallbacks(watchdog)
                    cleanup(camera, null); finish()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("Capture: ${facingName(facing)} camera error $error")
                    handler.removeCallbacks(watchdog)
                    cleanup(camera, null); finish()
                }
            }, handler)
        }.onFailure {
            log("Capture: ${facingName(facing)} open failed (${it.javaClass.simpleName})")
            handler.removeCallbacks(watchdog)
            cleanup(null, null); finish()
        }
    }

    // ================= Video mode =================

    fun runVideo(dir: File, durationMs: Long, withAudio: Boolean) {
        handler.post {
            recordVideo(CameraCharacteristics.LENS_FACING_FRONT, File(dir, "video.mp4"), durationMs, withAudio) {
                runCatching { thread.quitSafely() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordVideo(facing: Int, out: File, durationMs: Long, withAudio: Boolean, onDone: () -> Unit) {
        val done = AtomicBoolean(false)
        fun finish() { if (done.compareAndSet(false, true)) onDone() }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            }
        }.getOrNull()
        if (camId == null) { log("Video: no ${facingName(facing)} camera"); finish(); return }

        val chars = cm.getCameraCharacteristics(camId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)
        val size = sizes?.minByOrNull {
            kotlin.math.abs(it.width.toLong() * it.height - 1280L * 720L)
        } ?: Size(1280, 720)

        val r = newRecorder()
        recorder = r
        val prepared = runCatching {
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(out.absolutePath)
            r.setVideoEncodingBitRate(5_000_000)
            r.setVideoFrameRate(30)
            r.setVideoSize(size.width, size.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(128_000)
            }
            val hint = if (facing == CameraCharacteristics.LENS_FACING_FRONT)
                (sensorOrientation + 270) % 360 else (sensorOrientation + 90) % 360
            r.setOrientationHint(hint)
            r.prepare()
        }.isSuccess
        if (!prepared) {
            log("Video: recorder prepare failed")
            runCatching { r.release() }; recorder = null
            finish(); return
        }

        val recorderSurface = r.surface
        val previewSt = SurfaceTexture(0).apply { setDefaultBufferSize(size.width, size.height) }
        val previewSurface = Surface(previewSt)

        val cleaned = AtomicBoolean(false)
        fun cleanup(camera: CameraDevice?, session: CameraCaptureSession?, started: Boolean) {
            if (!cleaned.compareAndSet(false, true)) return
            if (started) runCatching { r.stop() }
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { r.reset() }
            runCatching { r.release() }
            recorder = null
            runCatching { previewSurface.release() }
            runCatching { previewSt.release() }
        }

        val watchdog = Runnable {
            if (!done.get()) { log("Video: timed out"); cleanup(null, null, false); finish() }
        }
        handler.postDelayed(watchdog, durationMs + 8_000L)

        runCatching {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    runCatching {
                        camera.createCaptureSession(
                            listOf(previewSurface, recorderSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    runCatching {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(previewSurface)
                                            addTarget(recorderSurface)
                                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                        }
                                        session.setRepeatingRequest(req.build(), null, handler)
                                        r.start()
                                        log("Video: recording started (${durationMs / 1000}s)")
                                        handler.postDelayed({
                                            handler.removeCallbacks(watchdog)
                                            cleanup(camera, session, true)
                                            log("Video: saved")
                                            finish()
                                        }, durationMs)
                                    }.onFailure {
                                        log("Video: start failed")
                                        handler.removeCallbacks(watchdog)
                                        cleanup(camera, session, false); finish()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    log("Video: session config failed")
                                    handler.removeCallbacks(watchdog)
                                    cleanup(camera, session, false); finish()
                                }
                            },
                            handler
                        )
                    }.onFailure {
                        handler.removeCallbacks(watchdog)
                        cleanup(camera, null, false); finish()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    handler.removeCallbacks(watchdog)
                    cleanup(camera, null, false); finish()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("Video: camera error $error")
                    handler.removeCallbacks(watchdog)
                    cleanup(camera, null, false); finish()
                }
            }, handler)
        }.onFailure {
            log("Video: open failed (${it.javaClass.simpleName})")
            handler.removeCallbacks(watchdog)
            cleanup(null, null, false); finish()
        }
    }

    // ================= helpers =================

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context)
        else
            @Suppress("DEPRECATION") MediaRecorder()

    private fun facingName(f: Int): String =
        if (f == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
}
