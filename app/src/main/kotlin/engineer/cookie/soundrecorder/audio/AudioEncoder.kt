package engineer.cookie.soundrecorder.audio

import java.io.Closeable

/** Encodes PCM chunks into AAC and writes them through an [AacFrameSink]. */
interface AudioEncoder : Closeable {
    fun start()

    fun encode(chunk: PcmChunk)

    /** Drains the encoder (including its EOS tail) and flushes the sink. */
    fun finish()

    /** Last error observed by the encoder, if any. */
    val error: Throwable?
}
