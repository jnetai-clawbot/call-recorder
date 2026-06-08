package com.ciphervault.callrecorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

class RecorderService : Service() {
    companion object {
        private const val TAG = "CR_RecorderService"
        const val ERROR_PREFIX = "CR-RS-"
        const val CHANNEL_ID = "call_recorder_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.ciphervault.callrecorder.START_RECORDING"
        const val ACTION_STOP = "com.ciphervault.callrecorder.STOP_RECORDING"
        const val ACTION_PAUSE = "com.ciphervault.callrecorder.PAUSE_RECORDING"

        var isServiceRunning = false
            private set
    }

    enum class ErrorCode(val code: String, val description: String) {
        RS001("RS001", "Service start failed"),
        RS002("RS002", "Foreground service permission denied"),
        RS003("RS003", "Notification channel creation failed"),
        RS004("RS004", "Recording start failed from service"),
        RS005("RS005", "Recording stop failed from service"),
        RS999("RS999", "Unknown service error")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onCreate() {
        super.onCreate()
        logDebug("RecorderService onCreate")
        createNotificationChannel()
        AudioEngine.setErrorCallback { code, message, exception ->
            reportError(
                ErrorCode.entries.firstOrNull { it.code == code.code } ?: ErrorCode.RS999,
                message,
                exception
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logDebug("onStartCommand: action=${intent?.action}")

        if (intent == null) {
            reportError(ErrorCode.RS001, "Received null intent")
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val config = loadRecordingConfig()
                startForegroundRecording(config)
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> stopRecording()
        }

        return START_NOT_STICKY
    }

    private fun loadRecordingConfig(): AudioEngine.RecordingConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val micSourceName = prefs.getString(
            SettingsActivity.PREF_MIC_SOURCE,
            AudioEngine.AudioSource.VOICE_COMMUNICATION.name
        ) ?: AudioEngine.AudioSource.VOICE_COMMUNICATION.name

        val outputFormatName = prefs.getString(
            SettingsActivity.PREF_FORMAT,
            AudioEngine.OutputFormat.WAV.name
        ) ?: AudioEngine.OutputFormat.WAV.name

        val micVolume = prefs.getFloat(SettingsActivity.PREF_MIC_VOLUME, 1.0f)
        val speakerVolume = prefs.getFloat(SettingsActivity.PREF_SPEAKER_VOLUME, 0.5f)
        val captureSpeaker = prefs.getBoolean(SettingsActivity.PREF_CAPTURE_SPEAKER, true)
        val storagePath = prefs.getString(
            SettingsActivity.PREF_STORAGE_PATH,
            "DCIM/Recordings"
        ) ?: "DCIM/Recordings"

        return AudioEngine.RecordingConfig(
            micSource = AudioEngine.AudioSource.fromName(micSourceName),
            outputFormat = AudioEngine.OutputFormat.fromName(outputFormatName),
            micVolume = micVolume,
            speakerVolume = speakerVolume,
            captureSpeaker = captureSpeaker,
            storagePath = storagePath
        )
    }

    private fun startForegroundRecording(config: AudioEngine.RecordingConfig) {
        logDebug("Starting foreground recording, config=$config")

        try {
            val notification = buildNotification("Recording in progress...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isServiceRunning = true
        } catch (e: SecurityException) {
            reportError(ErrorCode.RS002, "Foreground service permission denied", e)
            stopSelf()
            return
        } catch (e: Exception) {
            reportError(ErrorCode.RS002, "Failed to start foreground service", e)
            stopSelf()
            return
        }

        val success = AudioEngine.startRecording(this, config) { errorMsg ->
            reportError(ErrorCode.RS004, errorMsg)
            stopSelf()
        }

        if (!success) {
            reportError(ErrorCode.RS004, "AudioEngine.startRecording returned false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            isServiceRunning = false
            stopSelf()
        } else {
            logDebug("Recording started successfully")
            updateNotification("Recording... ${config.outputFormat.displayName}")
        }
    }

    private fun stopRecording() {
        logDebug("Stopping recording from service")
        try {
            val outputFile = AudioEngine.stopRecording()
            if (outputFile != null) {
                logDebug("Recording saved to: ${outputFile.absolutePath}")
                showCompletionNotification(outputFile)
            } else {
                logDebug("stopRecording returned null")
            }
        } catch (e: Exception) {
            reportError(ErrorCode.RS005, "Error stopping recording", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isServiceRunning = false
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when call recording is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            reportError(ErrorCode.RS003, "Failed to create notification channel", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(outputFile: File) {
        val channelId = "recording_complete"
        try {
            val channel = NotificationChannel(
                channelId,
                "Recording Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            logDebug("Completion channel creation failed: ${e.message}")
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Saved")
            .setContentText("File: ${outputFile.name}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logDebug("RecorderService onDestroy")
        serviceScope.cancel()
        if (AudioEngine.isRecording()) {
            AudioEngine.stopRecording()
        }
        isServiceRunning = false
        AudioEngine.setErrorCallback(null)
        super.onDestroy()
    }
}
