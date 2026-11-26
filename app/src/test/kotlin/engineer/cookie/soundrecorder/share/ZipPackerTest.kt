package engineer.cookie.soundrecorder.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ZipPackerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun storesTheAudioWithoutRecompression() {
        val source = File(temporaryFolder.root, "2026-11-25 00:01:02.aac")
        val payload = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        source.writeBytes(payload)

        val destination = File(temporaryFolder.root, "2026-11-25 00:01:02.zip")
        ZipPacker.createZip(source, destination)

        ZipFile(destination).use { zip ->
            val entry = zip.getEntry(source.name)
            assertEquals(java.util.zip.ZipEntry.STORED, entry.method)
            assertEquals(payload.size.toLong(), entry.size)
            assertEquals(payload.size.toLong(), entry.compressedSize)
            assertArrayEquals(payload, zip.getInputStream(entry).readBytes())
        }
    }

    @Test
    fun buildsZipNameFromRecordingName() {
        assertEquals(
            "2026-11-25 00:01:02.zip",
            ZipPacker.zipFileNameFor(File("2026-11-25 00:01:02.aac")),
        )
        assertEquals(
            "2026-11-25 00:01:02.zip",
            ZipPacker.zipFileNameFor(File("2026-11-25 00:01:02.aac.part")),
        )
        assertTrue(
            ZipPacker.zipFileNameFor(File("2026-11-25 00:01:02.aac")).endsWith(".zip"),
        )
    }
}
