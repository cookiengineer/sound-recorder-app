package engineer.cookie.soundrecorder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import engineer.cookie.soundrecorder.R
import engineer.cookie.soundrecorder.audio.RecordingStats
import engineer.cookie.soundrecorder.data.AudioSourceOption
import engineer.cookie.soundrecorder.data.ExportState
import engineer.cookie.soundrecorder.data.QualityPreset
import engineer.cookie.soundrecorder.data.Recording
import engineer.cookie.soundrecorder.data.Settings
import engineer.cookie.soundrecorder.service.RecorderEvent
import engineer.cookie.soundrecorder.service.RecorderState
import engineer.cookie.soundrecorder.share.ShareManager
import engineer.cookie.soundrecorder.share.ZipPacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(viewModel: RecorderViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val playingFile by viewModel.playingFile.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMicRationale by rememberSaveable { mutableStateOf(false) }
    var showNotificationRationale by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Recording?>(null) }
    var recoverCandidate by remember { mutableStateOf<Recording?>(null) }
    var zipInProgress by remember { mutableStateOf<String?>(null) }
    var resumeEpoch by remember { mutableStateOf(0) }

    val hasMicPermission = remember(resumeEpoch) {
        ContextCompatCheck.hasPermission(context, Manifest.permission.RECORD_AUDIO)
    }
    val hasNotificationPermission = remember(resumeEpoch) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompatCheck.hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        resumeEpoch += 1
        if (!granted) showNotificationRationale = true
        viewModel.startRecording()
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        resumeEpoch += 1
        if (granted) {
            if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startRecording()
            }
        } else {
            showMicRationale = true
        }
    }

    val freeBytes by produceState(
        initialValue = viewModel.freeBytes(),
        key1 = state,
    ) {
        while (true) {
            value = viewModel.freeBytes()
            delay(5_000)
        }
    }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val ignoringBatteryOptimizations = remember(state, resumeEpoch) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { event ->
            when (event) {
                is RecorderEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        resumeEpoch += 1
        viewModel.refresh()
        viewModel.recoverStale()
        onPauseOrDispose { }
    }

    val isActive = state is RecorderState.Recording || state is RecorderState.Starting
    val isSaving = state is RecorderState.Saving

    val activePartPath = when (val current = state) {
        is RecorderState.Starting -> current.partFile.absolutePath
        is RecorderState.Recording -> current.partFile.absolutePath
        is RecorderState.Saving -> current.partFile?.absolutePath
        else -> null
    }
    val visibleRecordings = remember(recordings, activePartPath) {
        recordings.filter { it.file.absolutePath != activePartPath }
    }

    fun onRecordClick() {
        when (state) {
            is RecorderState.Recording,
            is RecorderState.Starting,
            -> viewModel.stopRecording()

            is RecorderState.Saving -> Unit

            else -> when {
                !hasMicPermission -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission ->
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                else -> viewModel.startRecording()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RecorderPanel(
                    state = state,
                    stats = stats,
                    freeBytes = freeBytes,
                    quality = settings.quality,
                    isActive = isActive,
                    isSaving = isSaving,
                    onRecordClick = ::onRecordClick,
                )
            }

            if (!ignoringBatteryOptimizations && state is RecorderState.Idle) {
                item {
                    BatteryHint(
                        onOpenSettings = { openBatterySettings(context) },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.recordings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }

            if (visibleRecordings.isEmpty() && !isActive && !isSaving) {
                item { EmptyRecordings() }
            } else if (visibleRecordings.isNotEmpty()) {
                items(visibleRecordings, key = { it.file.absolutePath }) { recording ->
                    RecordingCard(
                        recording = recording,
                        isPlaying = playingFile == recording.file.absolutePath,
                        zipInProgress = zipInProgress == recording.baseName,
                        onPlay = { viewModel.togglePlayback(recording) },
                        onShareRaw = {
                            runCatching { ShareManager.shareRecording(context, recording) }
                                .onFailure { throwable ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(
                                                R.string.share_failed,
                                                throwable.message ?: "unknown error",
                                            ),
                                        )
                                    }
                                }
                        },
                        onShareZip = {
                            scope.launch {
                                zipInProgress = recording.baseName
                                try {
                                    val zip = withContext(Dispatchers.IO) {
                                        val destination = ShareManager.zipDestination(context, recording)
                                        ZipPacker.createZip(recording.file, destination)
                                        destination
                                    }
                                    ShareManager.shareZip(context, zip)
                                } catch (throwable: Throwable) {
                                    snackbarHostState.showSnackbar(
                                        context.getString(
                                            R.string.zip_failed,
                                            throwable.message ?: "unknown error",
                                        ),
                                    )
                                } finally {
                                    zipInProgress = null
                                }
                            }
                        },
                        onSaveToMusic = { viewModel.saveToMusic(recording) },
                        onDelete = { deleteCandidate = recording },
                        onRecover = { recoverCandidate = recording },
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            recordingsDir = viewModel.recordingsDir,
            onQuality = viewModel::updateQuality,
            onAudioSource = viewModel::updateAudioSource,
            onAutoSave = viewModel::updateAutoSave,
            onDismiss = { showSettings = false },
        )
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text(stringResource(R.string.permission_microphone_title)) },
            text = { Text(stringResource(R.string.permission_microphone_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMicRationale = false
                        openAppSettings(context)
                    },
                ) { Text(stringResource(R.string.permission_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = { showNotificationRationale = false },
            title = { Text(stringResource(R.string.permission_notifications_title)) },
            text = { Text(stringResource(R.string.permission_notifications_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationRationale = false
                        openAppSettings(context)
                    },
                ) { Text(stringResource(R.string.permission_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteCandidate?.let { recording ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_recording_title)) },
            text = { Text(stringResource(R.string.delete_recording_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        viewModel.delete(recording)
                    },
                ) { Text(stringResource(R.string.delete_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    recoverCandidate?.let { recording ->
        AlertDialog(
            onDismissRequest = { recoverCandidate = null },
            title = { Text(stringResource(R.string.recover_recording_title)) },
            text = { Text(stringResource(R.string.recover_recording_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        recoverCandidate = null
                        viewModel.recover(recording)
                    },
                ) { Text(stringResource(R.string.recover)) }
            },
            dismissButton = {
                TextButton(onClick = { recoverCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RecorderPanel(
    state: RecorderState,
    stats: RecordingStats,
    freeBytes: Long,
    quality: QualityPreset,
    isActive: Boolean,
    isSaving: Boolean,
    onRecordClick: () -> Unit,
) {
    val context = LocalContext.current
    val status = when (state) {
        RecorderState.Idle -> stringResource(R.string.ready)
        is RecorderState.Starting -> stringResource(R.string.starting)
        is RecorderState.Recording -> stringResource(R.string.recording)
        is RecorderState.Saving -> stringResource(R.string.saving)
        is RecorderState.Error -> stringResource(R.string.notification_error)
    }
    val statusColor = when (state) {
        is RecorderState.Recording -> MaterialTheme.colorScheme.error
        is RecorderState.Starting -> MaterialTheme.colorScheme.tertiary
        is RecorderState.Saving -> MaterialTheme.colorScheme.tertiary
        is RecorderState.Error -> MaterialTheme.colorScheme.error
        RecorderState.Idle -> MaterialTheme.colorScheme.primary
    }

    val displayedElapsedMs = if (state is RecorderState.Recording || state is RecorderState.Saving) {
        stats.elapsedMs
    } else {
        0L
    }

    val pulse = rememberInfiniteTransition(label = "record-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "record-pulse-alpha",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            statusColor.copy(
                                alpha = if (state is RecorderState.Recording) pulseAlpha else 1f,
                            ),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = formatDuration(displayedElapsedMs),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
            )

            Spacer(Modifier.height(8.dp))

            LevelMeter(
                level = stats.level,
                active = state is RecorderState.Recording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 8.dp),
            )

            Spacer(Modifier.height(18.dp))

            FilledIconButton(
                onClick = onRecordClick,
                enabled = !isSaving,
                modifier = Modifier.size(96.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (isActive) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ),
            ) {
                Icon(
                    painter = painterResource(
                        if (isActive) R.drawable.ic_stop else R.drawable.ic_mic,
                    ),
                    contentDescription = stringResource(
                        if (isActive) R.string.stop else R.string.record,
                    ),
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = when {
                    isSaving -> stringResource(R.string.notification_saving_text)
                    state is RecorderState.Recording -> stringResource(R.string.notification_recording_text)
                    state is RecorderState.Starting -> stringResource(R.string.notification_starting)
                    else -> stringResource(R.string.no_recordings_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(
                            R.string.free_space,
                            formatBytes(context, freeBytes),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.estimate_per_hour,
                            formatBytes(context, quality.bytesPerHour),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Text(
                        text = stringResource(R.string.quality_label_short, quality.bitrateKbps),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(48) { add(0f) } } }

    LaunchedEffect(level, active) {
        if (active) {
            bars.removeAt(0)
            bars.add(level.coerceIn(0f, 1f))
        } else {
            for (index in bars.indices) bars[index] = 0f
        }
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        if (bars.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = max(1f, (size.width - gap * (bars.size - 1)) / bars.size)
        bars.forEachIndexed { index, value ->
            val height = max(2.dp.toPx(), value * size.height)
            drawRoundRect(
                color = if (active) {
                    activeColor.copy(alpha = 0.35f + 0.65f * value)
                } else {
                    idleColor
                },
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun BatteryHint(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.battery_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.battery_settings))
                }
            }
        }
    }
}

@Composable
private fun EmptyRecordings() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.no_recordings),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.no_recordings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingCard(
    recording: Recording,
    isPlaying: Boolean,
    zipInProgress: Boolean,
    onPlay: () -> Unit,
    onShareRaw: () -> Unit,
    onShareZip: () -> Unit,
    onSaveToMusic: () -> Unit,
    onDelete: () -> Unit,
    onRecover: () -> Unit,
) {
    val context = LocalContext.current
    var shareMenuExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_graphic_eq),
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(formatDuration(recording.durationMs))
                            append(" • ")
                            append(formatBytes(context, recording.sizeBytes))
                            if (recording.isIncomplete) {
                                append(" • ")
                                append(context.getString(R.string.incomplete))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExportStatus(recording)
                }

                IconButton(
                    onClick = onPlay,
                    enabled = !recording.isIncomplete,
                ) {
                    if (isPlaying) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(R.string.stop_playback),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.play),
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { shareMenuExpanded = true },
                        enabled = !recording.isIncomplete,
                    ) {
                        Icon(
                            Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.share_raw),
                        )
                    }
                    DropdownMenu(
                        expanded = shareMenuExpanded,
                        onDismissRequest = { shareMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_raw)) },
                            onClick = {
                                shareMenuExpanded = false
                                onShareRaw()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_zip)) },
                            onClick = {
                                shareMenuExpanded = false
                                onShareZip()
                            },
                            enabled = !zipInProgress,
                        )
                    }
                }

                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        if (recording.isIncomplete) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recover)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onRecover()
                                },
                            )
                        } else if (recording.exportState != ExportState.DONE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_to_music)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Done, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onSaveToMusic()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (recording.isIncomplete) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.incomplete_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (zipInProgress) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.zip_preparing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportStatus(recording: Recording) {
    when {
        recording.isIncomplete -> Unit

        recording.exportState == ExportState.DONE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.public_copy_done),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        recording.exportState == ExportState.FAILED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.public_copy_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        else -> {
            Text(
                text = stringResource(R.string.public_copy_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: Settings,
    recordingsDir: File,
    onQuality: (QualityPreset) -> Unit,
    onAudioSource: (AudioSourceOption) -> Unit,
    onAutoSave: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.quality),
                style = MaterialTheme.typography.titleMedium,
            )
            QualityPreset.entries.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.quality == preset,
                            onClick = { onQuality(preset) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.quality == preset,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = qualityLabel(preset),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(
                                R.string.estimate_per_hour,
                                formatBytes(context, preset.bytesPerHour),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.microphone),
                style = MaterialTheme.typography.titleMedium,
            )
            AudioSourceOption.entries.forEach { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.audioSource == source,
                            onClick = { onAudioSource(source) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.audioSource == source,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = sourceLabel(source),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_save),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.auto_save_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.autoSave,
                    onCheckedChange = onAutoSave,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.storage_private, recordingsDir.absolutePath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.format_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun qualityLabel(preset: QualityPreset): String = when (preset) {
    QualityPreset.SPEECH -> stringResource(R.string.quality_speech)
    QualityPreset.BALANCED -> stringResource(R.string.quality_balanced)
    QualityPreset.HIGH -> stringResource(R.string.quality_high)
}

@Composable
private fun sourceLabel(source: AudioSourceOption): String = when (source) {
    AudioSourceOption.AUTO -> stringResource(R.string.source_auto)
    AudioSourceOption.UNPROCESSED -> stringResource(R.string.source_unprocessed)
    AudioSourceOption.VOICE_RECOGNITION -> stringResource(R.string.source_voice_recognition)
    AudioSourceOption.MIC -> stringResource(R.string.source_mic)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(context: Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

private fun openAppSettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { context.startActivity(intent) }
}

private object ContextCompatCheck {
    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
