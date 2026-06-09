package com.ciphervault.callrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        AE025("AE025", "Recording file write incomplete"),
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

    data class DeviceInfo(val id: Int, val name: String, val typeName: String)

    data class RecordingConfig(
        val micSource: AudioSource = AudioSource.VOICE_COMMUNICATION,
        val speakerSource: AudioSource = AudioSource.VOICE_CALL,
        val outputFormat: OutputFormat = OutputFormat.WAV,
        val micVolume: Float = 1.0f,
        val speakerVolume: Float = 0.5f,
        val captureSpeaker: Boolean = true,
        val storagePath: String = "DCIM"
    )

    private var audioRecord: AudioRecord? = null
    private var speakerAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentOutputFile: File? = null
    private var currentConfig: RecordingConfig = RecordingConfig()
    @Volatile private var isRecording = false
    private var onErrorCallback: ((ErrorCode, String, Exception?) -> Unit)? = null
    private var recordingStartTime: Long = 0L
    private var writeCompleteLatch: CountDownLatch? = null

    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG_IN = AndroidAudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT_PCM = AndroidAudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_COUNT = 1
    private val BITS_PER_SAMPLE = 16
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

    fun getDetectedDevices(context: Context): List<DeviceInfo> {
        val devices = mutableListOf<DeviceInfo>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                for (device in inputDevices) {
                    val typeStr = when (device.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
                        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
                        AudioDeviceInfo.TYPE_BUS -> "Internal Bus"
                        else -> "Device #${device.type}"
                    }
                    val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        device.productName?.toString() ?: typeStr
                    } else typeStr
                    devices.add(DeviceInfo(device.id, productName, typeStr))
                }
            }
        } catch (e: Exception) {
            logDebug("Device detection error: ${e.message}")
        }
        return devices
    }

    fun getAvailableMicSources(context: Context): List<AudioSource> {
        return AudioSource.entries
    }

    fun getAvailableSpeakerSources(context: Context): List<AudioSource> {
        return AudioSource.entries
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

        // Always use app-private external storage (writable on all Android versions)
        val recordingsDir = File(context.getExternalFilesDir(null), "Recordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()

        val outputFile = File(recordingsDir, "call-recording-${dateStr}.$extension")
        logDebug("Output file: ${outputFile.absolutePath}")
        currentOutputFile = outputFile

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_PCM)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val msg = "Invalid buffer size: $bufferSize"
            reportError(ErrorCode.AE001, msg)
            onError?.invoke(msg)
            return false
        }
        val actualBufferSize = bufferSize * 2

        try {
            // Create MIC audio record
            audioRecord = AudioRecord(config.micSource.id, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_PCM, actualBufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_PCM, actualBufferSize)
            }
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                val msg = "Mic AudioRecord failed to initialize"
                reportError(ErrorCode.AE001, msg)
                onError?.invoke(msg)
                return false
            }
            logDebug("Mic AudioRecord created for source: ${config.micSource.displayName}")

            // Create SPEAKER audio record (second AudioRecord for downlink capture)
            if (config.captureSpeaker) {
                val spkSourceId = config.speakerSource.id
                speakerAudioRecord = AudioRecord(spkSourceId, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_PCM, actualBufferSize)
                if (speakerAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    logDebug("Speaker AudioRecord init failed with source ${config.speakerSource.displayName}, trying MIC")
                    speakerAudioRecord?.release()
                    speakerAudioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_PCM, actualBufferSize)
                }
                if (speakerAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    speakerAudioRecord?.release()
                    speakerAudioRecord = null
                    logDebug("Speaker AudioRecord unavailable — single source mode")
                } else {
                    logDebug("Speaker AudioRecord created for source: ${config.speakerSource.displayName}")
                }
            }

            // Start both
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.release(); audioRecord = null
                speakerAudioRecord?.release(); speakerAudioRecord = null
                val msg = "Mic AudioRecord failed to start"
                reportError(ErrorCode.AE002, msg)
                onError?.invoke(msg)
                return false
            }

            speakerAudioRecord?.startRecording()
            if (speakerAudioRecord != null && speakerAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                speakerAudioRecord?.release()
                speakerAudioRecord = null
                logDebug("Speaker AudioRecord failed to start — single source mode")
            }

            writeCompleteLatch = CountDownLatch(1)
            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    recordAudio(outputFile, actualBufferSize, config)
                } catch (e: CancellationException) {
                    logDebug("Recording cancelled")
                } catch (e: Exception) {
                    reportError(ErrorCode.AE004, "Recording loop failed", e)
                } finally {
                    writeCompleteLatch?.countDown()
                }
            }

            return true
        } catch (e: SecurityException) {
            reportError(ErrorCode.AE001, "Permission denied", e)
            onError?.invoke("Permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            reportError(ErrorCode.AE001, "Unexpected error", e)
            onError?.invoke(e.message ?: "Unknown error")
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
        outputFile: File, bufferSize: Int, config: RecordingConfig,
        writeFile: (ByteArray, File) -> Unit
    ) = withContext(Dispatchers.IO) {
        logDebug("Recording PCM to ${outputFile.absolutePath}")
        val audioData = ByteArrayOutputStream()
        val micBuf = ByteArray(bufferSize)
        val spkBuf = ByteArray(bufferSize)
        val mixedBuf = ByteArray(bufferSize)

        var totalFrames = 0L
        var consecutiveErrors = 0
        try {
            while (isActive && isRecording) {
                val micBytes = audioRecord?.read(micBuf, 0, micBuf.size) ?: -1
                var spkBytes = -1
                if (speakerAudioRecord != null) {
                    spkBytes = speakerAudioRecord?.read(spkBuf, 0, spkBuf.size) ?: -1
                }

                if (micBytes < 0 && spkBytes < 0) {
                    consecutiveErrors++
                    if (consecutiveErrors > 5) {
                        logDebug("Too many consecutive read errors, stopping")
                        break
                    }
                    Thread.sleep(50)
                    continue
                }
                consecutiveErrors = 0

                val mixedLen = mixAudio(
                    micBuf, maxOf(0, micBytes),
                    spkBuf, maxOf(0, spkBytes),
                    mixedBuf, config
                )
                if (mixedLen > 0) {
                    audioData.write(mixedBuf, 0, mixedLen)
                    totalFrames += mixedLen / 2
                }
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE004, "Audio read error", e)
        }

        val pcmBytes = audioData.toByteArray()
        try { audioData.close() } catch (_: Exception) {}
        logDebug("Recording finished: ${pcmBytes.size} bytes, ~${totalFrames / SAMPLE_RATE}s")

        if (pcmBytes.isNotEmpty()) {
            writeFile(pcmBytes, outputFile)
            logDebug("File written: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
        } else {
            outputFile.createNewFile()
            logDebug("Empty recording saved as: ${outputFile.absolutePath}")
        }
    }

    private suspend fun recordToCodec(
        outputFile: File, bufferSize: Int, config: RecordingConfig,
        mediaType: String, bitRate: Int
    ) = withContext(Dispatchers.IO) {
        logDebug("Recording codec: $mediaType")
        val micBuf = ByteArray(bufferSize)
        val spkBuf = ByteArray(bufferSize)
        val mixedBuf = ByteArray(bufferSize)
        var fallbackToWav = false

        val encodedData = ByteArrayOutputStream()
        var mediaCodec: MediaCodec? = null
        var encoderStarted = false

        val encoderFormat = MediaFormat.createAudioFormat(mediaType, SAMPLE_RATE, NUM_CHANNELS_IN)
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)

        try {
            try {
                mediaCodec = MediaCodec.createEncoderByType(mediaType)
            } catch (_: Exception) {
                try { mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm") }
                catch (_: Exception) { fallbackToWav = true }
            }

            if (fallbackToWav || mediaCodec == null) {
                reportError(ErrorCode.AE011, "Codec unavailable, using WAV")
                val wavFile = File(outputFile.parent, outputFile.nameWithoutExtension + ".wav")
                currentOutputFile = wavFile
                val raw = ByteArrayOutputStream()
                while (isActive && isRecording) {
                    val micBytes = audioRecord?.read(micBuf, 0, micBuf.size) ?: -1
                    var spkBytes = -1
                    if (speakerAudioRecord != null) spkBytes = speakerAudioRecord?.read(spkBuf, 0, spkBuf.size) ?: -1
                    if (micBytes < 0 && spkBytes < 0) break
                    val len = mixAudio(micBuf, maxOf(0, micBytes), spkBuf, maxOf(0, spkBytes), mixedBuf, config)
                    if (len > 0) raw.write(mixedBuf, 0, len)
                }
                writeWavFile(wavFile, raw.toByteArray(), SAMPLE_RATE, NUM_CHANNELS_IN, BITS_PER_SAMPLE)
                try { raw.close() } catch (_: Exception) {}
                return@withContext
            }

            mediaCodec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()
            encoderStarted = true

            while (isActive && isRecording) {
                val micBytes = audioRecord?.read(micBuf, 0, micBuf.size) ?: -1
                var spkBytes = -1
                if (speakerAudioRecord != null) spkBytes = speakerAudioRecord?.read(spkBuf, 0, spkBuf.size) ?: -1
                if (micBytes < 0 && spkBytes < 0) break

                val len = mixAudio(micBuf, maxOf(0, micBytes), spkBuf, maxOf(0, spkBytes), mixedBuf, config)
                if (len <= 0) continue

                try {
                    val idx = mediaCodec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf = mediaCodec.getInputBuffer(idx)
                        buf?.clear()
                        buf?.put(mixedBuf, 0, len)
                        mediaCodec.queueInputBuffer(idx, 0, len, System.nanoTime() / 1000, 0)
                    }
                    val info = MediaCodec.BufferInfo()
                    var outIdx = mediaCodec.dequeueOutputBuffer(info, 10000)
                    while (outIdx >= 0) {
                        val outBuf = mediaCodec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk, 0, info.size)
                            encodedData.write(chunk)
                        }
                        mediaCodec.releaseOutputBuffer(outIdx, false)
                        outIdx = mediaCodec.dequeueOutputBuffer(info, 0)
                    }
                } catch (_: Exception) { break }
            }
        } catch (e: Exception) {
            reportError(ErrorCode.AE009, "Codec error", e)
        } finally {
            if (encoderStarted) try { mediaCodec?.stop() } catch (_: Exception) {}
            try { mediaCodec?.release() } catch (_: Exception) {}
            try { encodedData.close() } catch (_: Exception) {}
        }

        val data = encodedData.toByteArray()
        if (data.isNotEmpty()) {
            FileOutputStream(outputFile).use { it.write(data); it.flush() }
        } else {
            outputFile.createNewFile()
        }
    }

    private fun mixAudio(
        micBuf: ByteArray, micLen: Int,
        spkBuf: ByteArray, spkLen: Int,
        mixedBuf: ByteArray, config: RecordingConfig
    ): Int {
        val samples = (maxOf(micLen, spkLen) / 2) * 2
        if (samples <= 0) return 0

        val micVol = config.micVolume.coerceIn(0f, 2f)
        val spkVol = config.speakerVolume.coerceIn(0f, 2f)

        for (i in 0 until samples step 2) {
            var micSample = 0
            if (i + 1 < micLen) {
                micSample = ((micBuf[i + 1].toInt() and 0xFF shl 8) or (micBuf[i].toInt() and 0xFF))
                if (micSample > 32767) micSample -= 65536
            }
            micSample = (micSample * micVol).toInt()

            var spkSample = 0
            if (i + 1 < spkLen) {
                spkSample = ((spkBuf[i + 1].toInt() and 0xFF shl 8) or (spkBuf[i].toInt() and 0xFF))
                if (spkSample > 32767) spkSample -= 65536
            }
            spkSample = (spkSample * spkVol).toInt()

            var mixed = micSample + spkSample
            mixed = mixed.coerceIn(-32768, 32767)
            mixedBuf[i] = (mixed and 0xFF).toByte()
            if (i + 1 < mixedBuf.size) mixedBuf[i + 1] = ((mixed shr 8) and 0xFF).toByte()
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

        // Wait for write to complete (max 10 seconds)
        try {
            writeCompleteLatch?.await(10, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        // Release audio resources
        try { speakerAudioRecord?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop(); it.release() } }
        catch (_: Exception) {}
        speakerAudioRecord = null

        try { audioRecord?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop(); it.release() } }
        catch (_: Exception) {}
        audioRecord = null

        val output = currentOutputFile
        currentOutputFile = null
        recordingJob = null

        if (output == null || !output.exists()) {
            Log.e(TAG, "No output file at: ${output?.absolutePath}")
            return null
        }

        // Copy to public DCIM so user can see it in file manager
        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CallRecorder")
            if (!publicDir.exists()) publicDir.mkdirs()
            val publicFile = File(publicDir, output.name)
            output.copyTo(publicFile, overwrite = true)
            logDebug("Copied to public: ${publicFile.absolutePath} (${publicFile.length()} bytes)")
        } catch (e: Exception) {
            logDebug("Could not copy to public DCIM: ${e.message}")
        }

        logDebug("Recording saved: ${output.absolutePath} (${output.length()} bytes)")
        return output
    }

    fun isRecording(): Boolean = isRecording

    fun getRecordingDurationMs(): Long =
        if (isRecording) System.currentTimeMillis() - recordingStartTime else 0L

    fun getCurrentOutputFile(): File? = currentOutputFile

    fun getCurrentFormat(): OutputFormat = currentConfig.outputFormat

    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, numChannels: Int, bitsPerSample: Int) {
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
        return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }
}
