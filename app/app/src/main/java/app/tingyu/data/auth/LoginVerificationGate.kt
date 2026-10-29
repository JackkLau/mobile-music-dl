package app.tingyu.data.auth

import java.security.MessageDigest

/**
 * Owns one visible login flow. Call on the UI thread, including [commit], so leaving
 * the screen cannot interleave with the final guarded save. No credential is logged
 * or retained here; only an in-memory digest is used to deduplicate automatic checks.
 */
class LoginVerificationGate {
    class Attempt internal constructor(val id: Long, internal val sessionKey: String)

    private var serial = 0L
    private var active: Attempt? = null
    private var attemptedKey: String? = null
    private var closed = false
    var completed: Boolean = false
        private set

    fun begin(candidate: String, manual: Boolean = false): Attempt? {
        if (closed || completed || active != null || !CookiePolicy.hasSession(candidate)) return null
        val key = sessionKey(candidate)
        // No background retry loop on invalid credentials, rate limits or network errors.
        // A changed session or an explicit completion tap may start a new check.
        if (!manual && attemptedKey == key) return null
        attemptedKey = key
        return Attempt(++serial, key).also { active = it }
    }

    fun commit(attempt: Attempt, currentCandidate: String, save: () -> Unit): Boolean {
        if (closed || completed || active != attempt || !CookiePolicy.hasSession(currentCandidate) ||
            attempt.sessionKey != sessionKey(currentCandidate)) return false
        save() // Failure must leave the flow retryable, never publish a false success.
        completed = true
        active = null
        attemptedKey = null
        return true
    }

    fun finish(attempt: Attempt) {
        if (active == attempt) active = null
    }

    /** Pause/reload invalidates late responses and allows a fresh check when resumed. */
    fun cancelActive() {
        if (active != null) attemptedKey = null
        active = null
    }

    fun close() {
        closed = true
        active = null
        attemptedKey = null
    }

    private fun sessionKey(candidate: String): String {
        val identity = CookiePolicy.normalize(candidate).split("; ")
            .filter { it.startsWith("SESSDATA=") || it.startsWith("DedeUserID=") }
            .sorted().joinToString("; ")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
