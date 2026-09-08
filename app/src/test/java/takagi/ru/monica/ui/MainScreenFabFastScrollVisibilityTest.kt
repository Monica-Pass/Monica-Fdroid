package takagi.ru.monica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenFabFastScrollVisibilityTest {
    @Test
    fun `vault floating actions hide only during scrollbar interaction`() {
        assertFalse(
            shouldHideVaultFloatingActionsForFastScroll(
                isVaultV2Tab = true,
                isScrollbarInteracting = false,
            )
        )
        assertTrue(
            shouldHideVaultFloatingActionsForFastScroll(
                isVaultV2Tab = true,
                isScrollbarInteracting = true,
            )
        )
    }

    @Test
    fun `other tabs keep floating actions visible`() {
        assertFalse(
            shouldHideVaultFloatingActionsForFastScroll(
                isVaultV2Tab = false,
                isScrollbarInteracting = true,
            )
        )
    }
}
