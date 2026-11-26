package engineer.cookie.soundrecorder.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class AdtsFrameWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendsAdtsFrames() {
        val file = File(temporaryFolder.root, "test.aac")
        val writer = AdtsFrameWriter(file, 16_000, 1)

        writer.writeFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 3)
        writer.writeFrame(ByteBuffer.wrap(byteArrayOf(4, 5)), 2)
        writer.close()

        val bytes = file.readBytes()
        assertEquals(10 + 9, bytes.size)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xF1, bytes[1].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.copyOfRange(7, 10))
        assertEquals(0xFF, bytes[10].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(4, 5), bytes.copyOfRange(17, 19))
        assertTrue(writer.bytesWritten == 19L)
    }
}
