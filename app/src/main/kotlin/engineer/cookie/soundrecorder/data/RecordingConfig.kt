package engineer.cookie.soundrecorder.data

/** Immutable recording configuration passed from settings to the engine. */
data class RecordingConfig(
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val qualityPreset: QualityPreset,
    val audioSource: AudioSourceOption,
) {
    init {
        require(sampleRate > 0)
        require(channels == 1 || channels == 2)
        require(bitrate > 0)
    }

    val bytesPerSecond: Long get() = bitrate / 8L

    companion object {
        fun from(preset: QualityPreset, source: AudioSourceOption): RecordingConfig =
            RecordingConfig(
                sampleRate = preset.sampleRate,
                channels = preset.channels,
                bitrate = preset.bitrate,
                qualityPreset = preset,
                audioSource = source,
            )
    }
}
