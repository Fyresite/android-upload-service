package net.gotev.uploadservice.observer.task

import android.app.NotificationManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.data.UploadInfo
import net.gotev.uploadservice.data.UploadNotificationConfig
import net.gotev.uploadservice.data.UploadNotificationStatusConfig
import net.gotev.uploadservice.exceptions.UserCancelledUploadException
import net.gotev.uploadservice.network.ServerResponse
import net.gotev.uploadservice.placeholders.PlaceholdersProcessor

class NotificationHandler(
    private val service: UploadService,
    private val notificationManager: NotificationManager,
    private val namespace: String,
    private val placeholdersProcessor: PlaceholdersProcessor
) : UploadTaskObserver {

    private val notificationCreationTimeMillis by lazy { System.currentTimeMillis() }

    private fun NotificationCompat.Builder.setRingtoneCompat(isRingToneEnabled: Boolean): NotificationCompat.Builder {
        if (isRingToneEnabled && Build.VERSION.SDK_INT < 26) {
            val sound = RingtoneManager.getActualDefaultRingtoneUri(
                service,
                RingtoneManager.TYPE_NOTIFICATION
            )
            setSound(sound)
        }

        return this
    }

    private fun NotificationCompat.Builder.notify(uploadId: String, notificationId: Int) {
        build().apply {
            if (service.holdForegroundNotification(uploadId, this)) {
                notificationManager.cancel(notificationId)
            } else {
                notificationManager.notify(notificationId, this)
            }
        }
    }

    private fun NotificationCompat.Builder.setCommonParameters(
        statusConfig: UploadNotificationStatusConfig,
        info: UploadInfo
    ): NotificationCompat.Builder {
        return setGroup(namespace)
            .setContentTitle(placeholdersProcessor.processPlaceholders(statusConfig.title, info))
            .setContentText(placeholdersProcessor.processPlaceholders(statusConfig.message, info))
            .setContentIntent(statusConfig.getClickIntent(service))
            .setSmallIcon(statusConfig.iconResourceID)
            .setLargeIcon(statusConfig.largeIcon)
            .setColor(statusConfig.iconColorResourceID)
            .apply {
                statusConfig.addActionsToNotificationBuilder(this)
            }
    }

    private fun ongoingNotification(
        notificationChannelId: String,
        info: UploadInfo,
        statusConfig: UploadNotificationStatusConfig
    ): NotificationCompat.Builder? {
        if (statusConfig.message == null) return null

        return NotificationCompat.Builder(service, notificationChannelId)
            .setWhen(notificationCreationTimeMillis)
            .setCommonParameters(statusConfig, info)
            .setOngoing(true)
    }

    private fun updateNotification(
        notificationId: Int,
        info: UploadInfo,
        notificationChannelId: String,
        isRingToneEnabled: Boolean,
        statusConfig: UploadNotificationStatusConfig
    ) {
        notificationManager.cancel(notificationId)

        if (statusConfig.message == null || statusConfig.autoClear) return

        val notification = NotificationCompat.Builder(service, notificationChannelId)
            .setCommonParameters(statusConfig, info)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(statusConfig.clearOnAction)
            .setRingtoneCompat(isRingToneEnabled)
            .build()

        // this is needed because the main notification used to show progress is ongoing
        // and a new one has to be created to allow the user to dismiss it
        notificationManager.notify(notificationId + 1, notification)
    }

    override fun initialize(
        info: UploadInfo,
        notificationId: Int,
        notificationConfig: UploadNotificationConfig?
    ) {
        val config = notificationConfig ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(config.notificationChannelId)
                ?: throw IllegalArgumentException("The provided notification channel ID ${config.notificationChannelId} does not exist! You must create it at app startup and before Upload Service!")
        }

        ongoingNotification(config.notificationChannelId, info, config.progress)
            ?.setProgress(100, 0, true)
            ?.notify(info.uploadId, notificationId)
    }

    override fun onProgress(
        info: UploadInfo,
        notificationId: Int,
        notificationConfig: UploadNotificationConfig?
    ) {
        val config = notificationConfig ?: return

        ongoingNotification(config.notificationChannelId, info, config.progress)
            ?.setProgress(100, info.progressPercent, false)
            ?.notify(info.uploadId, notificationId)
    }

    override fun onSuccess(
        info: UploadInfo,
        notificationId: Int,
        notificationConfig: UploadNotificationConfig?,
        response: ServerResponse
    ) {
        val config = notificationConfig ?: return

        updateNotification(
            notificationId,
            info,
            config.notificationChannelId,
            config.isRingToneEnabled,
            config.completed
        )
    }

    override fun onError(
        info: UploadInfo,
        notificationId: Int,
        notificationConfig: UploadNotificationConfig?,
        exception: Throwable
    ) {
        val config = notificationConfig ?: return

        val statusConfig = if (exception is UserCancelledUploadException) {
            config.cancelled
        } else {
            config.error
        }

        updateNotification(
            notificationId,
            info,
            config.notificationChannelId,
            config.isRingToneEnabled,
            statusConfig
        )
    }

    override fun onCompleted(
        info: UploadInfo,
        notificationId: Int,
        notificationConfig: UploadNotificationConfig?
    ) {
    }
}
