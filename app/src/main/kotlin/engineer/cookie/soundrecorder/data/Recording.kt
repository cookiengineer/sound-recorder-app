package engineer.cookie.soundrecorder.data

import java.io.File

/** A recording found in the recordings directory. */
data class Recording(
    val file: File,
    val metadata: RecordingMetadata?,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    val baseName: String
        get() = file.name
            .removeSuffix(RecordingName.PART_SUFFIX)
            .removeSuffix(RecordingName.EXTENSION)

    val displayName: String get() = baseName

    val isIncomplete: Boolean get() = file.name.endsWith(RecordingName.EXTENSION + RecordingName.PART_SUFFIX)

    val metadataFile: File
        get() = File(file.parentFile, baseName + RecordingName.EXTENSION + ".json")

    val startedAtEpochMs: Long
        get() = metadata?.startedAtEpochMs?.takeIf { it > 0L } ?: file.lastModified()

    val exportState: ExportState
        get() = metadata?.exportState ?: ExportState.NONE

    val autoExported: Boolean
        get() = metadata?.autoExported == true

    val hasPublicCopy: Boolean
        get() = metadata?.publicUri != null && metadata.exportState == ExportState.DONE
}
