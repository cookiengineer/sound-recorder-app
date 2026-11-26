package engineer.cookie.soundrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import engineer.cookie.soundrecorder.R
import engineer.cookie.soundrecorder.data.AudioSourceOption
import engineer.cookie.soundrecorder.data.ExportState
import engineer.cookie.soundrecorder.data.QualityPreset
import engineer.cookie.soundrecorder.data.Recording
import engineer.cookie.soundrecorder.data.RecordingStore
import engineer.cookie.soundrecorder.data.SettingsRepository
import engineer.cookie.soundrecorder.service.RecorderEvent
import engineer.cookie.soundrecorder.service.RecorderState
import engineer.cookie.soundrecorder.service.RecordingController
import engineer.cookie.soundrecorder.service.RecordingService
import engineer.cookie.soundrecorder.share.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RecordingStore(application)
    private val settingsRepository = SettingsRepository(application)
    private val audioPlayer = AudioPlayer(application)

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _messages = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 16)
    val messages: SharedFlow<RecorderEvent> = _messages.asSharedFlow()

    private var recoveryJob: Job? = null

    val state: StateFlow<RecorderState> = RecordingController.state
    val stats = RecordingController.stats
    val settings = settingsRepository.settings
    val playingFile = audioPlayer.playingFile

    val recordingsDir: File get() = store.recordingsDir

    init {
        viewModelScope.launch {
            RecordingController.events.collect { _messages.emit(it) }
        }
        viewModelScope.launch {
            audioPlayer.errors.collect { throwable ->
                _messages.emit(
                    RecorderEvent.Message(
                        getApplication<Application>().getString(
                            R.string.playback_failed,
                            throwable.message ?: "unknown error",
                        ),
                        isError = true,
                    ),
                )
            }
        }
        viewModelScope.launch {
            RecordingController.state
                .map { it is RecorderState.Idle || it is RecorderState.Error }
                .distinctUntilChanged()
                .collect { refreshable -> if (refreshable) refresh() }
        }
        refresh()
        recoverStale()
    }

    fun refresh() {
        viewModelScope.launch {
            val activePartPath = RecordingController.activePartPath
            _recordings.value = withContext(Dispatchers.IO) {
                store.listRecordings().filter { it.file.absolutePath != activePartPath }
            }
        }
    }

    fun recoverStale() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            val recovered = withContext(Dispatchers.IO) {
                store.recoverStale(RecordingController.activePartPath)
            }
            if (recovered.isNotEmpty()) {
                _messages.emit(
                    RecorderEvent.Message(getApplication<Application>().getString(R.string.recording_recovered)),
                )
                refresh()
            }
        }
    }

    fun startRecording() {
        runCatching { RecordingService.start(getApplication()) }
            .onFailure { throwable ->
                viewModelScope.launch {
                    _messages.emit(
                        RecorderEvent.Message(
                            getApplication<Application>().getString(
                                R.string.recording_failed,
                                throwable.message ?: "could not start service",
                            ),
                            isError = true,
                        ),
                    )
                }
            }
    }

    fun stopRecording() {
        runCatching { RecordingService.stop(getApplication()) }
    }

    fun togglePlayback(recording: Recording) {
        try {
            audioPlayer.toggle(recording.file)
        } catch (throwable: Throwable) {
            viewModelScope.launch {
                _messages.emit(
                    RecorderEvent.Message(
                        getApplication<Application>().getString(
                            R.string.playback_failed,
                            throwable.message ?: "unknown error",
                        ),
                        isError = true,
                    ),
                )
            }
        }
    }

    fun delete(recording: Recording) {
        viewModelScope.launch {
            val application = getApplication<Application>()
            val deleted = withContext(Dispatchers.IO) {
                MediaStoreExporter.delete(application, recording.metadata?.publicUri)
                store.delete(recording)
            }
            if (!deleted) {
                _messages.emit(
                    RecorderEvent.Message(
                        application.getString(R.string.delete_failed),
                        isError = true,
                    ),
                )
            }
            refresh()
        }
    }

    fun recover(recording: Recording) {
        viewModelScope.launch {
            val recovered = withContext(Dispatchers.IO) { store.recover(recording) }
            if (recovered != null) {
                _messages.emit(
                    RecorderEvent.Message(
                        getApplication<Application>().getString(R.string.recording_recovered),
                    ),
                )
            } else {
                _messages.emit(
                    RecorderEvent.Message(
                        getApplication<Application>().getString(R.string.recover_failed),
                        isError = true,
                    ),
                )
            }
            refresh()
        }
    }

    fun saveToMusic(recording: Recording) {
        viewModelScope.launch {
            val application = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                MediaStoreExporter.export(application, recording)
            }

            withContext(Dispatchers.IO) {
                val metadata = store.readMetadata(recording)
                if (metadata != null) {
                    val publicCopy = result.getOrNull()
                    store.writeMetadata(
                        recording.metadataFile,
                        metadata.copy(
                            publicUri = publicCopy?.uri,
                            publicName = publicCopy?.displayName,
                            exportState = if (result.isSuccess) {
                                ExportState.DONE
                            } else {
                                ExportState.FAILED
                            },
                            autoExported = false,
                        ),
                    )
                }
            }

            _messages.emit(
                RecorderEvent.Message(
                    application.getString(
                        if (result.isSuccess) {
                            R.string.saved_to_music
                        } else {
                            R.string.save_to_music_failed
                        },
                    ),
                    isError = result.isFailure,
                ),
            )
            refresh()
        }
    }

    fun updateQuality(preset: QualityPreset) {
        settingsRepository.updateQuality(preset)
    }

    fun updateAudioSource(source: AudioSourceOption) {
        settingsRepository.updateAudioSource(source)
    }

    fun updateAutoSave(enabled: Boolean) {
        settingsRepository.updateAutoSave(enabled)
    }

    fun freeBytes(): Long = store.recordingsDir.usableSpace

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }
}
