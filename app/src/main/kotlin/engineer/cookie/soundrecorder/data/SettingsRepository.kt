package engineer.cookie.soundrecorder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val quality: QualityPreset = QualityPreset.SPEECH,
    val audioSource: AudioSourceOption = AudioSourceOption.AUTO,
    val autoSave: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun updateQuality(preset: QualityPreset) {
        update(_settings.value.copy(quality = preset))
    }

    fun updateAudioSource(source: AudioSourceOption) {
        update(_settings.value.copy(audioSource = source))
    }

    fun updateAutoSave(enabled: Boolean) {
        update(_settings.value.copy(autoSave = enabled))
    }

    fun currentConfig(): RecordingConfig =
        RecordingConfig.from(_settings.value.quality, _settings.value.audioSource)

    private fun update(value: Settings) {
        _settings.value = value
        preferences.edit()
            .putString(KEY_QUALITY, value.quality.name)
            .putString(KEY_AUDIO_SOURCE, value.audioSource.name)
            .putBoolean(KEY_AUTO_SAVE, value.autoSave)
            .apply()
    }

    private fun load(): Settings {
        val quality = runCatching {
            QualityPreset.valueOf(
                preferences.getString(KEY_QUALITY, QualityPreset.SPEECH.name)
                    ?: QualityPreset.SPEECH.name,
            )
        }.getOrDefault(QualityPreset.SPEECH)

        val source = runCatching {
            AudioSourceOption.valueOf(
                preferences.getString(KEY_AUDIO_SOURCE, AudioSourceOption.AUTO.name)
                    ?: AudioSourceOption.AUTO.name,
            )
        }.getOrDefault(AudioSourceOption.AUTO)

        return Settings(
            quality = quality,
            audioSource = source,
            autoSave = preferences.getBoolean(KEY_AUTO_SAVE, true),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "sound_recorder_settings"
        const val KEY_QUALITY = "quality"
        const val KEY_AUDIO_SOURCE = "audio_source"
        const val KEY_AUTO_SAVE = "auto_save"
    }
}
