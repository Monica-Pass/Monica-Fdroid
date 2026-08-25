package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2DetailReturnRetentionGuardTest {

    @Test
    fun `main back stack view model owns snapshots across detail navigation`() {
        val mainScreen = source("ui/SimpleMainScreen.kt")
        val paneState = source("ui/vaultv2/VaultV2PaneState.kt")

        assertTrue(mainScreen.contains("VaultV2RetainedStateViewModel = viewModel()"))
        assertTrue(
            mainScreen.contains(
                "rememberVaultV2PaneState(retainedStateViewModel.retainedState)"
            )
        )
        assertTrue(paneState.contains("internal fun vaultV2PaneStateSaver("))
        assertTrue(paneState.contains("retainedState: VaultV2RetainedState"))
        assertTrue(paneState.contains("retainedState = retainedState"))
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        assertTrue(
            Regex(
                "var\\s+selectedAggregateTypes\\s+by\\s+rememberSaveable\\(\\s*" +
                    "stateSaver\\s*=\\s*passwordPageContentTypeSetSaver"
            ).containsMatchIn(pane)
        )
    }

    @Test
    fun `restored pane state reuses retained snapshots and lock clears them`() {
        val retainedState = VaultV2RetainedState()
        val key = VaultV2ComputedSnapshotKey(
            isArchiveView = false,
            showOnlyLocalData = false,
        )
        val source = VaultV2ComputedSources(
            passwords = emptyList(),
            totpItems = emptyList(),
            bankCardItems = emptyList(),
            documentItems = emptyList(),
            noteItems = emptyList(),
            passkeyItems = emptyList(),
        )
        retainedState.computedListSnapshots.update(key, source, VaultV2ComputedListState())

        val restored = requireNotNull(
            vaultV2PaneStateSaver(retainedState).restore(
                listOf(
                    0, 0, 0, 0f, 0,
                    VAULT_V2_STORAGE_FILTER_ALL, null, null, false, 0, false,
                    null, null, null,
                )
            )
        )

        assertTrue(
            restored.computedListSnapshots.seed(key, source, VaultV2ComputedListState()).hasSnapshot
        )
        restored.clearRetainedListSnapshots()
        assertFalse(
            restored.computedListSnapshots.seed(key, source, VaultV2ComputedListState()).hasSnapshot
        )
    }

    @Test
    fun `manual stack metadata survives detail return and invalidates on entry update`() {
        val retainedState = VaultV2RetainedState()
        val firstRevision = listOf(VaultV2PasswordRevision(id = 1L, updatedAtMillis = 10L))
        val changedRevision = listOf(VaultV2PasswordRevision(id = 1L, updatedAtMillis = 11L))
        val metadata = VaultV2ManualStackMetadata(
            revisions = firstRevision,
            manualStackGroupByEntryId = mapOf(1L to "group"),
            noStackEntryIds = emptySet(),
        )

        retainedState.updateManualStackMetadata(metadata)

        assertTrue(retainedState.seedManualStackMetadata(firstRevision) === metadata)
        assertTrue(retainedState.seedManualStackMetadata(changedRevision) == null)
        retainedState.clear()
        assertTrue(retainedState.seedManualStackMetadata(firstRevision) == null)
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
