package engineer.cookie.soundrecorder.audio

import java.io.Closeable

/**
 * Supplies PCM chunks to the recorder engine.
 *
 * [read] returns the number of shorts written to the buffer, 0 when no data is
 * currently available, or [END_OF_STREAM] when the source is exhausted.
 * Real microphone sources block until data is available.
 */
interface PcmSource : Closeable {
    fun start()

    fun read(buffer: ShortArray): Int

    fun stop()

    companion object {
        const val END_OF_STREAM = -1
    }
}
