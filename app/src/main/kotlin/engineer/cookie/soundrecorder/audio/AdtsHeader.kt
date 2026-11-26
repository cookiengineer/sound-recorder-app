package engineer.cookie.soundrecorder.audio

/**
 * Builds the 7-byte ADTS header used by `.aac` files.
 *
 * ADTS frames are self-contained (header + AAC payload), which is what makes
 * incremental writes and crash recovery possible: every complete frame is
 * independently decodable.
 */
object AdtsHeader {
    const val SIZE = 7
    const val PROFILE_AAC_LC = 1 // Audio Object Type 2 minus 1

    private val SAMPLE_RATE_INDEX = mapOf(
        96_000 to 0,
        88_200 to 1,
        64_000 to 2,
        48_000 to 3,
        44_100 to 4,
        32_000 to 5,
        24_000 to 6,
        22_050 to 7,
        16_000 to 8,
        12_000 to 9,
        11_025 to 10,
        8_000 to 11,
        7_350 to 12,
    )

    fun sampleRateIndex(sampleRate: Int): Int =
        SAMPLE_RATE_INDEX[sampleRate]
            ?: throw IllegalArgumentException("Unsupported AAC sample rate: $sampleRate")

    fun isSupportedSampleRate(sampleRate: Int): Boolean =
        SAMPLE_RATE_INDEX.containsKey(sampleRate)

    /**
     * Writes an ADTS header into [header] and returns the total frame length
     * (header + payload).
     */
    fun write(
        header: ByteArray,
        sampleRate: Int,
        channels: Int,
        payloadSize: Int,
        profile: Int = PROFILE_AAC_LC,
    ): Int {
        require(header.size >= SIZE) { "ADTS header buffer too small" }
        require(payloadSize >= 0) { "Negative AAC payload size" }
        require(channels in 1..2) { "Unsupported channel count: $channels" }

        val frameLength = payloadSize + SIZE
        require(frameLength <= 0x1FFF) { "AAC frame too large for ADTS: $frameLength" }

        val frequencyIndex = sampleRateIndex(sampleRate)
        val channelConfig = channels

        // Fixed header: syncword 0xFFF, MPEG-4, layer 00, no CRC.
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()

        // profile (2) | sampling_frequency_index (4) | private (1) | channel high bit
        header[2] = (
            ((profile and 0x03) shl 6) or
                ((frequencyIndex and 0x0F) shl 2) or
                ((channelConfig shr 2) and 0x01)
            ).toByte()

        // channel low bits (2) | original/copy | home | copyright bits | frame length high 2
        header[3] = (
            (((channelConfig and 0x03) shl 6)) or
                ((frameLength shr 11) and 0x03)
            ).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        // buffer fullness = 0x7FF, number_of_raw_data_blocks = 0
        header[6] = 0xFC.toByte()

        return frameLength
    }
}
