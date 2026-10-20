package com.solorecord.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Screenshots and recordings are only started after Android's visible consent dialog. */
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var openLastButton: Button
    private lateinit var quality: Spinner
    private lateinit var fps: Spinner
    private lateinit var microphone: Switch
    private lateinit var prefix: EditText

    private val ui = Handler(Looper.getMainLooper())
    private var pendingStart = false
    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        timer = findViewById(R.id.timerText)
        startButton = findViewById(R.id.startButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)
        openLastButton = findViewById(R.id.openLastButton)
        quality = findViewById(R.id.qualitySpinner)
        fps = findViewById(R.id.fpsSpinner)
        microphone = findViewById(R.id.micSwitch)
        prefix = findViewById(R.id.prefixEdit)

        val saved = Settings.read(this)
        quality.setSelection(if (saved.longEdge == 1280) 0 else 1)
        fps.setSelection(if (saved.fps == 60) 1 else 0)
        microphone.isChecked = saved.microphone
        prefix.setText(saved.prefix)

        startButton.setOnClickListener { onRecordClicked() }
        pauseButton.setOnClickListener {
            val action = if (RecorderStatus.snapshot.state == CaptureState.PAUSED) {
                RecorderService.ACTION_RESUME
            } else RecorderService.ACTION_PAUSE
            startService(Intent(this, RecorderService::class.java).setAction(action))
        }
        stopButton.setOnClickListener {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        }
        openLastButton.setOnClickListener { openLastVideo() }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun currentSettings() = RecorderSettings(
        longEdge = if (quality.selectedItemPosition == 0) 1280 else 1920,
        fps = if (fps.selectedItemPosition == 1) 60 else 30,
        microphone = microphone.isChecked,
        prefix = RecordingPolicy.safePrefix(prefix.text.toString())
    )

    private fun onRecordClicked() {
        if (RecorderStatus.snapshot.state != CaptureState.IDLE || pendingStart) return
        Settings.save(this, currentSettings())
        pendingStart = true
        checkPermissionsAndLaunch()
    }

    private fun checkPermissionsAndLaunch() {
        if (!pendingStart) return
        if (microphone.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("asked_notification", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notification", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            return
        }
        pendingStart = false
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (ex: RuntimeException) {
            showError("Could not request screen capture: ${ex.localizedMessage}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                pendingStart = false
                AlertDialog.Builder(this)
                    .setTitle("Microphone permission required")
                    .setMessage("Turn off microphone narration to record without audio, or allow microphone access in system settings.")
                    .setPositiveButton("OK", null).show()
            } else checkPermissionsAndLaunch()
        } else if (requestCode == REQUEST_NOTIFICATION) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification controls may be hidden. Use the app to pause or stop.", Toast.LENGTH_LONG).show()
            }
            checkPermissionsAndLaunch()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not authorized.", Toast.LENGTH_SHORT).show()
            return
        }
        if (RecorderStatus.snapshot.state != CaptureState.IDLE) return
        val config = Settings.read(this)
        val launch = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecorderService.EXTRA_RESULT_DATA, data)
            putExtra(RecorderService.EXTRA_QUALITY, config.longEdge)
            putExtra(RecorderService.EXTRA_FPS, config.fps)
            putExtra(RecorderService.EXTRA_MIC, config.microphone)
            putExtra(RecorderService.EXTRA_PREFIX, config.prefix)
        }
        try {
            RecorderStatus.update(CaptureState.STARTING, "Preparing capture…")
            startForegroundService(launch)
        } catch (ex: RuntimeException) {
            RecorderStatus.update(CaptureState.IDLE, "Could not start recording")
            showError("Android prevented recording from starting: ${ex.localizedMessage}")
        }
    }

    private fun refreshUi() {
        val snapshot = RecorderStatus.snapshot
        status.text = snapshot.message
        val seconds = snapshot.elapsedMs / 1000
        timer.text = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
        val idle = snapshot.state == CaptureState.IDLE
        startButton.isEnabled = idle && !pendingStart
        pauseButton.isEnabled = snapshot.state == CaptureState.RECORDING || snapshot.state == CaptureState.PAUSED
        stopButton.isEnabled = snapshot.state == CaptureState.RECORDING ||
            snapshot.state == CaptureState.PAUSED || snapshot.state == CaptureState.STARTING
        pauseButton.text = if (snapshot.state == CaptureState.PAUSED) "Resume" else "Pause"
        quality.isEnabled = idle
        fps.isEnabled = idle
        microphone.isEnabled = idle
        prefix.isEnabled = idle
        openLastButton.isEnabled = Settings.lastVideo(this) != null
    }

    private fun openLastVideo() {
        val value = Settings.lastVideo(this) ?: return
        try {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(value), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            showError("No app was found to play MP4 videos. Look in Movies/SoloRecord.")
        } catch (_: SecurityException) {
            showError("Cannot open this file. Check Movies/SoloRecord in your gallery.")
        }
    }

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("SoloRecord")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    companion object {
        private const val REQUEST_MICROPHONE = 201
        private const val REQUEST_NOTIFICATION = 202
        private const val REQUEST_CAPTURE = 203
    }
}
