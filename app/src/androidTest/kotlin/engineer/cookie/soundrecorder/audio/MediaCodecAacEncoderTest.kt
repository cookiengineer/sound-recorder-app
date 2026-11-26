package engineer.cookie.soundrecorder.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import engineer.cookie.soundrecorder.data.AudioSourceOption
import engineer.cookie.soundrecorder.data.QualityPreset
import engineer.cookie.soundrecorder.data.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Device-only smoke test for the real MediaCodec + ADTS path.
 *
 * Run with: make shell   (then `gradle connectedDebugAndroidTest`)
 * It is not part of the host JVM test suite because it needs a device.
 */
@RunWith(AndroidJUnit4::class)
class MediaCodecAacEncoderTest {
    @Test
    fun producesAnAdtsStreamThatMediaExtractorCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "encoder-test-${System.nanoTime()}.aac")
        val config = RecordingConfig.from(QualityPreset.SPEECH, AudioSourceOption.MIC)

        try {
            val writer = AdtsFrameWriter(file, config.sampleRate, config.channels)
            val encoder = MediaCodecAacEncoder(config, writer)
            encoder.start()

            val chunkFrames = 1_024
            val totalFrames = config.sampleRate
            var writtenFrames = 0
            while (writtenFrames < totalFrames) {
                val frames = minOf(chunkFrames, totalFrames - writtenFrames)
                val samples = ShortArray(frames)
                for (index in samples.indices) {
                    val t = (writtenFrames + index).toDouble() / config.sampleRate
                    samples[index] = (sin(2.0 * PI * 440.0 * t) * 12_000.0).toInt().toShort()
                }
                encoder.encode(PcmChunk(samples, frames, 0.1f))
                writtenFrames += frames
            }

            encoder.finish()
            writer.close()

            assertTrue("encoder produced no output", file.length() > 100)
            val bytes = file.inputStream().use { stream ->
                ByteArray(2).also { stream.read(it) }
            }
            assertEquals(0xFF, bytes[0].toInt() and 0xFF)
            assertEquals(0xF1, bytes[1].toInt() and 0xFF)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertTrue("MediaExtractor found no track", extractor.trackCount > 0)
                val mime = extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)
                assertTrue("unexpected track MIME: $mime", mime?.startsWith("audio/") == true)
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }
}
