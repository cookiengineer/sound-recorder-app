package engineer.cookie.soundrecorder.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import engineer.cookie.soundrecorder.BuildConfig
import engineer.cookie.soundrecorder.R
import engineer.cookie.soundrecorder.audio.AacEncoderException
import engineer.cookie.soundrecorder.audio.AudioRecordException
import engineer.cookie.soundrecorder.data.ExportState
import engineer.cookie.soundrecorder.data.RecordingStore
import engineer.cookie.soundrecorder.data.SettingsRepository
import engineer.cookie.soundrecorder.share.MediaStoreExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Foreground service that keeps the microphone and encoder alive while the
 * screen is off or the task is in the background.
 */
class RecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var store: RecordingStore

    private var session: RecordingSession? = null
    private var storageJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var startInProgress = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var pendingStop = false

    @Volatile
    private var pendingStopError: Throwable? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        store = RecordingStore(this)
        RecordingNotifications.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep recording when the user swipes the task away. The foreground
        // notification remains available to stop it.
    }

    override fun onDestroy() {
        destroyed = true
        storageJob?.cancel()
        storageJob = null

        val current = session
        if (current != null) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    current.stop(reason = "Recording service was destroyed")
                }
            }
            session = null
        }

        releaseWakeLock()
        RecordingNotifications.cancel(this)
        serviceScope.cancel()
        RecordingController.setState(RecorderState.Idle)
        RecordingController.resetStats()
        super.onDestroy()
    }

    private fun handleStart() {
        if (RecordingController.isActive || startInProgress || stopping.get()) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failStart(getString(R.string.permission_denied))
            return
        }

        if (session != null) return

        startInProgress = true
        pendingStop = false
        pendingStopError = null
        stopping.set(false)
        RecordingController.resetStats()

        val startingState = RecorderState.Starting("starting", File(""))
        RecordingController.setState(startingState)

        try {
            startForegroundCompat(startingState)
        } catch (throwable: Throwable) {
            failStart(throwable.message ?: getString(R.string.recording_failed, "foreground service"))
            return
        }

        acquireWakeLock()
        serviceScope.launch { startInternal() }
    }

    private fun startInternal() {
        try {
            val config = settingsRepository.currentConfig()
            val newSession = RecordingSession(
                context = this,
                config = config,
                store = store,
                appVersion = BuildConfig.VERSION_NAME,
                onError = { error -> onEngineError(error) },
                onStats = { stats -> RecordingController.setStats(stats) },
            )

            session = newSession
            if (destroyed) {
                newSession.abort()
                session = null
                return
            }

            newSession.prepare()
            if (destroyed) {
                newSession.abort()
                session = null
                return
            }

            updateState(RecorderState.Starting(newSession.baseName, newSession.partFile))

            newSession.start()
            if (destroyed) {
                newSession.stop(reason = "Recording service was destroyed")
                session = null
                return
            }

            updateState(
                RecorderState.Recording(
                    baseName = newSession.baseName,
                    partFile = newSession.partFile,
                    startedAtEpochMs = newSession.startedAtEpochMs,
                    config = config,
                ),
            )
            startInProgress = false

            if (pendingStop) {
                val error = pendingStopError
                pendingStop = false
                pendingStopError = null
                handleStop(error)
            } else {
                startStorageMonitor()
            }
        } catch (cancelled: CancellationException) {
            startInProgress = false
            throw cancelled
        } catch (throwable: Throwable) {
            startInProgress = false
            failRecording(throwable)
        }
    }

    private fun handleStop(
        error: Throwable? = null,
        storageLow: Boolean = false,
    ) {
        val current = session
        if (current == null) {
            if (startInProgress) {
                pendingStop = true
                pendingStopError = error
                return
            }
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
            return
        }

        if (RecordingController.state.value is RecorderState.Starting) {
            pendingStop = true
            pendingStopError = error
            return
        }

        if (!stopping.compareAndSet(false, true)) return

        storageJob?.cancel()
        storageJob = null

        val baseName = RecordingController.activeBaseName ?: current.baseName
        updateState(RecorderState.Saving(baseName, current.partFile))

        serviceScope.launch {
            stopInternal(current, error, storageLow)
        }
    }

    private fun stopInternal(
        current: RecordingSession,
        error: Throwable?,
        storageLow: Boolean,
    ) {
        try {
            val recording = current.stop(
                reason = error?.message,
                isError = error != null,
            )

            if (recording != null) {
                val autoSave = settingsRepository.settings.value.autoSave
                if (autoSave) {
                    val exported = MediaStoreExporter.export(this, recording)
                    val metadata = recording.metadata
                    if (metadata != null) {
                        val updated = metadata.copy(
                            publicUri = exported.getOrNull()?.uri,
                            publicName = exported.getOrNull()?.displayName,
                            exportState = if (exported.isSuccess) {
                                ExportState.DONE
                            } else {
                                ExportState.FAILED
                            },
                        )
                        store.writeMetadata(recording.metadataFile, updated)
                    }
                    if (exported.isFailure) {
                        RecordingController.emit(
                            RecorderEvent.Message(
                                getString(R.string.save_to_music_failed),
                                isError = false,
                            ),
                        )
                    }
                }

                if (error == null) {
                    RecordingController.emit(
                        RecorderEvent.Message(getString(R.string.recording_saved)),
                    )
                }
            }

            if (storageLow) {
                RecordingController.emit(
                    RecorderEvent.Message(getString(R.string.storage_low)),
                )
            }

            if (error != null) {
                val message = error.message ?: error.javaClass.simpleName
                updateState(RecorderState.Error(message))
                RecordingNotifications.postError(this, message)
                RecordingController.emit(
                    RecorderEvent.Message(
                        getString(R.string.recording_failed, message),
                        isError = true,
                    ),
                )
            } else {
                updateState(RecorderState.Idle)
            }
        } catch (throwable: Throwable) {
            val message = throwable.message ?: throwable.javaClass.simpleName
            updateState(RecorderState.Error(message))
            RecordingNotifications.postError(this, message)
            RecordingController.emit(
                RecorderEvent.Message(
                    getString(R.string.recording_failed, message),
                    isError = true,
                ),
            )
        } finally {
            session = null
            releaseWakeLock()
            stopForegroundCompat()
            RecordingNotifications.cancel(this)
            stopSelf()
            stopping.set(false)
            pendingStop = false
            pendingStopError = null
        }
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SoundRecorder:recording",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
    }

    private fun startStorageMonitor() {
        storageJob?.cancel()
        storageJob = serviceScope.launch {
            val config = settingsRepository.currentConfig()
            val threshold = max(LOW_STORAGE_BYTES, config.bytesPerSecond * 120L)

            while (isActive) {
                delay(STORAGE_CHECK_INTERVAL_MS)
                if (RecordingController.state.value !is RecorderState.Recording) break

                val freeBytes = store.recordingsDir.usableSpace
                if (freeBytes in 1 until threshold) {
                    handleStop(storageLow = true)
                    break
                }
            }
        }
    }

    private fun onEngineError(error: Throwable) {
        if (stopping.get()) return
        serviceScope.launch { handleStop(error = error) }
    }

    private fun failStart(message: String) {
        startInProgress = false
        RecordingController.setState(RecorderState.Error(message))
        RecordingNotifications.postError(this, message)
        RecordingController.emit(RecorderEvent.Message(message, isError = true))
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun failRecording(throwable: Throwable) {
        startInProgress = false
        val message = when (throwable) {
            is AudioRecordException -> getString(
                R.string.audio_record_failed,
                throwable.message ?: "unknown error",
            )

            is AacEncoderException -> getString(R.string.unsupported_encoder)

            else -> getString(
                R.string.recording_failed,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }

        session?.abort()
        session = null
        updateState(RecorderState.Error(message))
        RecordingNotifications.postError(this, message)
        RecordingController.emit(RecorderEvent.Message(message, isError = true))
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun updateState(state: RecorderState) {
        RecordingController.setState(state)
        RecordingNotifications.update(this, state)
    }

    private fun startForegroundCompat(state: RecorderState) {
        val notification = RecordingNotifications.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                RecordingNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(RecordingNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "engineer.cookie.soundrecorder.action.START"
        const val ACTION_STOP = "engineer.cookie.soundrecorder.action.STOP"

        private const val LOW_STORAGE_BYTES = 100L * 1024L * 1024L
        private const val STORAGE_CHECK_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
