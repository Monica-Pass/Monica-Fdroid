package takagi.ru.monica.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryClipboardPolicyTest {
    @Test
    fun restoresWhenTemporaryClipboardStillMatches() {
        assertTrue(
            shouldRestoreTemporaryClipboard(
                snapshot = TemporaryClipboardSnapshot(
                    text = "secret",
                    label = "Monica autofill",
                    canVerify = true,
                ),
                expectedLabel = "Monica autofill",
                expectedText = "secret",
            )
        )
    }

    @Test
    fun preservesClipboardWhenUserCopiedSomethingElse() {
        assertFalse(
            shouldRestoreTemporaryClipboard(
                snapshot = TemporaryClipboardSnapshot(
                    text = "new user content",
                    label = "User copy",
                    canVerify = true,
                ),
                expectedLabel = "Monica autofill",
                expectedText = "secret",
            )
        )
    }

    @Test
    fun restoresOrClearsWhenClipboardCannotBeVerified() {
        assertTrue(
            shouldRestoreTemporaryClipboard(
                snapshot = TemporaryClipboardSnapshot(
                    text = null,
                    label = null,
                    canVerify = false,
                ),
                expectedLabel = "Monica autofill",
                expectedText = "secret",
            )
        )
    }
}
