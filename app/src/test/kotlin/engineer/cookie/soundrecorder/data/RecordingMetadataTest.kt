package engineer.cookie.soundrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingMetadataTest {
    @Test
    fun roundTripsMetadata() {
        val metadata = RecordingMetadata(
            state = RecordingState.COMPLETED,
            startedAtEpochMs = 1_800_000_000_000L,
            endedAtEpochMs = 1_800_003_600_000L,
            sampleRate = 16_000,
            channels = 1,
            bitrate = 48_000,
            audioSource = AudioSourceOption.AUTO.name,
            durationMs = 3_600_000L,
            bytes = 21_600_000L,
            overruns = 2L,
            publicUri = "content://media/external/audio/media/1",
            publicName = "2026-11-25 00-01-02.aac",
            exportState = ExportState.DONE,
            appVersion = "0.1.0",
        )

        val parsed = RecordingMetadata.fromJson(metadata.toJson())

        assertEquals(metadata, parsed)
    }

    @Test
    fun toleratesUnknownOrMissingFields() {
        val json = """
            {
              "state": "SOMETHING_NEW",
              "startedAtEpochMs": 123,
              "appVersion": "0.1.0"
            }
        """.trimIndent()

        val parsed = RecordingMetadata.fromJson(json)

        assertEquals(RecordingState.RECORDING, parsed?.state)
        assertEquals(123L, parsed?.startedAtEpochMs)
        assertEquals(ExportState.NONE, parsed?.exportState)
    }

    @Test
    fun returnsNullForBrokenJson() {
        assertNull(RecordingMetadata.fromJson("{not json"))
    }
}
