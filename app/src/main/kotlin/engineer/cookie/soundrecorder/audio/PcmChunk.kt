package engineer.cookie.soundrecorder.audio

/** A chunk of signed 16-bit PCM, interleaved for stereo. */
class PcmChunk(
    val samples: ShortArray,
    /** Number of frames per channel, not the number of shorts. */
    val frames: Int,
    /** Normalised RMS level in the range 0..1. */
    val level: Float,
)
