package engineer.cookie.soundrecorder.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Playback wrapper built on Media3 ExoPlayer.
 *
 * ExoPlayer has a proper ADTS AAC extractor, which MediaPlayer does not handle
 * reliably on all LineageOS/AOSP builds.
 */
class AudioPlayer(context: Context) {
    private val appContext = context.applicationContext

    private val _playingFile = MutableStateFlow<String?>(null)
    val playingFile: StateFlow<String?> = _playingFile.asStateFlow()

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    private lateinit var player: ExoPlayer

    init {
        player = ExoPlayer.Builder(appContext).build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) stop()
            }

            override fun onPlayerError(error: PlaybackException) {
                _errors.tryEmit(error)
                stop()
            }
        })
    }

    fun toggle(file: File) {
        val path = file.absolutePath
        if (_playingFile.value == path) {
            stop()
            return
        }

        stop()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        _playingFile.value = path
    }

    fun stop() {
        if (!::player.isInitialized) return
        player.stop()
        player.clearMediaItems()
        _playingFile.value = null
    }

    fun release() {
        if (::player.isInitialized) {
            stop()
            player.release()
        }
    }
}
