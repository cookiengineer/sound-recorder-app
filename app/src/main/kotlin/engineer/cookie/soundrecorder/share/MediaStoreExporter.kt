package engineer.cookie.soundrecorder.share

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import engineer.cookie.soundrecorder.data.Recording
import engineer.cookie.soundrecorder.data.RecordingName
import java.io.File

data class PublicCopy(
    val uri: String,
    val displayName: String,
)

/**
 * Copies a finished recording into the public Music collection so it survives
 * uninstall. The app-private file remains the working copy for playback and
 * sharing.
 */
object MediaStoreExporter {

    fun export(context: Context, recording: Recording): Result<PublicCopy> {
        if (recording.isIncomplete) {
            return Result.failure(IllegalStateException("Recording is incomplete"))
        }

        val exactName = recording.file.name
        val safeName = RecordingName.safePublicName(exactName)

        val first = exportWithName(context, recording.file, exactName)
        if (first.isSuccess) return first

        if (safeName != exactName) {
            return exportWithName(context, recording.file, safeName)
        }
        return first
    }

    fun delete(context: Context, publicUri: String?) {
        if (publicUri.isNullOrBlank()) return
        val uri = runCatching { Uri.parse(publicUri) }.getOrNull() ?: return
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun exportWithName(
        context: Context,
        source: File,
        displayName: String,
    ): Result<PublicCopy> {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/Sound Recorder",
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri: Uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return Result.failure(IllegalStateException("MediaStore rejected the recording"))

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("MediaStore returned no output stream")

            val publish = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)

            Result.success(PublicCopy(uri.toString(), displayName))
        } catch (throwable: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Result.failure(throwable)
        }
    }

    @Suppress("unused")
    private fun contentUriFor(id: Long): Uri =
        ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            id,
        )

    private const val MIME_TYPE = "audio/aac"
}
