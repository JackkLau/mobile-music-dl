package app.tingyu

import app.tingyu.model.LocalTrack
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Serializes local mutations and remembers identity, never an Android permission grant. */
internal class LocalLibraryOperations(
    restoredDeletion: String?,
    private val saveDeletion: (String?) -> Unit,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    var pendingDeletion: PendingLocalDeletion? = restoredDeletion?.let {
        runCatching { json.decodeFromString<PendingLocalDeletion>(it) }.getOrNull()
            ?.takeIf { pending -> runCatching { UUID.fromString(pending.operationId).toString() == pending.operationId }.getOrDefault(false) }
    }
        private set

    suspend fun <T> serialized(operation: suspend () -> T): T = mutex.withLock { operation() }

    fun beginDeletion(local: LocalTrack): PendingLocalDeletion =
        PendingLocalDeletion(UUID.randomUUID().toString(), local).also(::store)

    /** A result is tied to its original target, including after Activity/process recreation. */
    fun approveDeletion(operationId: String): PendingLocalDeletion? {
        val pending = pendingDeletion?.takeIf { it.operationId == operationId && !it.approved } ?: return null
        return pending.copy(approved = true).also(::store)
    }

    fun clearDeletion(operationId: String): Boolean {
        if (pendingDeletion?.operationId != operationId) return false
        store(null)
        return true
    }

    private fun store(pending: PendingLocalDeletion?) {
        saveDeletion(pending?.let { json.encodeToString(it) })
        pendingDeletion = pending
    }
}

@Serializable
internal data class PendingLocalDeletion(
    val operationId: String,
    val local: LocalTrack,
    val approved: Boolean = false,
)
