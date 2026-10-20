package com.solorecord.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service is the sole owner of the capture session. All recorder operations are
 * serialized onto one handler thread to avoid stop/pause/projection callbacks racing.
 */
@Suppress("DEPRECATION")
class RecorderService : Service() {
    private val machine = RecordingStateMachine()
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var fileUri: Uri? = null
    private var recorderStarted = false
    private var startMs = 0L
    private var pauseStartedMs = 0L
    private var totalPauseMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val state = machine.state
            if (state != CaptureState.RECORDING && state != CaptureState.PAUSED) return
            RecorderStatus.update(state, if (state == CaptureState.PAUSED) "Paused" else "Recording screen", elapsed())
            worker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        workerThread = HandlerThread("SoloRecord-capture")
        workerThread.start()
        worker = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!machine.send(CaptureEvent.START)) return START_NOT_STICKY
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val quality = intent.getIntExtra(EXTRA_QUALITY, 1920).takeIf { it == 1280 || it == 1920 } ?: 1920
                val fps = intent.getIntExtra(EXTRA_FPS, 30).takeIf { it == 30 || it == 60 } ?: 30
                val microphone = intent.getBooleanExtra(EXTRA_MIC, false)
                val prefix = RecordingPolicy.safePrefix(intent.getStringExtra(EXTRA_PREFIX) ?: "SoloRecord")
                if (resultData == null || code != android.app.Activity.RESULT_OK ||
                    (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
                    failBeforeCapture("Missing screen or microphone permission")
                    return START_NOT_STICKY
                }
                try {
                    val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(CaptureState.STARTING), type)
                    else startForeground(NOTIFICATION_ID, notification(CaptureState.STARTING))
                    RecorderStatus.update(CaptureState.STARTING, "Preparing capture…")
                } catch (ex: RuntimeException) {
                    failBeforeCapture("Foreground-service permission rejected: ${ex.localizedMessage}")
                    return START_NOT_STICKY
                }
                worker.post { startCapture(code, resultData, quality, fps, microphone, prefix) }
            }
            ACTION_PAUSE -> {
                if (machine.state == CaptureState.IDLE) stopSelfResult(startId)
                else worker.post { pauseCapture() }
            }
            ACTION_RESUME -> {
                if (machine.state == CaptureState.IDLE) stopSelfResult(startId)
                else worker.post { resumeCapture() }
            }
            ACTION_STOP -> {
                if (machine.state == CaptureState.IDLE) stopSelfResult(startId)
                else worker.post { stopCapture() }
            }
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY // Never silently restart screen capture without new consent.
    }

    private fun failBeforeCapture(message: String) {
        machine.send(CaptureEvent.FAIL)
        RecorderStatus.update(CaptureState.IDLE, message)
        stopSelf()
    }

    private fun startCapture(
        resultCode: Int, resultData: Intent, quality: Int, fps: Int, microphone: Boolean, prefix: String
    ) {
        if (machine.state != CaptureState.STARTING) return
        try {
            val point = Point()
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point)
            val metrics = resources.displayMetrics
            val size = RecordingPolicy.videoSize(point.x, point.y, quality)

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SoloRecord/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            fileUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create a video entry")
            fileDescriptor = contentResolver.openFileDescriptor(fileUri!!, "rw")
                ?: error("Unable to open video output")

            val mediaRecorder = MediaRecorder()
            recorder = mediaRecorder
            if (microphone) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(size.width, size.height)
            mediaRecorder.setVideoFrameRate(fps)
            mediaRecorder.setVideoEncodingBitRate(RecordingPolicy.bitrate(size, fps))
            if (microphone) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder.setAudioChannels(1)
                mediaRecorder.setAudioSamplingRate(44100)
                mediaRecorder.setAudioEncodingBitRate(128_000)
            }
            mediaRecorder.setOutputFile(fileDescriptor!!.fileDescriptor)
            mediaRecorder.prepare()

            // Android 14+: use consent data exactly once; register callback before creating display.
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("System did not provide a capture session")
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    worker.post { stopCapture(fromProjection = true) }
                }
            }
            projectionCallback = callback
            projection!!.registerCallback(callback, worker)
            display = projection!!.createVirtualDisplay(
                "SoloRecord", size.width, size.height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, worker
            ) ?: error("Cannot create virtual display")

            mediaRecorder.start()
            recorderStarted = true
            startMs = SystemClock.elapsedRealtime()
            totalPauseMs = 0
            pauseStartedMs = 0
            if (!machine.send(CaptureEvent.READY)) error("Capture session stopped before start")
            RecorderStatus.update(CaptureState.RECORDING, "Recording screen")
            updateNotification()
            worker.post(ticker)
        } catch (ex: Exception) {
            worker.removeCallbacks(ticker)
            cleanResources(validVideo = false, fromProjection = false)
            machine.send(CaptureEvent.FAIL)
            RecorderStatus.update(CaptureState.IDLE, "Recording failed: ${ex.localizedMessage ?: ex.javaClass.simpleName}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun pauseCapture() {
        if (machine.state != CaptureState.RECORDING) return
        try {
            recorder?.pause() ?: error("Recorder unavailable")
            pauseStartedMs = SystemClock.elapsedRealtime()
            machine.send(CaptureEvent.PAUSE)
            RecorderStatus.update(CaptureState.PAUSED, "Paused", elapsed())
            updateNotification()
        } catch (ex: RuntimeException) {
            RecorderStatus.update(CaptureState.RECORDING, "Pause failed: ${ex.localizedMessage}", elapsed())
        }
    }

    private fun resumeCapture() {
        if (machine.state != CaptureState.PAUSED) return
        try {
            recorder?.resume() ?: error("Recorder unavailable")
            totalPauseMs += SystemClock.elapsedRealtime() - pauseStartedMs
            pauseStartedMs = 0
            machine.send(CaptureEvent.RESUME)
            RecorderStatus.update(CaptureState.RECORDING, "Recording screen", elapsed())
            updateNotification()
        } catch (ex: RuntimeException) {
            RecorderStatus.update(CaptureState.PAUSED, "Resume failed: ${ex.localizedMessage}", elapsed())
        }
    }

    private fun elapsed(): Long {
        if (startMs == 0L) return 0
        val until = if (pauseStartedMs != 0L) pauseStartedMs else SystemClock.elapsedRealtime()
        return (until - startMs - totalPauseMs).coerceAtLeast(0L)
    }

    private fun stopCapture(fromProjection: Boolean = false) {
        if (!machine.send(CaptureEvent.STOP)) return
        worker.removeCallbacks(ticker)
        RecorderStatus.update(CaptureState.STOPPING, "Finalizing video…", elapsed())
        var valid = false
        try {
            if (recorderStarted) {
                recorder!!.stop()  // Can throw if recording was too short or interrupted.
                valid = true
            }
        } catch (_: RuntimeException) {
            valid = false
        }
        val savedUri = fileUri
        val published = cleanResources(valid, fromProjection)
        machine.send(CaptureEvent.FINISH)
        if (published && savedUri != null) {
            Settings.setLastVideo(this, savedUri.toString())
            RecorderStatus.update(CaptureState.IDLE, "Saved to Movies/SoloRecord")
        } else {
            RecorderStatus.update(CaptureState.IDLE, "Recording too short or interrupted; unusable video discarded")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Finalize after recorder.stop, or discard potentially corrupt MP4 on any failure. */
    private fun cleanResources(validVideo: Boolean, fromProjection: Boolean): Boolean {
        try { display?.release() } catch (_: Exception) { }
        display = null
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        recorderStarted = false
        val activeProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (activeProjection != null && callback != null) {
            try { activeProjection.unregisterCallback(callback) } catch (_: Exception) { }
        }
        if (!fromProjection) {
            try { activeProjection?.stop() } catch (_: Exception) { }
        }
        try { fileDescriptor?.close() } catch (_: Exception) { }
        fileDescriptor = null
        val uri = fileUri
        fileUri = null
        var published = false
        if (uri != null) {
            try {
                if (validVideo) {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    published = contentResolver.update(uri, values, null, null) > 0
                }
                if (!published) contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Local storage can become unavailable; never report success without publish.
            }
        }
        return published
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(machine.state))
    }

    private fun notification(state: CaptureState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("SoloRecord")
            .setContentText(when (state) {
                CaptureState.PAUSED -> "Paused · Tap resume to continue"
                CaptureState.STARTING -> "Preparing your recording"
                CaptureState.STOPPING -> "Saving your video"
                else -> "Recording your screen"
            })
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (state == CaptureState.RECORDING) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause", actionIntent(ACTION_PAUSE, 1)).build())
        } else if (state == CaptureState.PAUSED) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_play, "Resume", actionIntent(ACTION_RESUME, 2)).build())
        }
        if (state == CaptureState.RECORDING || state == CaptureState.PAUSED || state == CaptureState.STARTING) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_STOP, 3)).build())
        }
        return builder.build()
    }

    private fun actionIntent(action: String, id: Int): PendingIntent = PendingIntent.getService(
        this, id, Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Recording controls", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Controls to pause, resume, and stop an active recording"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (::worker.isInitialized) {
            worker.removeCallbacks(ticker)
            worker.post {
                if (machine.state != CaptureState.IDLE) stopCapture()
                workerThread.quitSafely()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.solorecord.app.START"
        const val ACTION_PAUSE = "com.solorecord.app.PAUSE"
        const val ACTION_RESUME = "com.solorecord.app.RESUME"
        const val ACTION_STOP = "com.solorecord.app.STOP"
        const val EXTRA_RESULT_CODE = "consent_code"
        const val EXTRA_RESULT_DATA = "consent_data"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MIC = "microphone"
        const val EXTRA_PREFIX = "prefix"
        private const val CHANNEL_ID = "solorecord_capture"
        private const val NOTIFICATION_ID = 111
    }
}
