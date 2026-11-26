package engineer.cookie.soundrecorder.service

import engineer.cookie.soundrecorder.audio.RecordingStats
import engineer.cookie.soundrecorder.data.RecordingConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface RecorderState {
    data object Idle : RecorderState

    data class Starting(
        val baseName: String,
        val partFile: File,
    ) : RecorderState

    data class Recording(
        val baseName: String,
        val partFile: File,
        val startedAtEpochMs: Long,
        val config: RecordingConfig,
    ) : RecorderState

    data class Saving(
        val baseName: String,
        val partFile: File? = null,
    ) : RecorderState

    data class Error(val message: String) : RecorderState
}

sealed interface RecorderEvent {
    data class Message(val text: String, val isError: Boolean = false) : RecorderEvent
}

/**
 * Process-wide bridge between the foreground service and the Compose UI.
 * The service and activity always run in the same process.
 */
object RecordingController {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(RecordingStats())
    val stats: StateFlow<RecordingStats> = _stats.asStateFlow()

    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

    val isActive: Boolean
        get() = when (_state.value) {
            is RecorderState.Starting,
            is RecorderState.Recording,
            is RecorderState.Saving,
            -> true

            else -> false
        }

    val activePartPath: String?
        get() = when (val current = _state.value) {
            is RecorderState.Starting -> current.partFile.absolutePath
            is RecorderState.Recording -> current.partFile.absolutePath
            is RecorderState.Saving -> current.partFile?.absolutePath
            else -> null
        }

    val activeBaseName: String?
        get() = when (val current = _state.value) {
            is RecorderState.Starting -> current.baseName
            is RecorderState.Recording -> current.baseName
            is RecorderState.Saving -> current.baseName
            else -> null
        }

    internal fun setState(state: RecorderState) {
        _state.value = state
    }

    internal fun setStats(stats: RecordingStats) {
        _stats.value = stats
    }

    internal fun resetStats() {
        _stats.value = RecordingStats()
    }

    fun emit(event: RecorderEvent) {
        _events.tryEmit(event)
    }
}
