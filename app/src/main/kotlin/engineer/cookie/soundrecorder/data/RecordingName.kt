package engineer.cookie.soundrecorder.data

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Filename helper.
 *
 * The requested format is the ISO-8601-like local timestamp with a space
 * instead of the usual 'T': 2026-11-25 00:01:02.aac.
 *
 * Colons and spaces are legal on Android's ext4/f2fs storage. They are not
 * legal on FAT/exFAT, so [safePublicName] is used when exporting a public
 * MediaStore copy.
 */
object RecordingName {
    const val EXTENSION = ".aac"
    const val PART_SUFFIX = ".part"

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun baseName(dateTime: LocalDateTime): String = dateTime.format(FORMATTER)

    fun fileName(dateTime: LocalDateTime): String = baseName(dateTime) + EXTENSION

    fun partFileName(baseName: String): String = baseName + EXTENSION + PART_SUFFIX

    fun finalFileName(baseName: String): String = baseName + EXTENSION

    fun metadataFileName(baseName: String): String = baseName + EXTENSION + ".json"

    fun partFileName(dateTime: LocalDateTime): String = partFileName(baseName(dateTime))

    fun finalFileName(dateTime: LocalDateTime): String = finalFileName(baseName(dateTime))

    /**
     * Returns a base name that is not yet used in [directory]. Collisions are
     * rare (one recording per second) but possible after a clock change.
     */
    fun uniqueBaseName(directory: File, dateTime: LocalDateTime): String {
        val base = baseName(dateTime)
        var candidate = base
        var index = 2
        while (candidateExists(directory, candidate)) {
            candidate = "$base ($index)"
            index += 1
        }
        return candidate
    }

    fun safePublicName(fileName: String): String =
        fileName
            .replace(':', '-')
            .replace(' ', '_')

    private fun candidateExists(directory: File, baseName: String): Boolean =
        File(directory, finalFileName(baseName)).exists() ||
            File(directory, partFileName(baseName)).exists() ||
            File(directory, metadataFileName(baseName)).exists()
}
