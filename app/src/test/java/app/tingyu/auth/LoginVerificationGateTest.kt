package app.tingyu.auth

import app.tingyu.data.auth.LoginVerificationGate
import org.junit.Assert.*
import org.junit.Test

class LoginVerificationGateTest {
    private val candidate = "SESSDATA=test-session; DedeUserID=42"

    @Test fun anonymousCookiesNeverStartVerification() {
        assertNull(LoginVerificationGate().begin("buvid3=device-only", manual = true))
    }

    @Test fun pageCallbacksAndPollingCannotDuplicateAnActiveCheck() {
        val gate = LoginVerificationGate()
        assertNotNull(gate.begin(candidate))
        assertNull(gate.begin(candidate))
        assertNull(gate.begin(candidate, manual = true))
    }

    @Test fun failedAutomaticCheckWaitsForManualRetryOrNewSession() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        gate.finish(first)
        assertNull(gate.begin(candidate))
        assertNull(gate.begin("buvid3=new-device; DedeUserID=42; SESSDATA=test-session"))
        assertNotNull(gate.begin(candidate, manual = true))
    }

    @Test fun changedCredentialsCanBeAutomaticallyVerified() {
        val gate = LoginVerificationGate()
        gate.finish(gate.begin(candidate)!!)
        assertNotNull(gate.begin("SESSDATA=next-session; DedeUserID=43"))
    }

    @Test fun staleResponseCannotSaveAnotherAccountsSession() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        var saved = false
        assertFalse(gate.commit(first, "SESSDATA=changed; DedeUserID=43") { saved = true })
        assertFalse(saved)
        assertFalse(gate.completed)
    }

    @Test fun removedSessionCannotBeCommitted() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        assertFalse(gate.commit(first, "") { fail("must not save an expired candidate") })
    }

    @Test fun backgroundingInvalidatesLateResultsButAllowsFreshCheck() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        gate.cancelActive()
        val next = gate.begin(candidate)!!
        assertNotEquals(first.id, next.id)
        assertFalse(gate.commit(first, candidate) { fail("stale check saved") })
        gate.finish(first) // An old finally block must not clear the new request.
        assertTrue(gate.commit(next, candidate) {})
    }

    @Test fun leavingScreenPermanentlyRejectsLateSuccessAndNewChecks() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        gate.close()
        assertFalse(gate.commit(first, candidate) { fail("closed screen saved") })
        assertNull(gate.begin(candidate, manual = true))
    }

    @Test fun successOccursOnlyAfterSaveAndIsPublishedOnce() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        var saves = 0
        assertTrue(gate.commit(first, "buvid3=updated; $candidate") {
            assertFalse(gate.completed)
            saves++
        })
        assertTrue(gate.completed)
        assertFalse(gate.commit(first, candidate) { saves++ })
        assertNull(gate.begin(candidate, manual = true))
        assertEquals(1, saves)
    }

    @Test fun storageFailureDoesNotReportSuccessOrBlockManualRetry() {
        val gate = LoginVerificationGate()
        val first = gate.begin(candidate)!!
        assertThrows(IllegalStateException::class.java) {
            gate.commit(first, candidate) { error("storage unavailable") }
        }
        assertFalse(gate.completed)
        gate.finish(first)
        assertNotNull(gate.begin(candidate, manual = true))
    }
}
