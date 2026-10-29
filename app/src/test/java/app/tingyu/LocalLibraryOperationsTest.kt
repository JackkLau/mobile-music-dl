package app.tingyu

import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLibraryOperationsTest {
    private val first = LocalTrack(Track("local_first", 0, "First song", localOnly = true), "content://fixture/audio/1", 123)
    private val second = LocalTrack(Track("local_second", 0, "Second song", localOnly = true), "content://fixture/audio/2", 456)

    @Test fun pickerResultAndDeletionWaitForStartupScanWithoutBeingDropped() = runTest {
        val operations = LocalLibraryOperations(null) { }
        val releaseScan = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch { operations.serialized { events += "scan-start"; releaseScan.await(); events += "scan-finished" } }
        runCurrent()
        launch { operations.serialized { events += "import-selected-URI" } }
        runCurrent()
        launch { operations.serialized { events += "delete-confirmed-file" } }
        runCurrent()
        assertEquals(listOf("scan-start"), events)
        releaseScan.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("scan-start", "scan-finished", "import-selected-URI", "delete-confirmed-file"), events)
    }

    @Test fun failedScanReleasesTheQueueForSelectedFiles() = runTest {
        val operations = LocalLibraryOperations(null) { }
        val releaseScan = CompletableDeferred<Unit>()
        var imported = false
        launch {
            try { operations.serialized { releaseScan.await(); throw IOException("scan failed") } }
            catch (_: IOException) { }
        }
        runCurrent()
        launch { operations.serialized { imported = true } }
        runCurrent()
        assertFalse(imported)
        releaseScan.complete(Unit)
        advanceUntilIdle()
        assertTrue(imported)
    }

    @Test fun cancelledWaiterCannotRunLaterOrBlockFollowingImport() = runTest {
        val operations = LocalLibraryOperations(null) { }
        val releaseScan = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch { operations.serialized { releaseScan.await() } }
        runCurrent()
        val deletion = launch { operations.serialized { events += "delete" } }
        runCurrent()
        deletion.cancelAndJoin()
        launch { operations.serialized { events += "import" } }
        releaseScan.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("import"), events)
    }

    @Test fun pendingSystemResultRestoresItsExactIdentityAfterProcessRecreation() {
        var saved: String? = null
        val before = LocalLibraryOperations(saved) { saved = it }
        val original = before.beginDeletion(first)
        assertNotNull(saved)
        val after = LocalLibraryOperations(saved) { saved = it }
        assertEquals(original, after.pendingDeletion)
        val approved = after.approveDeletion(original.operationId)!!
        assertEquals(first, approved.local)
        assertTrue(approved.approved)
        // Approval alone neither changes the local track nor declares deletion complete.
        assertNotNull(after.pendingDeletion)
        assertNull(after.approveDeletion(original.operationId))
        assertTrue(after.clearDeletion(original.operationId))
        assertNull(saved)
    }

    @Test fun staleSystemResultCannotApproveOrCancelANewDeleteTarget() {
        var saved: String? = null
        val operations = LocalLibraryOperations(null) { saved = it }
        val old = operations.beginDeletion(first)
        val restored = LocalLibraryOperations(saved) { saved = it }
        val current = restored.beginDeletion(second)
        assertNotEquals(old.operationId, current.operationId)
        assertNull(restored.approveDeletion(old.operationId))
        assertFalse(restored.clearDeletion(old.operationId))
        assertEquals(second, restored.pendingDeletion!!.local)
        assertEquals(second, restored.approveDeletion(current.operationId)!!.local)
    }

    @Test fun restoredUnansweredConfirmationDoesNotBlockScansOrAuthorizeDeletion() = runTest {
        var saved: String? = null
        LocalLibraryOperations(null) { saved = it }.beginDeletion(first)
        val restored = LocalLibraryOperations(saved) { saved = it }
        var scanned = false
        restored.serialized { scanned = true }
        assertTrue(scanned)
        assertFalse(restored.pendingDeletion!!.approved)
        assertTrue(restored.clearDeletion(restored.pendingDeletion!!.operationId))
        assertNull(restored.pendingDeletion)
    }

    @Test fun rejectedOrMalformedSavedStateNeverSuppliesADeleteTarget() {
        listOf("broken JSON", "{}", "{\"operationId\":\"bad\"}").forEach { saved ->
            assertNull(LocalLibraryOperations(saved) { fail("Restoring identity must not perform actions") }.pendingDeletion)
        }
    }
}
