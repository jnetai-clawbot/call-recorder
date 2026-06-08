package com.ciphervault.callrecorder

import android.Manifest
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import com.ciphervault.callrecorder.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CR_MainActivity"
        const val ERROR_PREFIX = "CR-MA-"
        private const val PERMISSION_REQUEST_CODE = 100

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
        }.toTypedArray()
    }

    enum class ErrorCode(val code: String, val description: String) {
        MA001("MA001", "Permission denied"),
        MA002("MA002", "Service binding failed"),
        MA003("MA003", "Start recording intent failed"),
        MA004("MA004", "Stop recording intent failed"),
        MA005("MA005", "Timer update error"),
        MA006("MA006", "UI state update error"),
        MA007("MA007", "Lifecycle observer error"),
        MA999("MA999", "Unknown main activity error")
    }

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var permissionsGranted = false

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logDebug("MainActivity onCreate")

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            reportError(ErrorCode.MA006, "Failed to inflate layout", e)
            return
        }

        setupUI()
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        binding.btnRecord.setOnClickListener {
            if (permissionsGranted) {
                toggleRecording()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.btnShare.setOnClickListener {
            val shareUrl = "https://github.com/jnetai-clawbot/call-recorder/releases/latest"
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pkgInfo.versionName ?: "1.0"
            val shareText = "Call Recorder v$version - Record calls to WAV/FLAC/MP3/AAC/OGG\n$shareUrl"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Call Recorder v$version")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Call Recorder"))
        }

        binding.btnRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsListActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (RecorderService.isServiceRunning || AudioEngine.isRecording()) {
            isRecording = true
            updateUIForRecordingState()
            startTimer()
        } else {
            isRecording = false
            updateUIForIdleState()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            permissionsGranted = true
            logDebug("All permissions already granted")
            return
        }

        logDebug("Requesting permissions: $permissionsToRequest")
        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            permissionsGranted = allGranted

            if (allGranted) {
                logDebug("All permissions granted")
            } else {
                val denied = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                val msg = "Permissions denied: $denied"
                reportError(ErrorCode.MA001, msg)
                Toast.makeText(this, "Permissions required for recording", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        logDebug("Starting recording via RecorderService")

        val intent = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            val msg = "Failed to start recording service"
            reportError(ErrorCode.MA003, msg, e)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        updateUIForRecordingState()
        startTimer()
        logDebug("Recording service started")
    }

    private fun stopRecording() {
        logDebug("Stopping recording")

        val intent = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_STOP
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            val msg = "Failed to stop recording service"
            reportError(ErrorCode.MA004, msg, e)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        isRecording = false
        stopTimer()
        updateUIForIdleState()
        logDebug("Recording stopped")
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (isActive && isRecording) {
                try {
                    binding.tvTimer.text = AudioEngine.getFormattedDuration()
                    delay(200)
                } catch (e: Exception) {
                    reportError(ErrorCode.MA005, "Timer update error", e)
                    break
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun updateUIForRecordingState() {
        try {
            binding.btnRecord.text = "STOP RECORDING"
            binding.btnRecord.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
            binding.tvStatus.text = "Recording..."
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_light)
            )
            binding.tvTimer.visibility = View.VISIBLE
            binding.pulseView.visibility = View.VISIBLE
        } catch (e: Exception) {
            reportError(ErrorCode.MA006, "UI update error (recording)", e)
        }
    }

    private fun updateUIForIdleState() {
        try {
            binding.btnRecord.text = "START RECORDING"
            binding.btnRecord.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
            binding.tvStatus.text = "Ready"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.darker_gray)
            )
            binding.tvTimer.visibility = View.GONE
            binding.pulseView.visibility = View.GONE
        } catch (e: Exception) {
            reportError(ErrorCode.MA006, "UI update error (idle)", e)
        }
    }

    override fun onDestroy() {
        stopTimer()
        scope.cancel()
        super.onDestroy()
    }
}
