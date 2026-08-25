package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2InitialLoadingPolicyTest {

    @Test
    fun `initial empty derivation stays loading until password source is ready`() {
        assertTrue(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = true,
                hasVisibleSections = false,
                hasRetainedSnapshot = false,
                passwordEntriesReady = false,
                computedListIsComputing = false,
                hasPendingItems = false,
            )
        )
    }

    @Test
    fun `first load remains loading while the computed stage is unfinished`() {
        assertTrue(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = true,
                hasVisibleSections = false,
                hasRetainedSnapshot = false,
                passwordEntriesReady = true,
                computedListIsComputing = true,
                hasPendingItems = false,
            )
        )
        assertTrue(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = true,
                hasVisibleSections = false,
                hasRetainedSnapshot = false,
                passwordEntriesReady = true,
                computedListIsComputing = false,
                hasPendingItems = true,
            )
        )
    }

    @Test
    fun `loaded empty list and retained empty snapshot show the real empty state`() {
        assertFalse(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = true,
                hasVisibleSections = false,
                hasRetainedSnapshot = false,
                passwordEntriesReady = true,
                computedListIsComputing = false,
                hasPendingItems = false,
            )
        )
        assertFalse(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = true,
                hasVisibleSections = false,
                hasRetainedSnapshot = true,
                passwordEntriesReady = false,
                computedListIsComputing = true,
                hasPendingItems = true,
            )
        )
    }

    @Test
    fun `visible data and active search never use the initial loading state`() {
        assertFalse(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = true,
                hasVisibleSections = true,
                hasRetainedSnapshot = false,
                passwordEntriesReady = false,
                computedListIsComputing = true,
                hasPendingItems = true,
            )
        )
        assertFalse(
            shouldShowVaultV2InitialLoading(
                queryIsBlank = false,
                hasVisibleSections = false,
                hasRetainedSnapshot = false,
                passwordEntriesReady = false,
                computedListIsComputing = true,
                hasPendingItems = true,
            )
        )
    }
}
