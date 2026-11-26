package engineer.cookie.soundrecorder.audio

/** Live statistics exposed by [RecorderEngine]. */
data class RecordingStats(
    val elapsedMs: Long = 0L,
    val bytesWritten: Long = 0L,
    val queueDepth: Int = 0,
    val level: Float = 0f,
    val overruns: Long = 0L,
)
