package engineer.cookie.soundrecorder.audio

import engineer.cookie.soundrecorder.data.AudioSourceOption
import engineer.cookie.soundrecorder.data.QualityPreset
import engineer.cookie.soundrecorder.data.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong

class RecorderEngineTest {
    @Test
    fun streamsTwoHoursWithoutLosingFrames() {
        val config = RecordingConfig.from(QualityPreset.SPEECH, AudioSourceOption.MIC)
        val totalFrames = 2L * 3_600L * config.sampleRate.toLong()
        val source = FakePcmSource(totalFrames, config.channels)
        val sink = FakeSink()
        val encoder = FakeEncoder(sink)

        val engine = RecorderEngine(
            config = config,
            sourceFactory = { source },
            encoderFactory = { encoder },
            sink = sink,
            queueSeconds = 120,
        )

        engine.start()
        assertTrue("pipeline did not finish in time", engine.awaitFinished(30_000))
        engine.stop()

        assertNull(engine.error)
        assertEquals(totalFrames, encoder.frames.get())
        assertEquals(totalFrames * 2L, sink.bytesWritten)
    }

    private class FakePcmSource(
        private var remainingFrames: Long,
        private val channels: Int,
    ) : PcmSource {
        override fun start() = Unit

        override fun read(buffer: ShortArray): Int {
            if (remainingFrames <= 0L) return PcmSource.END_OF_STREAM
            val frames = minOf(remainingFrames, (buffer.size / channels).toLong()).toInt()
            val count = frames * channels
            Arrays.fill(buffer, 0, count, 0.toShort())
            remainingFrames -= frames
            return count
        }

        override fun stop() = Unit

        override fun close() = Unit
    }

    private class FakeSink : AacFrameSink {
        @Volatile
        private var written = 0L

        override val bytesWritten: Long get() = written

        fun add(bytes: Long) {
            written += bytes
        }

        override fun writeFrame(buffer: ByteBuffer, size: Int) = Unit

        override fun flush(sync: Boolean) = Unit

        override fun close() = Unit
    }

    private class FakeEncoder(
        private val sink: FakeSink,
    ) : AudioEncoder {
        val frames = AtomicLong(0)

        override fun start() = Unit

        override fun encode(chunk: PcmChunk) {
            frames.addAndGet(chunk.frames.toLong())
            sink.add(chunk.frames * 2L)
        }

        override fun finish() = Unit

        override fun close() = Unit

        override val error: Throwable? = null
    }
}
