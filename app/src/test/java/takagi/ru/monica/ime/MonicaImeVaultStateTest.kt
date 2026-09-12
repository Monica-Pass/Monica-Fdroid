package takagi.ru.monica.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MonicaImeVaultStateTest {
    private val panels = listOf(
        MonicaImePanel.PASSWORDS, MonicaImePanel.AUTHENTICATORS, MonicaImePanel.DOCUMENTS
    )

    @Test fun everyUnlockedVaultPanelCanEditItsOwnSearch() {
        panels.forEach { panel ->
            val before = MonicaImeUiState(
                unlocked = true, activePanel = panel, query = "demo",
                keyboardMode = MonicaKeyboardMode.SYMBOLS, isUppercase = true,
                selectedDatabaseScope = MonicaImeDatabaseScope.KeePass(7)
            )
            val editing = before.startVaultSearch()
            assertTrue(editing.isSearchEditing)
            assertEquals(panel, editing.activePanel)
            assertEquals("demo", editing.query)
            assertEquals(before.selectedDatabaseScope, editing.selectedDatabaseScope)
            assertEquals(MonicaKeyboardMode.LETTERS, editing.keyboardMode)
            assertFalse(editing.isUppercase)
        }
    }

    @Test fun lockedVaultsAndNonVaultPanelsCannotStartSearch() {
        panels.forEach { panel ->
            val locked = MonicaImeUiState(activePanel = panel)
            assertSame(locked, locked.startVaultSearch())
        }
        listOf(MonicaImePanel.KEYBOARD, MonicaImePanel.GENERATOR).forEach { panel ->
            val state = MonicaImeUiState(unlocked = true, activePanel = panel)
            assertSame(state, state.startVaultSearch())
        }
    }

    @Test fun searchCountsAlwaysBelongToTheActivePanel() {
        val state = MonicaImeUiState(
            entries = listOf(passwordEntry()),
            authenticatorEntries = List(2) {
                MonicaImeAuthenticatorEntry(it.toLong(), "Demo", "", "", "123456", 20, false, "Local")
            },
            cardWalletEntries = List(3) {
                MonicaImeCardWalletEntry(it.toLong(), "Demo", "", "Card", false, "Local", emptyList())
            }
        )
        panels.forEachIndexed { index, panel ->
            assertEquals(index + 1, state.copy(activePanel = panel).activeEntryCount)
        }
        assertEquals(0, state.copy(activePanel = MonicaImePanel.KEYBOARD).activeEntryCount)
    }

    @Test fun reselectingTheCurrentPanelPreservesItsSearch() {
        panels.forEach { panel ->
            val state = MonicaImeUiState(unlocked = true, activePanel = panel, query = "demo")
            val selected = state.selectVaultPanel(panel, isLoading = false)
            assertEquals("demo", selected.query)
            assertEquals(state.vaultPresentation(), selected.vaultPresentation())
            assertFalse(selected.isSearchEditing)
        }
    }

    @Test fun returningToAFilteredPanelDetectsThatItsQueryWasCleared() {
        panels.forEach { panel ->
            val filtered = MonicaImeUiState(unlocked = true, activePanel = panel, query = "demo")
            val cachedPresentation = filtered.vaultPresentation()
            val otherPanel = panels.first { it != panel }
            val returned = filtered.selectVaultPanel(otherPanel, isLoading = false)
                .selectVaultPanel(panel, isLoading = false)
            assertEquals("", returned.query)
            assertNotEquals(cachedPresentation, returned.vaultPresentation())
        }
    }

    @Test fun changingDatabaseScopeInvalidatesPreviouslyCachedPanelResults() {
        panels.forEach { panel ->
            val original = MonicaImeUiState(unlocked = true, activePanel = panel)
            val changed = original.copy(selectedDatabaseScope = MonicaImeDatabaseScope.Mdbx(8))
            assertNotEquals(original.vaultPresentation(), changed.vaultPresentation())
        }
    }

    @Test fun unchangedFiltersCanReuseTheCachedProjectionWhenSwitchingBack() {
        panels.forEach { panel ->
            val original = MonicaImeUiState(unlocked = true, activePanel = panel)
            val otherPanel = panels.first { it != panel }
            val returned = original.selectVaultPanel(otherPanel, isLoading = false)
                .selectVaultPanel(panel, isLoading = false)
            assertEquals(original.vaultPresentation(), returned.vaultPresentation())
        }
    }

    @Test fun packageChangesInvalidateOnlyPasswordRelevanceOrdering() {
        val state = MonicaImeUiState(
            activePanel = MonicaImePanel.PASSWORDS,
            passwordSortMode = MonicaImePasswordSortMode.RELEVANCE,
            activePackageName = "demo.first"
        )
        assertNotEquals(state.vaultPresentation(), state.copy(activePackageName = "demo.second").vaultPresentation())
        val alphabetical = state.copy(passwordSortMode = MonicaImePasswordSortMode.ALPHABETICAL)
        assertEquals(alphabetical.vaultPresentation(), alphabetical.copy(activePackageName = "demo.second").vaultPresentation())
    }

    private fun passwordEntry() = MonicaImePasswordEntry(
        id = 1, title = "Demo", username = "user", website = "https://example.com",
        packageName = "", password = "demo", isFavorite = false, sourceLabel = "Local"
    )
}
