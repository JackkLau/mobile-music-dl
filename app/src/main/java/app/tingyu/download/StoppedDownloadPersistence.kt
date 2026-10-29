package app.tingyu.download

import app.tingyu.model.DownloadTask
import kotlinx.coroutines.CancellationException

/** A stopped transfer must stay stopped in memory even if its final database write fails. */
internal suspend fun persistStoppedDownload(
    task: DownloadTask,
    persist: suspend (DownloadTask) -> Unit,
): DownloadTask = try {
    persist(task)
    task
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    task.copy(error = listOfNotNull(
        task.error?.takeIf(String::isNotBlank),
        "下载状态未能保存，请检查手机存储空间",
    ).joinToString("；"))
}
