package takagi.ru.monica.passkey

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.keepass.KeePassPasskeyCredentialConflictException

class PasskeyMoveReporterTest {
    private fun entry(id: Long) = PasskeyEntry(id = id, credentialId = "credential-$id",
        rpId = "example.invalid", rpName = "Example", userId = "user", userName = "Account $id",
        userDisplayName = "User", publicKey = "sensitive-public-material", privateKeyAlias = "sensitive-private-material")

    @Test fun allBlockedReportsEachReasonWithoutWriting() = runBlocking {
        val report = PasskeyMoveReporter.execute(listOf(
            entry(1).copy(boundPasswordId = 5),
            entry(2).copy(boundPasswordId = 5, syncStatus = "REFERENCE"),
        )) { fail("Blocked entries must not be written"); Result.success(Unit) }
        assertEquals(0, report.movedCount)
        assertEquals(listOf(PasskeyMoveIssueReason.BOUND_PASSWORD, PasskeyMoveIssueReason.REFERENCE_ONLY),
            report.issues.map { it.reason })
        assertEquals(listOf("Account 1", "Account 2"), report.issues.map { it.account })
    }

    @Test fun partialFailureKeepsCorrectCountsAndContinuesWithoutLeakingExceptionOrKeyMaterial() = runBlocking {
        val calls = mutableListOf<Long>()
        val report = PasskeyMoveReporter.execute((1L..5L).map(::entry)) {
            calls += it.id
            when (it.id) {
                1L -> Result.success(Unit)
                2L -> Result.failure(PasskeyBitwardenMoveBlockedException())
                3L -> Result.failure(KeePassPasskeyCredentialConflictException("sensitive-credential"))
                4L -> throw java.io.IOException("sensitive-exception-value")
                else -> Result.failure(PasskeyMoveSourceCleanupException(java.io.IOException("sensitive-path")))
            }
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), calls)
        assertEquals(1, report.movedCount)
        assertEquals(listOf(PasskeyMoveIssueReason.BITWARDEN_UNSUPPORTED, PasskeyMoveIssueReason.KEEPASS_CONFLICT,
            PasskeyMoveIssueReason.UPDATE_FAILED, PasskeyMoveIssueReason.SOURCE_CLEANUP_FAILED), report.issues.map { it.reason })
        assertFalse(report.toString().contains("sensitive"))
    }

    @Test fun allSuccessfulUsesMovedCountAndNoProblemDialogData() = runBlocking {
        val report = PasskeyMoveReporter.execute((1L..3L).map(::entry)) { Result.success(Unit) }
        assertEquals(3, report.movedCount)
        assertTrue(report.issues.isEmpty())
    }

    @Test fun cancellationNeverBecomesSkippedItemOrContinuesTheBatch() {
        for (throwDirectly in listOf(false, true)) {
            var calls = 0
            try {
                runBlocking {
                    PasskeyMoveReporter.execute(listOf(entry(1), entry(2))) {
                        calls++
                        if (throwDirectly) throw CancellationException("cancelled")
                        Result.failure(CancellationException("cancelled"))
                    }
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(1, calls)
            }
        }
    }
}
