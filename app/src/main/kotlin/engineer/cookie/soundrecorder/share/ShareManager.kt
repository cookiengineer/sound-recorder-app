package engineer.cookie.soundrecorder.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import engineer.cookie.soundrecorder.data.Recording
import java.io.File

object ShareManager {
    const val MIME_AAC = "audio/aac"
    const val MIME_ZIP = "application/zip"

    fun shareRecording(context: Context, recording: Recording) {
        require(!recording.isIncomplete) { "Recover incomplete recordings before sharing" }
        shareFile(
            context = context,
            file = recording.file,
            mimeType = MIME_AAC,
            subject = recording.displayName,
        )
    }

    fun shareZip(context: Context, zipFile: File) {
        shareFile(
            context = context,
            file = zipFile,
            mimeType = MIME_ZIP,
            subject = zipFile.name,
        )
    }

    fun zipDestination(context: Context, recording: Recording): File {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        return File(directory, ZipPacker.zipFileNameFor(recording.file))
    }

    fun cleanupCache(context: Context) {
        ZipPacker.cleanupCache(File(context.cacheDir, "shared"))
    }

    private fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
        subject: String,
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newUri(context.contentResolver, subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, subject)
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
