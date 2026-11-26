package engineer.cookie.soundrecorder.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import engineer.cookie.soundrecorder.R
import engineer.cookie.soundrecorder.ui.MainActivity

object RecordingNotifications {
    const val CHANNEL_ID = "recording"
    const val NOTIFICATION_ID = 1001
    private const val ERROR_NOTIFICATION_ID = 1002

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_recording_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: RecorderState): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            is RecorderState.Starting -> {
                builder
                    .setContentTitle(context.getString(R.string.notification_starting))
                    .setContentText(context.getString(R.string.notification_recording_text))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .addAction(
                        R.drawable.ic_stat_stop,
                        context.getString(R.string.notification_stop),
                        stopIntent(context),
                    )
            }

            is RecorderState.Recording -> {
                builder
                    .setContentTitle(context.getString(R.string.notification_recording))
                    .setContentText(context.getString(R.string.notification_recording_text))
                    .setWhen(state.startedAtEpochMs)
                    .setUsesChronometer(true)
                    .setOngoing(true)
                    .addAction(
                        R.drawable.ic_stat_stop,
                        context.getString(R.string.notification_stop),
                        stopIntent(context),
                    )
            }

            is RecorderState.Saving -> {
                builder
                    .setContentTitle(context.getString(R.string.notification_saving))
                    .setContentText(context.getString(R.string.notification_saving_text))
                    .setOngoing(true)
                    .setShowWhen(false)
            }

            is RecorderState.Error -> {
                builder
                    .setContentTitle(context.getString(R.string.notification_error))
                    .setContentText(state.message)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setShowWhen(false)
            }

            RecorderState.Idle -> {
                builder
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.ready))
                    .setOngoing(false)
                    .setShowWhen(false)
            }
        }

        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, state: RecorderState) {
        if (!canPostNotifications(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, state))
        }
    }

    @SuppressLint("MissingPermission")
    fun postError(context: Context, message: String) {
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notification_error))
            .setContentText(message)
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(ERROR_NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
