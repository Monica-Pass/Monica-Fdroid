package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2EntryCacheIntegrationGuardTest {

    @Test
    fun `pane seeds and updates both retained list stages`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val paneState = source("ui/vaultv2/VaultV2PaneState.kt")
        val retainedState = source("ui/vaultv2/VaultV2RetainedStateViewModel.kt")

        assertTrue(pane.contains("state.computedListSnapshots.seed("))
        assertTrue(pane.contains("state.computedListSnapshots.update("))
        assertTrue(pane.contains("source = computedSources"))
        assertTrue(pane.contains("computationKey = computedSnapshotKey to computedSources"))
        assertTrue(pane.contains("state.visibleListSnapshots.seed("))
        assertTrue(pane.contains("state.visibleListSnapshots.update("))
        assertTrue(pane.contains("initialHasComputed = computedSnapshotSeed.hasSnapshot && visibleSnapshotSeed.hasSnapshot"))
        assertTrue(pane.contains("visibleSnapshotSeed.value"))
        assertTrue(
            pane.contains(
                "computedListStateAsync.hasComputed &&"
            )
        )
        assertTrue(
            pane.contains(
                "showVaultLoadingIndicator = !hasDisplayedContent && isVaultListLoading"
            )
        )
        assertTrue(pane.contains("shouldShowVaultV2InitialLoading("))
        assertTrue(pane.contains("hasRetainedSnapshot = visibleSnapshotSeed.hasSnapshot"))
        assertTrue(pane.contains("value = visibleListState"))
        assertTrue(pane.contains("val displayListStateAsync = rememberVaultV2AsyncComputedValue("))
        assertTrue(pane.contains("buildVaultV2DisplayListState("))
        assertTrue(paneState.contains("VaultV2RetainedSourceSnapshotStore<"))
        assertTrue(retainedState.contains("computedListSnapshots: VaultV2RetainedSourceSnapshotStore<"))
    }

    @Test
    fun `retained snapshots are memory only and cleared when vault locks`() {
        val state = source("ui/vaultv2/VaultV2PaneState.kt")
        val retainedState = source("ui/vaultv2/VaultV2RetainedStateViewModel.kt")
        val mainScreen = source("ui/SimpleMainScreen.kt")

        val saverBlock = state
            .substringAfter("internal fun vaultV2PaneStateSaver(")
            .substringBefore("@Composable")
        assertFalse(saverBlock.contains("computedListSnapshots"))
        assertFalse(saverBlock.contains("visibleListSnapshots"))
        assertTrue(retainedState.contains("override fun onCleared()"))
        assertTrue(retainedState.contains("retainedState.clear()"))
        assertTrue(mainScreen.contains("vaultV2PaneState.clearRetainedListSnapshots()"))
        assertTrue(mainScreen.contains("if (!isPasswordVaultAuthenticated)"))
    }

    @Test
    fun `secondary vault sources expose retained StateFlows`() {
        val bankCards = source("viewmodel/BankCardViewModel.kt")
        val documents = source("viewmodel/DocumentViewModel.kt")
        val notes = source("viewmodel/NoteViewModel.kt")
        val pane = source("ui/vaultv2/VaultV2Pane.kt")

        assertTrue(bankCards.contains("val allCards: StateFlow<List<SecureItem>>"))
        assertTrue(documents.contains("val allDocuments: StateFlow<List<SecureItem>>"))
        assertTrue(notes.contains("val allNotes: StateFlow<List<SecureItem>>"))
        assertTrue(pane.contains("bankCardViewModel.allCards.collectAsState()"))
        assertTrue(pane.contains("documentViewModel.allDocuments.collectAsState()"))
        assertTrue(pane.contains("noteViewModel.allNotes.collectAsState()"))
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/$relativePath"
        ).readText()
    }
}
