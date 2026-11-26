package engineer.cookie.soundrecorder.data

import org.json.JSONObject

enum class RecordingState {
    RECORDING,
    COMPLETED,
    RECOVERED,
    ERROR,
}

enum class ExportState {
    NONE,
    PENDING,
    DONE,
    FAILED,
}

/**
 * Metadata stored next to each recording as `<name>.aac.json`.
 *
 * The AAC file is the source of truth; this sidecar only carries information
 * that is not present in raw ADTS (duration, settings, export status).
 */
data class RecordingMetadata(
    val version: Int = 1,
    val state: RecordingState,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val audioSource: String,
    val durationMs: Long = 0L,
    val bytes: Long = 0L,
    val overruns: Long = 0L,
    val publicUri: String? = null,
    val publicName: String? = null,
    val exportState: ExportState = ExportState.NONE,
    val error: String? = null,
    val appVersion: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("state", state.name)
        put("startedAtEpochMs", startedAtEpochMs)
        if (endedAtEpochMs != null) put("endedAtEpochMs", endedAtEpochMs)
        put("sampleRate", sampleRate)
        put("channels", channels)
        put("bitrate", bitrate)
        put("audioSource", audioSource)
        put("durationMs", durationMs)
        put("bytes", bytes)
        put("overruns", overruns)
        if (publicUri != null) put("publicUri", publicUri)
        if (publicName != null) put("publicName", publicName)
        put("exportState", exportState.name)
        if (error != null) put("error", error)
        put("appVersion", appVersion)
    }.toString(2)

    companion object {
        fun fromJson(json: String): RecordingMetadata? = runCatching {
            val objectJson = JSONObject(json)
            RecordingMetadata(
                version = objectJson.optInt("version", 1),
                state = enumValueOrDefault(
                    objectJson.optString("state"),
                    RecordingState.RECORDING,
                ),
                startedAtEpochMs = objectJson.optLong("startedAtEpochMs", 0L),
                endedAtEpochMs = if (objectJson.has("endedAtEpochMs") &&
                    !objectJson.isNull("endedAtEpochMs")
                ) {
                    objectJson.optLong("endedAtEpochMs")
                } else {
                    null
                },
                sampleRate = objectJson.optInt("sampleRate", QualityPreset.SPEECH.sampleRate),
                channels = objectJson.optInt("channels", QualityPreset.SPEECH.channels),
                bitrate = objectJson.optInt("bitrate", QualityPreset.SPEECH.bitrate),
                audioSource = objectJson.optString(
                    "audioSource",
                    AudioSourceOption.AUTO.name,
                ),
                durationMs = objectJson.optLong("durationMs", 0L),
                bytes = objectJson.optLong("bytes", 0L),
                overruns = objectJson.optLong("overruns", 0L),
                publicUri = objectJson.optString("publicUri").takeIf { it.isNotBlank() },
                publicName = objectJson.optString("publicName").takeIf { it.isNotBlank() },
                exportState = enumValueOrDefault(
                    objectJson.optString("exportState"),
                    ExportState.NONE,
                ),
                error = objectJson.optString("error").takeIf { it.isNotBlank() },
                appVersion = objectJson.optString("appVersion"),
            )
        }.getOrNull()

        private inline fun <reified T : Enum<T>> enumValueOrDefault(
            value: String,
            default: T,
        ): T = runCatching { enumValueOf<T>(value) }.getOrDefault(default)
    }
}
