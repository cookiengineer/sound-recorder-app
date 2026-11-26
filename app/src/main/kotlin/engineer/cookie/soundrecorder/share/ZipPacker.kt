package engineer.cookie.soundrecorder.share

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Wraps an AAC file in a ZIP without compressing the audio payload.
 *
 * Messaging apps often treat shared audio as media and may transcode it. A ZIP
 * is an opaque document, so the raw AAC bytes arrive unchanged. Using STORED
 * also avoids wasting CPU on already compressed data.
 */
object ZipPacker {

    fun zipFileNameFor(source: File): String {
        val name = source.name.removeSuffix(".part")
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return "$base.zip"
    }

    fun createZip(source: File, destination: File) {
        require(source.isFile) { "Source does not exist: $source" }
        require(!destination.exists() || destination.delete()) {
            "Could not replace destination: $destination"
        }

        destination.parentFile?.mkdirs()

        // STORED entries need size and CRC before the data is written.
        val crc = CRC32()
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }

        val size = source.length()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination), 64 * 1024)).use { zip ->
            val entry = ZipEntry(source.name).apply {
                method = ZipEntry.STORED
                this.size = size
                compressedSize = size
                this.crc = crc.value
                time = source.lastModified()
            }
            zip.putNextEntry(entry)
            source.inputStream().use { input ->
                input.copyTo(zip, 64 * 1024)
            }
            zip.closeEntry()
        }
    }

    /** Removes cached ZIPs older than [maxAgeMs]. */
    fun cleanupCache(cacheDir: File, maxAgeMs: Long = 7L * 24L * 60L * 60L * 1_000L) {
        val files = cacheDir.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        for (file in files) {
            if (file.isFile && file.name.endsWith(".zip") && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
