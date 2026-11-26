package engineer.cookie.soundrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class RecordingNameTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun formatsTheRequestedLocalTimestamp() {
        val dateTime = LocalDateTime.of(2026, 11, 25, 0, 1, 2)
        assertEquals("2026-11-25 00:01:02", RecordingName.baseName(dateTime))
        assertEquals("2026-11-25 00:01:02.aac", RecordingName.fileName(dateTime))
        assertEquals("2026-11-25 00:01:02.aac.part", RecordingName.partFileName(dateTime))
    }

    @Test
    fun addsACollisionSuffix() {
        val directory = temporaryFolder.newFolder()
        val dateTime = LocalDateTime.of(2026, 11, 25, 0, 1, 2)

        assertEquals("2026-11-25 00:01:02", RecordingName.uniqueBaseName(directory, dateTime))

        File(directory, "2026-11-25 00:01:02.aac").writeText("x")
        assertEquals(
            "2026-11-25 00:01:02 (2)",
            RecordingName.uniqueBaseName(directory, dateTime),
        )

        File(directory, "2026-11-25 00:01:02 (2).aac").writeText("x")
        assertEquals(
            "2026-11-25 00:01:02 (3)",
            RecordingName.uniqueBaseName(directory, dateTime),
        )
    }

    @Test
    fun sanitizesNamesForPublicMediaStoreCopies() {
        assertTrue(
            RecordingName.safePublicName("2026-11-25 00:01:02.aac")
                .matches(Regex("2026-11-25_00-01-02\\.aac")),
        )
    }
}
