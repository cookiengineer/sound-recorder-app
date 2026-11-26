package engineer.cookie.soundrecorder.data

/**
 * Recording presets. AAC-LC is used for all of them.
 *
 * The defaults are aimed at speech transcription: 16 kHz mono is enough for
 * Whisper-class models and keeps a two-hour conversation around 43 MB.
 */
enum class QualityPreset(
    val sampleRate: Int,
    val channels: Int,
    val bitrateKbps: Int,
) {
    SPEECH(sampleRate = 16_000, channels = 1, bitrateKbps = 48),
    BALANCED(sampleRate = 22_050, channels = 1, bitrateKbps = 64),
    HIGH(sampleRate = 44_100, channels = 2, bitrateKbps = 128),
    ;

    val bitrate: Int get() = bitrateKbps * 1_000

    val bytesPerSecond: Long get() = bitrate / 8L

    val bytesPerHour: Long get() = bytesPerSecond * 3_600L

    val bytesPerMinute: Long get() = bytesPerSecond * 60L
}
