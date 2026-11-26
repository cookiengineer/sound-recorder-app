package engineer.cookie.soundrecorder.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Appends ADTS frames to a file, flushing often and fsyncing periodically.
 *
 * A crash can lose at most the in-memory buffer (about one second) and leaves
 * a valid, truncated AAC stream behind.
 */
class AdtsFrameWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : AacFrameSink, Closeable {

    private val output = FileOutputStream(file, false)
    private val buffered = BufferedOutputStream(output, BUFFER_SIZE)
    private var closed = false

    @Volatile
    private var writtenBytes: Long = 0L

    private var lastFlushNanos: Long = System.nanoTime()
    private var lastSyncNanos: Long = System.nanoTime()

    override val bytesWritten: Long get() = writtenBytes

    @Synchronized
    override fun writeFrame(buffer: ByteBuffer, size: Int) {
        if (closed) throw IOException("AAC sink is closed")
        require(size >= 0)
        require(buffer.remaining() >= size) {
            "AAC output buffer has ${buffer.remaining()} bytes, need $size"
        }

        val header = ByteArray(AdtsHeader.SIZE)
        AdtsHeader.write(header, sampleRate, channels, size)
        buffered.write(header)

        val payload = ByteArray(size)
        buffer.get(payload, 0, size)
        buffered.write(payload)
        writtenBytes += AdtsHeader.SIZE + size

        maybeFlush()
    }

    @Synchronized
    override fun flush(sync: Boolean) {
        if (closed) return
        buffered.flush()
        if (sync) {
            output.fd.sync()
            lastSyncNanos = System.nanoTime()
        }
        lastFlushNanos = System.nanoTime()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            buffered.flush()
            output.fd.sync()
        } finally {
            buffered.close()
            output.close()
        }
    }

    private fun maybeFlush() {
        val now = System.nanoTime()
        if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
            buffered.flush()
            lastFlushNanos = now
        }
        if (now - lastSyncNanos >= SYNC_INTERVAL_NANOS) {
            output.fd.sync()
            lastSyncNanos = now
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val FLUSH_INTERVAL_NANOS = 1_000_000_000L
        const val SYNC_INTERVAL_NANOS = 5_000_000_000L
    }
}
