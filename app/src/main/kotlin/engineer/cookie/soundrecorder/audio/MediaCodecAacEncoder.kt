package engineer.cookie.soundrecorder.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import engineer.cookie.soundrecorder.data.RecordingConfig
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.min

class AacEncoderException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * AAC-LC encoder built on Android's MediaCodec.
 *
 * MediaCodec emits raw AAC access units. We wrap every unit in an ADTS header
 * and append it to the sink, so no MP4 finalisation is needed.
 */
class MediaCodecAacEncoder(
    private val config: RecordingConfig,
    private val sink: AacFrameSink,
) : AudioEncoder {

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var started = false
    private var closed = false
    private var eos = false
    private var totalFrames = 0L

    @Volatile
    override var error: Throwable? = null
        private set

    override fun start() {
        if (started) return
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            config.sampleRate,
            config.channels,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (throwable: Throwable) {
            throw AacEncoderException("No AAC encoder available", throwable)
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (throwable: Throwable) {
            encoder.release()
            throw AacEncoderException(
                "AAC encoder rejected ${config.sampleRate} Hz / ${config.channels} ch / ${config.bitrate} bps",
                throwable,
            )
        }

        codec = encoder
        started = true
    }

    override fun encode(chunk: PcmChunk) {
        val encoder = codec ?: throw AacEncoderException("AAC encoder is not started")
        if (chunk.frames <= 0) return

        try {
            var offsetFrames = 0
            var inputDeadline = System.nanoTime() + INPUT_STALL_TIMEOUT_NANOS
            while (offsetFrames < chunk.frames && !closed) {
                drainOutput(blocking = false)

                val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex < 0) {
                    drainOutput(blocking = true)
                    if (System.nanoTime() > inputDeadline) {
                        throw AacEncoderException("AAC encoder stopped accepting input")
                    }
                    continue
                }

                val input = encoder.getInputBuffer(inputIndex)
                    ?: throw AacEncoderException("AAC encoder returned no input buffer")

                input.clear()
                val maxFrames = input.remaining() / (2 * config.channels)
                if (maxFrames <= 0) {
                    throw AacEncoderException("AAC encoder input buffer is too small")
                }

                val frames = min(chunk.frames - offsetFrames, maxFrames)
                input.order(ByteOrder.LITTLE_ENDIAN)
                input.asShortBuffer().put(
                    chunk.samples,
                    offsetFrames * config.channels,
                    frames * config.channels,
                )

                val size = frames * config.channels * 2
                input.position(0)
                input.limit(size)
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    size,
                    presentationTimeUs(),
                    0,
                )

                totalFrames += frames
                offsetFrames += frames
                inputDeadline = System.nanoTime() + INPUT_STALL_TIMEOUT_NANOS
                drainOutput(blocking = false)
            }
        } catch (throwable: Throwable) {
            error = throwable
            throw throwable
        }
    }

    override fun finish() {
        val encoder = codec
        if (encoder == null || !started || closed) return

        try {
            var attempts = 0
            while (!eos && attempts < EOS_ATTEMPTS) {
                val inputIndex = encoder.dequeueInputBuffer(EOS_INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        presentationTimeUs(),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    break
                }
                drainOutput(blocking = true)
                attempts += 1
            }

            val deadline = System.nanoTime() + EOS_DRAIN_TIMEOUT_NANOS
            while (!eos && System.nanoTime() < deadline) {
                drainOutput(blocking = true)
            }

            sink.flush(sync = true)
        } catch (throwable: Throwable) {
            error = throwable
            throw throwable
        } finally {
            release()
        }
    }

    override fun close() {
        release()
    }

    private fun drainOutput(blocking: Boolean) {
        val encoder = codec ?: return
        while (true) {
            val index = encoder.dequeueOutputBuffer(
                bufferInfo,
                if (blocking) OUTPUT_TIMEOUT_US else 0L,
            )

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = encoder.outputFormat
                    val rate = outputFormat.getInteger(
                        MediaFormat.KEY_SAMPLE_RATE,
                        config.sampleRate,
                    )
                    val channels = outputFormat.getInteger(
                        MediaFormat.KEY_CHANNEL_COUNT,
                        config.channels,
                    )
                    if (rate != config.sampleRate || channels != config.channels) {
                        throw AacEncoderException(
                            "AAC encoder changed format to $rate Hz / $channels ch",
                        )
                    }
                }

                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                index >= 0 -> {
                    val isCodecConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                    if (bufferInfo.size > 0 && !isCodecConfig) {
                        val output = encoder.getOutputBuffer(index)
                            ?: throw AacEncoderException("AAC encoder returned no output buffer")
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        sink.writeFrame(output, bufferInfo.size)
                    }

                    encoder.releaseOutputBuffer(index, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eos = true
                        return
                    }
                }
            }
        }
    }

    private fun presentationTimeUs(): Long =
        totalFrames * 1_000_000L / config.sampleRate

    @Synchronized
    private fun release() {
        if (closed) return
        closed = true
        val encoder = codec
        codec = null
        if (encoder != null) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val INPUT_STALL_TIMEOUT_NANOS = 5_000_000_000L
        const val EOS_INPUT_TIMEOUT_US = 50_000L
        const val EOS_ATTEMPTS = 50
        const val EOS_DRAIN_TIMEOUT_NANOS = 3_000_000_000L
    }
}
