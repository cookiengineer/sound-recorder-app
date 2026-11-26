package engineer.cookie.soundrecorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdtsHeaderTest {
    @Test
    fun writesMono16kHeader() {
        val header = ByteArray(AdtsHeader.SIZE)
        val frameLength = AdtsHeader.write(header, 16_000, 1, 100)

        assertEquals(107, frameLength)
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)

        val profile = (header[2].toInt() shr 6) and 0x03
        val frequencyIndex = (header[2].toInt() shr 2) and 0x0F
        val channelConfig = ((header[2].toInt() and 0x01) shl 2) or
            ((header[3].toInt() shr 6) and 0x03)

        assertEquals(AdtsHeader.PROFILE_AAC_LC, profile)
        assertEquals(8, frequencyIndex)
        assertEquals(1, channelConfig)
    }

    @Test
    fun writesStereo44kHeader() {
        val header = ByteArray(AdtsHeader.SIZE)
        val frameLength = AdtsHeader.write(header, 44_100, 2, 500)

        assertEquals(507, frameLength)

        val frequencyIndex = (header[2].toInt() shr 2) and 0x0F
        val channelConfig = ((header[2].toInt() and 0x01) shl 2) or
            ((header[3].toInt() shr 6) and 0x03)

        assertEquals(4, frequencyIndex)
        assertEquals(2, channelConfig)

        val encodedLength = ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() shr 5) and 0x07)
        assertEquals(507, encodedLength)
    }

    @Test
    fun rejectsUnsupportedSampleRate() {
        assertThrows(IllegalArgumentException::class.java) {
            AdtsHeader.write(ByteArray(AdtsHeader.SIZE), 12_345, 1, 10)
        }
    }

    @Test
    fun rejectsOversizedFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            AdtsHeader.write(ByteArray(AdtsHeader.SIZE), 16_000, 1, 0x1FFF)
        }
    }
}
