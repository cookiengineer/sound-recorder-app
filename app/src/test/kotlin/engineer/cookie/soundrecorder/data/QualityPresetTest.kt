package engineer.cookie.soundrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityPresetTest {
    @Test
    fun estimatesFileSizes() {
        assertEquals(6_000L, QualityPreset.SPEECH.bytesPerSecond)
        assertEquals(21_600_000L, QualityPreset.SPEECH.bytesPerHour)
        assertEquals(28_800_000L, QualityPreset.BALANCED.bytesPerHour)
        assertEquals(57_600_000L, QualityPreset.HIGH.bytesPerHour)
    }
}
