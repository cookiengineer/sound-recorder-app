package engineer.cookie.soundrecorder.service

import android.content.Context
import android.media.AudioManager
import android.os.Process
import engineer.cookie.soundrecorder.audio.AdtsFrameWriter
import engineer.cookie.soundrecorder.audio.AudioRecordSource
import engineer.cookie.soundrecorder.audio.MediaCodecAacEncoder
import engineer.cookie.soundrecorder.audio.RecorderEngine
import engineer.cookie.soundrecorder.audio.RecordingStats
import engineer.cookie.soundrecorder.data.Recording
import engineer.cookie.soundrecorder.data.RecordingConfig
import engineer.cookie.soundrecorder.data.RecordingMetadata
import engineer.cookie.soundrecorder.data.RecordingName
import engineer.cookie.soundrecorder.data.RecordingState
import engineer.cookie.soundrecorder.data.RecordingStore
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One recording: file naming, sidecar, engine lifecycle and finalisation.
 */
class RecordingSession(
    private val context: Context,
    private val config: RecordingConfig,
    private val store: RecordingStore,
    private val appVersion: String,
    private val onError: (Throwable) -> Unit,
    private val onStats: (RecordingStats) -> Unit = {},
) {
    lateinit var baseName: String
        private set

    lateinit var partFile: File
        private set

    lateinit var finalFile: File
        private set

    lateinit var metadataFile: File
        private set

    private var writer: AdtsFrameWriter? = null
    private var engine: RecorderEngine? = null
    private var metadata: RecordingMetadata? = null
    private var startNanos = 0L
    private val stopping = AtomicBoolean(false)

    private val metadataExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SoundRecorder-metadata").apply { isDaemon = true }
    }
    private var metadataFuture: ScheduledFuture<*>? = null

    @Volatile
    private var latestStats = RecordingStats()

    @Volatile
    private var engineFailure: Throwable? = null

    @Volatile
    private var closed = false

    val startedAtEpochMs: Long
        get() = metadata?.startedAtEpochMs ?: 0L

    fun prepare() {
        check(!::baseName.isInitialized) { "RecordingSession.prepare() called twice" }

        val now = LocalDateTime.now()
        baseName = RecordingName.uniqueBaseName(store.recordingsDir, now)
        partFile = File(store.recordingsDir, RecordingName.partFileName(baseName))
        finalFile = File(store.recordingsDir, RecordingName.finalFileName(baseName))
        metadataFile = File(store.recordingsDir, RecordingName.metadataFileName(baseName))

        metadata = RecordingMetadata(
            state = RecordingState.RECORDING,
            startedAtEpochMs = System.currentTimeMillis(),
            sampleRate = config.sampleRate,
            channels = config.channels,
            bitrate = config.bitrate,
            audioSource = config.audioSource.name,
            appVersion = appVersion,
        )
        store.writeMetadata(metadataFile, metadata!!)
    }

    fun start() {
        check(::partFile.isInitialized) { "RecordingSession.prepare() must be called first" }

        startNanos = System.nanoTime()

        val frameWriter = AdtsFrameWriter(partFile, config.sampleRate, config.channels)
        writer = frameWriter

        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("AudioManager is unavailable")

        val recorder = RecorderEngine(
            config = config,
            sourceFactory = { AudioRecordSource(config, audioManager) },
            encoderFactory = { sink -> MediaCodecAacEncoder(config, sink) },
            sink = frameWriter,
            onStats = { stats ->
                latestStats = stats
                onStats(stats)
            },
            onError = { error ->
                engineFailure = error
                onError(error)
            },
            onCaptureThreadStart = {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            },
        )
        engine = recorder

        try {
            recorder.start()
            metadataFuture = metadataExecutor.scheduleWithFixedDelay(
                { writeMetadataSnapshot() },
                METADATA_INTERVAL_SECONDS,
                METADATA_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        } catch (throwable: Throwable) {
            engine = null
            runCatching { recorder.stop() }
            runCatching { frameWriter.close() }
            cleanupFailedStart()
            throw throwable
        }
    }

    /**
     * Stops the engine, drains encoded frames, and finalises the file.
     *
     * Returns the finished recording on a clean stop, or null when the file
     * was left as `.part` because of an error or because no frames were written.
     */
    fun stop(reason: String? = null, isError: Boolean = false): Recording? {
        if (!stopping.compareAndSet(false, true)) return null
        closed = true
        metadataFuture?.cancel(false)
        metadataFuture = null
        metadataExecutor.shutdownNow()
        runCatching { metadataExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        val currentEngine = engine
        engine = null
        runCatching { currentEngine?.stop() }

        val currentWriter = writer
        writer = null
        runCatching { currentWriter?.flush(sync = true) }
        runCatching { currentWriter?.close() }

        val meta = metadata ?: return null
        val durationMs = latestStats.elapsedMs.takeIf { it > 0L }
            ?: ((System.nanoTime() - startNanos) / 1_000_000L)
        val size = partFile.length()
        val failureMessage = reason ?: engineFailure?.message
        val failed = isError || engineFailure != null
        val state = if (failed) RecordingState.ERROR else RecordingState.COMPLETED

        val completed = meta.copy(
            state = state,
            endedAtEpochMs = System.currentTimeMillis(),
            durationMs = durationMs,
            bytes = size,
            overruns = latestStats.overruns,
            error = failureMessage,
        )

        if (size <= 0L) {
            partFile.delete()
            metadataFile.delete()
            metadata = null
            return null
        }

        if (failed) {
            // Keep the .part file: every complete ADTS frame is still playable
            // and the UI offers recovery.
            metadata = completed
            store.writeMetadata(metadataFile, completed)
            return null
        }

        val renamed = partFile.renameTo(finalFile) || runCatching {
            partFile.copyTo(finalFile, overwrite = false)
            partFile.delete()
            true
        }.getOrDefault(false)

        if (!renamed) {
            val renameFailure = completed.copy(
                state = RecordingState.ERROR,
                error = "Could not finalise recording file",
            )
            metadata = renameFailure
            store.writeMetadata(metadataFile, renameFailure)
            return null
        }

        metadata = completed
        store.writeMetadata(metadataFile, completed)
        return Recording(
            file = finalFile,
            metadata = completed,
            durationMs = durationMs,
            sizeBytes = size,
        )
    }

    fun abort() {
        if (!stopping.compareAndSet(false, true)) return
        closed = true
        metadataFuture?.cancel(false)
        metadataFuture = null
        metadataExecutor.shutdownNow()
        runCatching { metadataExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { engine?.stop() }
        runCatching { writer?.close() }
        engine = null
        writer = null
        if (::partFile.isInitialized) partFile.delete()
        if (::metadataFile.isInitialized) metadataFile.delete()
        metadata = null
    }

    private fun writeMetadataSnapshot() {
        synchronized(this) {
            if (closed) return
            val current = metadata ?: return
            val stats = latestStats
            val updated = current.copy(
                durationMs = stats.elapsedMs,
                bytes = partFile.length(),
                overruns = stats.overruns,
            )
            metadata = updated
            runCatching { store.writeMetadata(metadataFile, updated) }
        }
    }

    private fun cleanupFailedStart() {
        if (::partFile.isInitialized) partFile.delete()
        if (::metadataFile.isInitialized) metadataFile.delete()
        metadata = null
    }

    private companion object {
        const val METADATA_INTERVAL_SECONDS = 5L
    }
}
