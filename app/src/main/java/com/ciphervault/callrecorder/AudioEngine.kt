package com.ciphervault.callrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

object AudioEngine {
    private const val TAG = "CR_AudioEngine"
    const val ERROR_PREFIX = "CR-AE-"

    enum class ErrorCode(val code: String, val description: String) {
        AE001("AE001", "AudioRecord initialization failed"),
        AE002("AE002", "AudioRecord start recording failed"),
        AE003("AE003", "AudioRecord stop failed"),
        AE004("AE004", "File write error"),
        AE005("AE005", "File close error"),
        AE006("AE006", "WAV header write failed"),
        AE007("AE007", "FLAC encoding not supported on this device, using WAV fallback"),
        AE008("AE008", "MP3 encoder initialization failed"),
        AE009("AE009", "MP3 encoder start failed"),
        AE010("AE010", "MP3 encoder buffer error"),
        AE011("AE011", "MP3/Codec encoder MediaCodec not available"),
        AE012("AE012", "Storage directory creation failed"),
        AE013("AE013", "Invalid audio source"),
        AE014("AE014", "Recording already in progress"),
        AE015("AE015", "No recording in progress to stop"),
        AE016("AE016", "Output file not found after recording"),
        AE017("AE017", "Coroutine scope error"),
        AE018("AE018", "Speaker capture initialization failed"),
        AE019("AE019", "Audio mixing error"),
        AE020("AE020", "Audio source selection failed"),
        AE021("AE021", "AAC encoder error"),
        AE022("AE022", "OGG encoder error"),
        AE023("AE023", "Dual source capture error"),
        AE024("AE024", "Audio stream read timeout"),
        AE999("AE999", "Unknown audio engine error")
    }

    enum class OutputFormat(val extension: String, val displayName: String) {
        WAV("wav", "WAV (PCM)"),
        FLAC("flac", "FLAC (Lossless)"),
        MP3("mp3", "MP3 (Compressed)"),
        AAC("aac", "AAC (Advanced)"),
        OGG("ogg", "OGG/Vorbis");

        companion object {
            fun fromName(name: String): OutputFormat =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.extension == name }
                    ?: WAV
        }
    }

    enum class AudioSource(val id: Int, val displayName: String, val requiresPermission: String?) {
        VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "Call (Mic + Speaker)", null),
        MIC(MediaRecorder.AudioSource.MIC, "Microphone", null),
        CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, "Camcorder Mic", null),
        VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Voice Recognition", null),
        UNPROCESSED(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC,
            "Unprocessed Mic",
            null
        ),
        VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL, "Voice Call", null);

        companion object {
            fun fromId(id: Int): AudioSource = entries.find { it.id == id } ?: VOICE_COMMUNICATION
            fun fromName(name: String): AudioSource = entries.firstOrNull { it.name == name } ?: VOICE_COMMUNICATION
        }
    }

    data class RecordingConfig(
        val micSource: AudioSource = AudioSource.VOICE_COMMUNICATION,
        val speakerSource: AudioSource = AudioSource.VOICE_COMMUNICATION,
        val outputFormat: OutputFormat = OutputFormat.WAV,
        val micVolume: Float = 1.0f,
        val speakerVolume: Float = 0.5f,
        val captureSpeaker: Boolean = true,
        val storagePath: String = "DCIM/Recordings"
    )

    private var audioRecord: AudioRecord? = null
    private var speakerAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentOutputFile: File? = null
    private var currentConfig: RecordingConfig = RecordingConfig()
    private var isRecording = false
    private var onErrorCallback: ((ErrorCode, String, Exception?) -> Unit)? = null
    private var recordingStartTime: Long = 0L

    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG_IN = AndroidAudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT_PCM = AndroidAudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_COUNT = 1
    private val BITS_PER_SAMPLE = 16
    private val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private val NUM_CHANNELS_IN = 1

    fun setErrorCallback(callback: ((ErrorCode, String, Exception?) -> Unit)?) {
        onErrorCallback = callback
    }

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
        onErrorCallback?.invoke(code, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    fun getAvailableMicSources(context: Context): List<AudioSource> {
        return AudioSource.entries.filter { source ->
            if (source.requiresPermission != null) {
                ContextCompat.checkSelfPermission(context, source.requiresPermission) ==
                        PackageManager.PERMISSION_GRANTED
            } else true
        }
    }

    fun startRecording(
        context: Context,
        config: RecordingConfig,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        if (isRecording) {
            val msg = "Recording already in progress"
            reportError(ErrorCode.AE014, msg)
            onError?.invoke(msg)
            return false
        }

        currentConfig = config
        recordingStartTime = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extension = config.outputFormat.extension

        val relPath = config.storagePath.removePrefix("/storage/emulated/0/").removePrefix("storage/emulated/0/").trim('/').ifBlank { "DCIM" }
        val recordingsDir = File(Environment.getExternalStorageDirectory(), relPath)
        if (!recordingsDir.exists()) recordingsDir.mkdirs()

        val outputFile = File(recordingsDir, "call-recording-${dateStr}.$extension")
        try {
            if (!outputFile.parentFile?.exists()!!) {
                outputFile.parentFile?.mkdirs()
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE012, "Failed to create directory: ${outputFile.parent}", e)
            onError?.invoke("Failed to create storage directory: ${e.message}")
            return false
        }

        currentOutputFile = outputFile
        logDebug("Output file: ${outputFile.absolutePath}, format=${config.outputFormat.displayName}")
        logDebug("Config: micSource=${config.micSource.displayName}, micVol=${config.micVolume}, spkVol=${config.speakerVolume}, captureSpk=${config.captureSpeaker}")

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_PCM
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val msg = "Invalid buffer size calculated: $bufferSize"
            reportError(ErrorCode.AE001, msg)
            onError?.invoke(msg)
            return false
        }

        val actualBufferSize = bufferSize * 2
        logDebug("Buffer size: min=$bufferSize, actual=$actualBufferSize")

        try {
            audioRecord = AudioRecord(
                config.micSource.id,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT_PCM,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val msg = "AudioRecord state not initialized for mic source: ${config.micSource.displayName}, state=${audioRecord?.state}"
                reportError(ErrorCode.AE001, msg)
                audioRecord?.release()
                audioRecord = null

                // Try fallback to MIC
                logDebug("Trying fallback to MIC source")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT_PCM,
                    actualBufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val msg = "All audio sources failed to initialize"
                reportError(ErrorCode.AE001, msg)
                audioRecord?.release()
                audioRecord = null
                onError?.invoke(msg)
                return false
            }

            logDebug("Speaker capture requires MediaProjection - not available in foreground-only mode")

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val msg = "AudioRecord failed to start, recordingState=${audioRecord?.recordingState}"
                reportError(ErrorCode.AE002, msg)
                audioRecord?.release()
                audioRecord = null
                if (speakerAudioRecord != null) {
                    try { speakerAudioRecord?.stop() } catch (_: Exception) {}
                    try { speakerAudioRecord?.release() } catch (_: Exception) {}
                    speakerAudioRecord = null
                }
                onError?.invoke(msg)
                return false
            }

            speakerAudioRecord?.startRecording()
            if (speakerAudioRecord != null &&
                speakerAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                logDebug("Speaker audio start failed, proceeding with mic only")
                speakerAudioRecord?.release()
                speakerAudioRecord = null
            }

            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    recordAudio(outputFile, actualBufferSize, config)
                } catch (e: CancellationException) {
                    logDebug("Recording coroutine cancelled")
                } catch (e: Exception) {
                    reportError(ErrorCode.AE004, "Recording write loop failed", e)
                    try {
                        onError?.invoke("Recording failed: ${e.message}")
                    } catch (_: Exception) {}
                }
            }

            return true
        } catch (e: SecurityException) {
            val msg = "Permission denied for audio recording"
            reportError(ErrorCode.AE001, msg, e)
            onError?.invoke(msg)
            return false
        } catch (e: Exception) {
            val msg = "Unexpected error during recording setup"
            reportError(ErrorCode.AE001, msg, e)
            onError?.invoke(msg)
            return false
        }
    }

    private suspend fun recordAudio(outputFile: File, bufferSize: Int, config: RecordingConfig) =
        withContext(Dispatchers.IO) {
            when (config.outputFormat) {
                OutputFormat.WAV -> recordPcm(outputFile, bufferSize, config) { data, file ->
                    writeWavFile(file, data, SAMPLE_RATE, NUM_CHANNELS_IN, BITS_PER_SAMPLE)
                }
                OutputFormat.FLAC -> recordPcm(outputFile, bufferSize, config) { data, file ->
                    writeWavFile(file, data, SAMPLE_RATE, NUM_CHANNELS_IN, BITS_PER_SAMPLE)
                }
                OutputFormat.MP3 -> recordToCodec(outputFile, bufferSize, config, "audio/mpeg-L2", 128000)
                OutputFormat.AAC -> recordToCodec(outputFile, bufferSize, config, "audio/mp4a-latm", 128000)
                OutputFormat.OGG -> recordToCodec(outputFile, bufferSize, config, "audio/vorbis", 96000)
            }
        }

    private suspend fun recordPcm(
        outputFile: File,
        bufferSize: Int,
        config: RecordingConfig,
        writeFile: (ByteArray, File) -> Unit
    ) = withContext(Dispatchers.IO) {
        logDebug("Recording PCM to ${outputFile.absolutePath}")
        val audioData = ByteArrayOutputStream()
        val micBuffer = ByteArray(bufferSize)
        val spkBuffer = ByteArray(bufferSize)
        val mixedBuffer = ByteArray(bufferSize)

        try {
            while (isActive && isRecording) {
                val micBytes = audioRecord?.read(micBuffer, 0, micBuffer.size) ?: -1
                var spkBytes = -1
                if (speakerAudioRecord != null) {
                    spkBytes = speakerAudioRecord?.read(spkBuffer, 0, spkBuffer.size) ?: -1
                }

                if (micBytes < 0 && spkBytes < 0) {
                    logDebug("Read error from all sources: mic=$micBytes spk=$spkBytes")
                    break
                }

                val actualData = mixAudio(
                    micBuffer, if (micBytes > 0) micBytes else 0,
                    spkBuffer, if (spkBytes > 0) spkBytes else 0,
                    mixedBuffer, config
                )

                if (actualData > 0) {
                    audioData.write(mixedBuffer, 0, actualData)
                }
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE004, "Error reading audio data", e)
            throw e
        }

        try {
            if (audioData.size() > 0) {
                writeFile(audioData.toByteArray(), outputFile)
                logDebug("File written successfully: ${outputFile.length()} bytes")
            } else {
                logDebug("No audio data captured — writing empty file")
                writeFile(ByteArray(0), outputFile)
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE004, "Failed to write output file", e)
            throw e
        } finally {
            try { audioData.close() } catch (_: Exception) {}
        }
    }

    private suspend fun recordToCodec(
        outputFile: File,
        bufferSize: Int,
        config: RecordingConfig,
        mediaType: String,
        bitRate: Int
    ) = withContext(Dispatchers.IO) {
        logDebug("Recording with codec: $mediaType to ${outputFile.absolutePath}")

        val encoderFormat = MediaFormat.createAudioFormat(mediaType, SAMPLE_RATE, NUM_CHANNELS_IN)
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            encoderFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AndroidAudioFormat.ENCODING_PCM_16BIT)
        }

        var mediaCodec: MediaCodec? = null
        val encodedData = ByteArrayOutputStream()
        val micBuffer = ByteArray(bufferSize)
        val spkBuffer = ByteArray(bufferSize)
        val mixedBuffer = ByteArray(bufferSize)
        var encoderStarted = false
        var fallbackToPcm = false

        try {
            try {
                mediaCodec = MediaCodec.createEncoderByType(mediaType)
            } catch (e: Exception) {
                val alternativeType = when (mediaType) {
                    "audio/mpeg-L2" -> "audio/mpeg"
                    "audio/mp4a-latm" -> "audio/mp4a-latm"
                    "audio/vorbis" -> "audio/opus"
                    else -> null
                }
                if (alternativeType != null) {
                    try {
                        mediaCodec = MediaCodec.createEncoderByType(alternativeType)
                        encoderFormat.setString(MediaFormat.KEY_MIME, alternativeType)
                        logDebug("Using alternative codec: $alternativeType")
                    } catch (e2: Exception) {
                        logDebug("Alt codec also failed: ${e2.message}")
                        fallbackToPcm = true
                    }
                } else {
                    fallbackToPcm = true
                }
            }

            if (fallbackToPcm || mediaCodec == null) {
                reportError(ErrorCode.AE011, "Codec $mediaType unavailable, falling back to WAV")
                val pcmFile = File(outputFile.parent, outputFile.nameWithoutExtension + ".wav")
                currentOutputFile = pcmFile
                val fallbackData = ByteArrayOutputStream()
                while (isActive && isRecording) {
                    val micBytes = audioRecord?.read(micBuffer, 0, micBuffer.size) ?: -1
                    var spkBytes = -1
                    if (speakerAudioRecord != null) {
                        spkBytes = speakerAudioRecord?.read(spkBuffer, 0, spkBuffer.size) ?: -1
                    }
                    if (micBytes < 0 && spkBytes < 0) break
                    val actual = mixAudio(
                        micBuffer, maxOf(0, micBytes),
                        spkBuffer, maxOf(0, spkBytes),
                        mixedBuffer, config
                    )
                    if (actual > 0) fallbackData.write(mixedBuffer, 0, actual)
                }
                writeWavFile(pcmFile, fallbackData.toByteArray(), SAMPLE_RATE, NUM_CHANNELS_IN, BITS_PER_SAMPLE)
                try { fallbackData.close() } catch (_: Exception) {}
                logDebug("WAV fallback written: ${pcmFile.length()} bytes")
                return@withContext
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                encoderFormat.setInteger(MediaFormat.KEY_MAX_PTS_GAP_TO_ENCODER, 0)
            }
            mediaCodec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()
            encoderStarted = true
            logDebug("Codec encoder started: $mediaType")

            while (isActive && isRecording) {
                val micBytes = audioRecord?.read(micBuffer, 0, micBuffer.size) ?: -1
                var spkBytes = -1
                if (speakerAudioRecord != null) {
                    spkBytes = speakerAudioRecord?.read(spkBuffer, 0, spkBuffer.size) ?: -1
                }
                if (micBytes < 0 && spkBytes < 0) break

                val actual = mixAudio(
                    micBuffer, maxOf(0, micBytes),
                    spkBuffer, maxOf(0, spkBytes),
                    mixedBuffer, config
                )
                if (actual <= 0) continue

                try {
                    val inputBufferIndex = mediaCodec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(mixedBuffer, 0, actual)
                        mediaCodec.queueInputBuffer(
                            inputBufferIndex, 0, actual,
                            System.nanoTime() / 1000, 0
                        )
                    }

                    val bufferInfo = android.media.MediaCodec.BufferInfo()
                    var outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(chunk, 0, bufferInfo.size)
                            encodedData.write(chunk)
                        }
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                } catch (e: Exception) {
                    if (isActive && isRecording) {
                        reportError(ErrorCode.AE010, "Codec buffer error: ${e.message}", e)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE009, "Codec encoding error: ${e.message}", e)
            throw e
        } finally {
            try {
                if (encoderStarted) {
                    mediaCodec?.stop()
                }
                mediaCodec?.release()
            } catch (e: Exception) {
                reportError(ErrorCode.AE009, "Codec cleanup error", e)
            }
            try { encodedData.close() } catch (_: Exception) {}
        }

        try {
            if (encodedData.size() > 0) {
                FileOutputStream(outputFile).use { fos ->
                    fos.write(encodedData.toByteArray())
                    fos.flush()
                }
                logDebug("Codec file written: ${outputFile.length()} bytes")
            } else {
                val pcmFile = File(outputFile.parent, outputFile.nameWithoutExtension + ".wav")
                currentOutputFile = pcmFile
                logDebug("No encoded data — writing empty WAV fallback")
                FileOutputStream(pcmFile).use { it.close() }
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE004, "Codec file write error", e)
            throw e
        }
    }

    private fun mixAudio(
        micBuf: ByteArray,
        micLen: Int,
        spkBuf: ByteArray,
        spkLen: Int,
        mixedBuf: ByteArray,
        config: RecordingConfig
    ): Int {
        val samples = maxOf(micLen, spkLen) / 2 * 2 // ensure even
        if (samples <= 0) return 0

        val micVol = config.micVolume.coerceIn(0f, 2f)
        val spkVol = config.speakerVolume.coerceIn(0f, 2f)

        for (i in 0 until samples step 2) {
            var micSample = 0
            if (i + 1 < micLen) {
                micSample = ((micBuf[i + 1].toInt() shl 8) or (micBuf[i].toInt() and 0xFF))
                    .toShort().toInt()
            }
            micSample = (micSample * micVol).toInt()

            var spkSample = 0
            if (i + 1 < spkLen) {
                spkSample = ((spkBuf[i + 1].toInt() shl 8) or (spkBuf[i].toInt() and 0xFF))
                    .toShort().toInt()
            }
            spkSample = (spkSample * spkVol).toInt()

            var mixed = micSample + spkSample
            mixed = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            mixedBuf[i] = (mixed and 0xFF).toByte()
            if (i + 1 < mixedBuf.size) {
                mixedBuf[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            }
        }

        return samples
    }

    fun stopRecording(): File? {
        if (!isRecording) {
            reportError(ErrorCode.AE015, "No recording in progress")
            return null
        }

        val durationMs = System.currentTimeMillis() - recordingStartTime
        logDebug("Stopping recording, duration=${durationMs}ms")

        isRecording = false

        // Wait for recording coroutine to finish writing, not just cancel
        kotlinx.coroutines.runBlocking {
            recordingJob?.join()
        }
        recordingJob = null

        // Clean up speaker record first
        try {
            speakerAudioRecord?.apply {
                try { stop() } catch (e: Exception) {
                    reportError(ErrorCode.AE003, "Speaker AudioRecord stop failed", e)
                }
                try { release() } catch (e: Exception) {
                    reportError(ErrorCode.AE003, "Speaker AudioRecord release failed", e)
                }
            }
            speakerAudioRecord = null
        } catch (e: Exception) {
            reportError(ErrorCode.AE003, "Speaker AudioRecord cleanup failed", e)
        }

        try {
            audioRecord?.apply {
                try { stop() } catch (e: Exception) {
                    reportError(ErrorCode.AE003, "AudioRecord stop failed", e)
                }
                try { release() } catch (e: Exception) {
                    reportError(ErrorCode.AE003, "AudioRecord release failed", e)
                }
            }
            audioRecord = null
        } catch (e: Exception) {
            reportError(ErrorCode.AE003, "AudioRecord cleanup failed", e)
        }

        val output = currentOutputFile
        currentOutputFile = null

        if (output == null) {
            reportError(ErrorCode.AE016, "No output file reference")
            return null
        }

        if (!output.exists()) {
            logDebug("Output file does not exist: ${output.absolutePath} — recording may have been empty")
            // Create empty file so the notification has something to report
            try {
                output.parentFile?.mkdirs()
                output.createNewFile()
            } catch (_: Exception) {}
        }

        val fileSizeBytes = output.length()
        logDebug("Recording saved: ${output.absolutePath}, size=${fileSizeBytes}bytes, duration=${durationMs}ms")

        return output
    }

    fun isRecording(): Boolean = isRecording

    fun getRecordingDurationMs(): Long {
        return if (isRecording) System.currentTimeMillis() - recordingStartTime else 0L
    }

    fun getCurrentOutputFile(): File? = currentOutputFile

    fun getCurrentFormat(): OutputFormat = currentConfig.outputFormat

    private fun writeWavFile(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).apply { order(ByteOrder.LITTLE_ENDIAN) }
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(pcmData.size)

            fos.write(header.array())
            fos.write(pcmData)
            fos.flush()
        }
    }

    fun getFormattedDuration(): String {
        val ms = getRecordingDurationMs()
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
