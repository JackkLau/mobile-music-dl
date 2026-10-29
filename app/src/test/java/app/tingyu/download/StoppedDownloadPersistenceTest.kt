package app.tingyu.download

import app.tingyu.model.DownloadStatus
import app.tingyu.model.DownloadTask
import app.tingyu.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StoppedDownloadPersistenceTest {
    private val track = Track("BV1xx411c7mD", 42, "Music")

    @Test fun successfulWritePreservesTheStoppedTask() = runTest {
        val task = DownloadTask(track, DownloadStatus.CANCELLED, bytes = 512, totalBytes = 1024)
        var saved: DownloadTask? = null

        val visible = persistStoppedDownload(task) { saved = it }

        assertEquals(task, saved)
        assertSame(task, visible)
    }

    @Test fun failedWriteKeepsCancellationVisibleAndReportsPersistenceFailure() = runTest {
        val task = DownloadTask(track, DownloadStatus.CANCELLED, bytes = 512, totalBytes = 1024)

        val visible = persistStoppedDownload(task) { throw IOException("disk full /private/database") }

        assertEquals(DownloadStatus.CANCELLED, visible.status)
        assertEquals(track, visible.track)
        assertEquals(512L, visible.bytes)
        assertEquals(1024L, visible.totalBytes)
        assertTrue(visible.error!!.contains("状态未能保存"))
        assertFalse(visible.error!!.contains("/private/database"))
    }

    @Test fun failedWritePreservesInterruptionAndTransferFailureDetails() = runTest {
        listOf(
            DownloadStatus.INTERRUPTED to "下载已中断，点击重试继续",
            DownloadStatus.FAILED to "音频下载不完整，点击重试继续",
        ).forEach { (status, message) ->
            val visible = persistStoppedDownload(DownloadTask(track, status, error = message)) {
                throw IOException("database unavailable")
            }

            assertEquals(status, visible.status)
            assertTrue(visible.error!!.contains(message))
            assertTrue(visible.error!!.contains("状态未能保存"))
        }
    }

    @Test fun coroutineCancellationIsNotTurnedIntoASuccessfulResult() = runTest {
        val cancellation = CancellationException("owner stopped")
        try {
            persistStoppedDownload(DownloadTask(track, DownloadStatus.INTERRUPTED)) { throw cancellation }
            fail("Coroutine cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
