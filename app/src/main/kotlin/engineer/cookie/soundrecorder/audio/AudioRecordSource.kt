package engineer.cookie.soundrecorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import engineer.cookie.soundrecorder.data.AudioSourceOption
import engineer.cookie.soundrecorder.data.RecordingConfig
import kotlin.math.max

class AudioRecordException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Wraps AudioRecord with source fallbacks and one-time recovery from a dead
 * audio server (which otherwise silently ends the recording).
 */
class AudioRecordSource(
    private val config: RecordingConfig,
    private val audioManager: AudioManager,
) : PcmSource {

    private val channelMask: Int =
        if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    private var record: AudioRecord? = null
    private var deadObjectRetries = 0
    private var closed = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        open()
        startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val candidates = candidateSources()
        var lastError: Throwable? = null

        for (source in candidates) {
            var candidate: AudioRecord? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    config.sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) {
                    throw AudioRecordException("Unsupported AudioRecord format (min buffer $minBuffer)")
                }

                // Keep several seconds of microphone data in the hardware buffer
                // so scheduler hiccups do not immediately drop audio.
                val bufferSize = max(
                    minBuffer * 4,
                    config.sampleRate * config.channels * 2 * 2,
                )

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(channelMask)
                    .build()

                candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    throw AudioRecordException("AudioRecord could not initialise")
                }

                record = candidate
                return
            } catch (error: Throwable) {
                lastError = error
                candidate?.release()
            }
        }

        throw AudioRecordException(
            "No usable microphone source (tried ${candidates.joinToString()})",
            lastError,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val current = record ?: throw AudioRecordException("AudioRecord is not open")
        current.startRecording()
        if (current.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AudioRecordException("Microphone did not enter recording state")
        }
    }

    override fun read(buffer: ShortArray): Int {
        val current = record ?: return PcmSource.END_OF_STREAM
        val read = current.read(buffer, 0, buffer.size)

        if (read >= 0) {
            deadObjectRetries = 0
            return read
        }

        if (read == AudioRecord.ERROR_DEAD_OBJECT && deadObjectRetries < MAX_REOPEN_ATTEMPTS) {
            deadObjectRetries += 1
            reopen()
            return read(buffer)
        }

        if (read == AudioRecord.ERROR_DEAD_OBJECT) {
            throw AudioRecordException("Audio server died repeatedly")
        }

        throw AudioRecordException("AudioRecord read failed with code $read")
    }

    @SuppressLint("MissingPermission")
    private fun reopen() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
        record?.release()
        record = null
        open()
        startRecording()
    }

    override fun stop() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
        record?.release()
        record = null
    }

    private fun candidateSources(): List<Int> {
        val unprocessedSupported =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.toBoolean() == true

        return when (config.audioSource) {
            AudioSourceOption.AUTO -> buildList {
                if (unprocessedSupported) add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.MIC)
            }

            AudioSourceOption.UNPROCESSED -> buildList {
                add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.MIC)
            }

            AudioSourceOption.VOICE_RECOGNITION -> listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )

            AudioSourceOption.MIC -> listOf(MediaRecorder.AudioSource.MIC)
        }
    }

    private companion object {
        const val MAX_REOPEN_ATTEMPTS = 3
    }
}
