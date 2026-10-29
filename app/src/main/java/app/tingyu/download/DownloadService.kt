package app.tingyu.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.tingyu.MusicApp
import app.tingyu.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Started only by an explicit download/retry action; never restarted from BOOT_COMPLETED. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var finishingNormally = false
    private val downloads get() = (application as MusicApp).container.downloads
    private val notifications get() = checkNotNull(getSystemService(NotificationManager::class.java))

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "音乐下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == ACTION_CANCEL) intent.getStringExtra(EXTRA_ID)?.let(downloads::cancel)
        scope.launch {
            try {
                // Each start gets a waiter. The coordinator serializes runners; stopSelfResult
                // prevents an older start from stopping a newer enqueued task.
                downloads.runPending { notifications.notify(NOTIFICATION_ID, notification()) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                downloads.interruptAll()
            } finally {
                if (stopSelfResult(startId)) finishingNormally = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.interruptAll()
        stopSelf()
    }

    override fun onDestroy() {
        if (!finishingNormally) downloads.interruptAll()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val task = downloads.jobs.value.firstOrNull { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.SAVING }
            ?: downloads.jobs.value.firstOrNull { it.status == DownloadStatus.QUEUED }
        val remaining = downloads.jobs.value.count { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.SAVING) }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.track?.title ?: "准备下载")
            .setContentText(if (task?.status == DownloadStatus.SAVING) "正在保存到音乐目录" else "下载队列 · 剩余 $remaining 首")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (task != null) {
            val cancel = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, task.track.id)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消当前下载", PendingIntent.getService(
                this, task.track.id.hashCode(), cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            if (task.totalBytes > 0 && task.status == DownloadStatus.DOWNLOADING) {
                builder.setProgress(1000, (task.bytes * 1000 / task.totalBytes).coerceIn(0, 1000).toInt(), false)
            } else builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "music_downloads"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_CANCEL = "app.tingyu.download.CANCEL"
        private const val EXTRA_ID = "track_id"
    }
}
