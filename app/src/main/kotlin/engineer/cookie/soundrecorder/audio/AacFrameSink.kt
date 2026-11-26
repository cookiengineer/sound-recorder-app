package engineer.cookie.soundrecorder.audio

import java.io.Closeable
import java.nio.ByteBuffer

/** Append-only sink for raw AAC access units wrapped in ADTS frames. */
interface AacFrameSink : Closeable {
    val bytesWritten: Long

    fun writeFrame(buffer: ByteBuffer, size: Int)

    fun flush(sync: Boolean)
}
