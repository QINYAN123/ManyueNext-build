package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.map
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.domain.download.service.DownloadPreferences

/**
 * This worker is used to manage the downloader. The system can decide to stop the worker, in
 * which case the downloader is also stopped. It's also stopped while there's no network available.
 */
class DownloadJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var downloadManager: DownloadManager

    @Inject private lateinit var downloadPreferences: DownloadPreferences

    init {
        graph.inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val networkCheck = checkNetworkState(
            applicationContext.activeNetworkState(),
            downloadPreferences.downloadOnlyOverWifi.get(),
        )
        if (!networkCheck) {
            return Result.failure()
        }

        setForegroundSafely()

        if (!downloadManager.downloaderStart()) {
            return Result.failure()
        }

        try {
            awaitDownloadCompletion(
                networkAllowed = combineTransform(
                    applicationContext.networkStateFlow(),
                    downloadPreferences.downloadOnlyOverWifi.changes(),
                    transform = { a, b -> emit(checkNetworkState(a, b)) },
                ),
                isDownloading = { !isStopped && downloadManager.isRunning },
            )
        } finally {
            // The downloader owns a separate scope, so worker cancellation must stop it too.
            if (downloadManager.isRunning) downloadManager.downloaderStop()
        }

        return Result.success()
    }

    private fun checkNetworkState(state: NetworkState, requireWifi: Boolean): Boolean {
        return if (state.isOnline) {
            val noWifi = requireWifi && !state.isWifi
            if (noWifi) {
                downloadManager.downloaderStop(
                    applicationContext.getString(R.string.download_notifier_text_only_wifi),
                )
            }
            !noWifi
        } else {
            downloadManager.downloaderStop(applicationContext.getString(R.string.download_notifier_no_network))
            false
        }
    }

    companion object {
        private const val TAG = "Downloader"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
