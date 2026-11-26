package engineer.cookie.soundrecorder.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * Filesystem layout and sidecar handling for recordings.
 *
 * Recordings live in app-specific external storage by default, so no storage
 * permission is needed. If external storage is unavailable, internal storage
 * is used instead.
 */
class RecordingStore(
    private val context: Context,
) {
    val recordingsDir: File by lazy { resolveRecordingsDir(context) }

    fun listRecordings(): List<Recording> {
        val dir = recordingsDir
        val files = dir.listFiles() ?: return emptyList()

        return files
            .asSequence()
            .filter { it.isFile && (it.name.endsWith(RecordingName.EXTENSION) ||
                it.name.endsWith(RecordingName.EXTENSION + RecordingName.PART_SUFFIX)) }
            .map { file -> toRecording(file) }
            .sortedByDescending { it.startedAtEpochMs }
            .toList()
    }

    fun readMetadata(recording: Recording): RecordingMetadata? =
        readMetadata(recording.metadataFile)

    fun readMetadata(file: File): RecordingMetadata? {
        if (!file.isFile) return null
        return runCatching { RecordingMetadata.fromJson(file.readText()) }.getOrNull()
    }

    fun writeMetadata(file: File, metadata: RecordingMetadata) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        val bytes = metadata.toJson().toByteArray(Charsets.UTF_8)
        FileOutputStream(temp, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    /**
     * Renames a stale `.aac.part` to `.aac` and marks its sidecar as recovered.
     * Returns the recovered recording, or null if recovery was not possible.
     */
    fun recover(recording: Recording): Recording? {
        if (!recording.isIncomplete) return recording
        if (!recording.file.isFile) return null

        if (recording.file.length() <= 0L) {
            recording.file.delete()
            recording.metadataFile.delete()
            return null
        }

        val dir = recordingsDir
        var baseName = recording.baseName
        var finalFile = File(dir, RecordingName.finalFileName(baseName))
        var suffix = 2
        while (finalFile.exists()) {
            baseName = "${recording.baseName} ($suffix)"
            finalFile = File(dir, RecordingName.finalFileName(baseName))
            suffix += 1
        }

        val moved = recording.file.renameTo(finalFile) || runCatching {
            recording.file.copyTo(finalFile, overwrite = false)
            recording.file.delete()
            true
        }.getOrDefault(false)

        if (!moved) return null

        val oldMetadataFile = recording.metadataFile
        val newMetadataFile = File(dir, RecordingName.metadataFileName(baseName))
        val oldMetadata = readMetadata(oldMetadataFile)
        val estimatedDuration = estimateDurationMs(
            finalFile.length(),
            oldMetadata?.bitrate ?: QualityPreset.SPEECH.bitrate,
        )
        val duration = maxOf(oldMetadata?.durationMs ?: 0L, estimatedDuration)

        val metadata = (oldMetadata ?: RecordingMetadata(
            state = RecordingState.RECOVERED,
            startedAtEpochMs = finalFile.lastModified(),
            sampleRate = QualityPreset.SPEECH.sampleRate,
            channels = QualityPreset.SPEECH.channels,
            bitrate = QualityPreset.SPEECH.bitrate,
            audioSource = AudioSourceOption.AUTO.name,
            appVersion = "0.0.0",
        )).copy(
            state = RecordingState.RECOVERED,
            endedAtEpochMs = finalFile.lastModified(),
            durationMs = duration,
            bytes = finalFile.length(),
            error = null,
        )

        writeMetadata(newMetadataFile, metadata)
        if (oldMetadataFile != newMetadataFile) oldMetadataFile.delete()

        return Recording(
            file = finalFile,
            metadata = metadata,
            durationMs = duration,
            sizeBytes = finalFile.length(),
        )
    }

    /**
     * Recovers every stale `.part` file except the one currently being
     * recorded. Returns the recovered base names.
     */
    fun recoverStale(activePartPath: String?): List<String> {
        val dir = recordingsDir
        val files = dir.listFiles() ?: return emptyList()
        val recovered = mutableListOf<String>()

        for (file in files) {
            if (!file.isFile) continue
            if (!file.name.endsWith(RecordingName.EXTENSION + RecordingName.PART_SUFFIX)) continue
            if (activePartPath != null && file.absolutePath == activePartPath) continue

            val recording = toRecording(file)
            val result = recover(recording)
            if (result != null) recovered += result.baseName
        }

        return recovered
    }

    /** Deletes the app-private file and sidecar. Public copies are handled separately. */
    fun delete(recording: Recording): Boolean {
        val metadataFile = recording.metadataFile
        val fileDeleted = !recording.file.exists() || recording.file.delete()
        val metadataDeleted = !metadataFile.exists() || metadataFile.delete()
        return fileDeleted && metadataDeleted
    }

    fun estimateDurationMs(bytes: Long, bitrate: Int): Long {
        if (bytes <= 0L || bitrate <= 0) return 0L
        return bytes * 8L * 1_000L / bitrate
    }

    private fun toRecording(file: File): Recording {
        val metadataFile = File(
            file.parentFile,
            file.name.removeSuffix(RecordingName.PART_SUFFIX) + ".json",
        )
        val metadata = readMetadata(metadataFile)
        val size = file.length()
        val duration = metadata?.durationMs?.takeIf { it > 0L }
            ?: estimateDurationMs(size, metadata?.bitrate ?: QualityPreset.SPEECH.bitrate)

        return Recording(
            file = file,
            metadata = metadata,
            durationMs = duration,
            sizeBytes = size,
        )
    }

    companion object {
        fun resolveRecordingsDir(context: Context): File {
            val external = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?.resolve("Recordings")
            val dir = external ?: File(context.filesDir, "recordings")
            dir.mkdirs()
            return dir
        }
    }
}
