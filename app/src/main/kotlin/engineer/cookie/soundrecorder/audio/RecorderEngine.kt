package engineer.cookie.soundrecorder.audio

import engineer.cookie.soundrecorder.data.RecordingConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streaming recorder pipeline.
 *
 * A high-priority capture thread reads PCM and puts it into a bounded queue.
 * A second thread encodes AAC and appends frames to the sink. The queue
 * decouples microphone timing from encoder/storage hiccups; it is sized by
 * both time (about two minutes) and memory (about 8 MB).
 *
 * The class is deliberately free of Android imports so the pipeline can be
 * unit-tested with fake sources and encoders.
 */
class RecorderEngine(
    private val config: RecordingConfig,
    private val sourceFactory: () -> PcmSource,
    private val encoderFactory: (AacFrameSink) -> AudioEncoder,
    private val sink: AacFrameSink,
    private val onStats: (RecordingStats) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val onCaptureThreadStart: () -> Unit = {},
    queueSeconds: Int = DEFAULT_QUEUE_SECONDS,
) {
    private val chunkFrames: Int = max(256, config.sampleRate / 16)
    private val bytesPerChunk: Int = chunkFrames * config.channels * 2
    private val queue: ArrayBlockingQueue<PcmChunk> = ArrayBlockingQueue(queueCapacity(queueSeconds))

    private val finished = CountDownLatch(2)
    private val overruns = AtomicLong(0)
    private val firstError = AtomicReference<Throwable?>(null)

    @Volatile
    private var running = false

    @Volatile
    private var lastLevel = 0f

    @Volatile
    private var startNanos = System.nanoTime()

    private var lastStatsNanos = 0L
    private var source: PcmSource? = null
    private var encoder: AudioEncoder? = null
    private var captureThread: Thread? = null
    private var encoderThread: Thread? = null

    val error: Throwable? get() = firstError.get()

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        check(captureThread == null) { "RecorderEngine.start() called twice" }

        running = true
        startNanos = System.nanoTime()
        lastStatsNanos = 0L

        val newSource = sourceFactory()
        val newEncoder = try {
            encoderFactory(sink)
        } catch (throwable: Throwable) {
            runCatching { newSource.close() }
            throw throwable
        }

        try {
            newSource.start()
            newEncoder.start()
        } catch (throwable: Throwable) {
            runCatching { newEncoder.close() }
            runCatching { newSource.close() }
            throw throwable
        }

        source = newSource
        encoder = newEncoder

        captureThread = Thread({ captureLoop(newSource) }, "SoundRecorder-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        encoderThread = Thread({ encoderLoop(newEncoder) }, "SoundRecorder-encoder").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /**
     * Stops capture, drains everything already queued, flushes the encoder and
     * closes the sink. Safe to call more than once.
     */
    fun stop() {
        running = false
        runCatching { source?.stop() }

        captureThread?.let { joinQuietly(it, CAPTURE_JOIN_MS) }
        encoderThread?.let { joinQuietly(it, ENCODER_JOIN_MS) }

        if (captureThread?.isAlive == true) captureThread?.interrupt()
        if (encoderThread?.isAlive == true) {
            encoderThread?.interrupt()
            runCatching { encoder?.close() }
            runCatching { sink.close() }
        }

        awaitFinished(1_000)
        publishStats(force = true)
    }

    /** Test/helper hook: wait until both pipeline threads have finished. */
    fun awaitFinished(timeoutMs: Long): Boolean =
        finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun captureLoop(pcmSource: PcmSource) {
        try {
            runCatching { onCaptureThreadStart() }
            val buffer = ShortArray(chunkFrames * config.channels)
            while (running) {
                val read = pcmSource.read(buffer)
                if (read == PcmSource.END_OF_STREAM) {
                    running = false
                    break
                }
                if (read <= 0) continue

                val frames = read / config.channels
                if (frames <= 0) continue

                val copy = buffer.copyOf(read)
                val level = calculateLevel(copy)
                val chunk = PcmChunk(copy, frames, level)

                if (!queue.offer(chunk, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    overruns.incrementAndGet()
                    // Backpressure: wait for the encoder instead of silently
                    // dropping audio. If the encoder is truly stuck, the
                    // service's stop path still terminates cleanly.
                    while (running && !queue.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
                        // Keep waiting; stats show the growing overrun count.
                    }
                }

                lastLevel = level
                publishStats()
            }
        } catch (throwable: Throwable) {
            fail(throwable)
        } finally {
            finished.countDown()
            publishStats(force = true)
        }
    }

    private fun encoderLoop(aacEncoder: AudioEncoder) {
        try {
            while (running || queue.isNotEmpty()) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    aacEncoder.encode(chunk)
                }
                publishStats()
            }
            aacEncoder.finish()
        } catch (throwable: Throwable) {
            fail(throwable)
        } finally {
            runCatching { aacEncoder.close() }
            runCatching { sink.close() }
            finished.countDown()
            publishStats(force = true)
        }
    }

    private fun fail(throwable: Throwable) {
        if (firstError.compareAndSet(null, throwable)) {
            onError(throwable)
        }
        running = false
        runCatching { source?.stop() }
    }

    private fun publishStats(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastStatsNanos < STATS_INTERVAL_NANOS) return
        lastStatsNanos = now

        onStats(
            RecordingStats(
                elapsedMs = (now - startNanos) / 1_000_000L,
                bytesWritten = sink.bytesWritten,
                queueDepth = queue.size,
                level = lastLevel,
                overruns = overruns.get(),
            ),
        )
    }

    private fun queueCapacity(queueSeconds: Int): Int {
        val durationChunks = (queueSeconds * 1_000L / CHUNK_DURATION_MS).toInt()
        val memoryChunks = MAX_QUEUE_BYTES / bytesPerChunk
        return max(8, min(durationChunks, memoryChunks))
    }

    private fun calculateLevel(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            val value = sample / 32_768.0
            sum += value * value
        }
        return sqrt(sum / samples.size).toFloat().coerceIn(0f, 1f)
    }

    private fun joinQuietly(thread: Thread, timeoutMs: Long) {
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val DEFAULT_QUEUE_SECONDS = 120
        const val MAX_QUEUE_BYTES = 8 * 1024 * 1024
        const val CHUNK_DURATION_MS = 1_000.0 / 16.0
        const val QUEUE_OFFER_TIMEOUT_MS = 500L
        const val CAPTURE_JOIN_MS = 2_000L
        const val ENCODER_JOIN_MS = 15_000L
        const val STATS_INTERVAL_NANOS = 200_000_000L
    }
}
