package takagi.ru.monica.ui.cardwallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection

class CardWalletSyncScopeTest {

    @Test
    fun localAndNonBitwardenScopesDoNotTriggerBitwardenSync() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.All,
            UnifiedCategoryFilterSelection.Local,
            UnifiedCategoryFilterSelection.Starred,
            UnifiedCategoryFilterSelection.Uncategorized,
            UnifiedCategoryFilterSelection.LocalStarred,
            UnifiedCategoryFilterSelection.LocalUncategorized,
            UnifiedCategoryFilterSelection.Custom(10L),
            UnifiedCategoryFilterSelection.KeePassDatabaseFilter(20L),
            UnifiedCategoryFilterSelection.KeePassGroupFilter(20L, "cards"),
            UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(20L),
            UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(20L),
            UnifiedCategoryFilterSelection.MdbxDatabaseFilter(30L),
            UnifiedCategoryFilterSelection.MdbxFolderFilter(30L, "folder")
        )

        filters.forEach { filter ->
            assertFalse(filter.isBitwardenWalletScope())
            assertNull(filter.bitwardenVaultIdForWalletSync())
        }
    }

    @Test
    fun bitwardenScopesExposeVaultIdForSync() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.BitwardenVaultFilter(7L),
            UnifiedCategoryFilterSelection.BitwardenFolderFilter(7L, "folder"),
            UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(7L),
            UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(7L)
        )

        filters.forEach { filter ->
            assertTrue(filter.isBitwardenWalletScope())
            assertEquals(7L, filter.bitwardenVaultIdForWalletSync())
        }
    }

    @Test
    fun cardWalletDoesNotCreateBitwardenViewModelForLocalScopes() {
        val walletScreen = projectFile("src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt").readText()
        val contentSource = projectFile("src/main/java/takagi/ru/monica/ui/cardwallet/CardWalletContent.kt").readText()

        assertFalse(
            "Card wallet must not create its own BitwardenViewModel; BitwardenViewModel init can enqueue startup sync for unlocked vaults.",
            walletScreen.contains("val bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = viewModel()")
        )
        assertFalse(walletScreen.contains("viewModel<takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel>()"))
        assertTrue(walletScreen.contains("bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel? = null"))
        assertTrue(contentSource.contains("bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel? = null"))
        assertTrue(walletScreen.contains("affectedVaultIds.forEach(bitwardenRepository::requestLocalMutationSync)"))
    }

    @Test
    fun cardWalletEditorsDoNotCreateBitwardenViewModelForSyncNotification() {
        val editors = listOf(
            Triple("AddEditBankCardScreen", "BankCardViewModel", "saveCardAcrossTargets"),
            Triple("AddEditDocumentScreen", "DocumentViewModel", "saveDocumentAcrossTargets")
        )
        editors.forEach { (screenName, viewModelName, saveMethod) ->
            val source = projectFile("src/main/java/takagi/ru/monica/ui/screens/$screenName.kt").readText()
            val viewModelSource = projectFile("src/main/java/takagi/ru/monica/viewmodel/$viewModelName.kt").readText()
            assertFalse(
                "Wallet editors must let the owning save pipeline notify mutations without creating a separate BitwardenViewModel.",
                source.contains("bitwardenSyncViewModel")
            )
            assertFalse(source.contains("BitwardenViewModel = viewModel()"))
            assertTrue(source.contains("viewModel.$saveMethod("))
            val saveBody = viewModelSource.substringAfter("fun $saveMethod(")
            assertTrue(saveBody.contains("bitwardenVaultId = (target as? StorageTarget.Bitwarden)?.vaultId"))
            assertTrue(viewModelSource.contains("requestBitwardenMutationSync(bitwardenVaultId)"))
            assertTrue(viewModelSource.contains("vaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }"))
        }
    }

    @Test
    fun cardWalletBottomStatusUsesSelectedWalletBitwardenScopeOnly() {
        val mainScreen = projectFile("src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt").readText()
        val contentSource = projectFile("src/main/java/takagi/ru/monica/ui/cardwallet/CardWalletContent.kt").readText()

        assertTrue(mainScreen.contains("var cardWalletBitwardenVaultId"))
        assertTrue(mainScreen.contains("BottomNavItem.CardWallet -> cardWalletBitwardenVaultId != null"))
        assertTrue(mainScreen.contains("BottomNavItem.CardWallet -> cardWalletBitwardenVaultId"))
        assertFalse(
            "Card wallet must not use activeVault as its Bitwarden page context; local/MDBX/KeePass wallet scopes would show Bitwarden sync.",
            mainScreen.contains("BottomNavItem.CardWallet,\n        BottomNavItem.Notes")
        )
        assertTrue(contentSource.contains("onBitwardenScopeChanged: (Long?) -> Unit"))
        assertTrue(contentSource.contains("onBitwardenScopeChanged = state.onBitwardenScopeChanged"))
    }

    @Test
    fun cardWalletPullSyncUsesSelectedVaultInsteadOfActiveVault() {
        val walletScreen = projectFile("src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt").readText()
        val resolveSyncableVaultBody = walletScreen
            .substringAfter("suspend fun resolveSyncableVaultId(): Long?")
            .substringBefore("fun vibratePullThreshold")

        assertTrue(resolveSyncableVaultBody.contains("selectedBitwardenVaultId"))
        assertFalse(
            "Card wallet pull/manual sync must target the selected Bitwarden wallet scope, not whichever vault is globally active.",
            resolveSyncableVaultBody.contains("getActiveVault()")
        )
    }

    @Test
    fun bitwardenViewModelInitOnlyLoadsStateWithoutAutoSync() {
        val viewModelSource = projectFile("src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt").readText()
        val initBody = viewModelSource
            .substringAfter("init {")
            .substringBefore("override fun onCleared()")

        assertTrue(initBody.contains("loadVaults("))
        assertTrue(initBody.contains("triggerStartupAutoSync = false"))
        assertTrue(initBody.contains("triggerActiveVaultAutoSync = false"))
        assertFalse(
            "Creating BitwardenViewModel from non-Bitwarden pages must not enqueue Bitwarden auto sync.",
            initBody.contains("triggerStartupAutoSync = true")
        )
        assertFalse(
            "Authentication must not sync a globally active vault independently of the visible page.",
            viewModelSource.contains("fun requestStartupAutoSync(")
        )
        assertTrue(
            "Single-vault and multi-vault page sync must share cancellable sessions.",
            viewModelSource.contains("fun beginPageEnterAutoSync(") &&
                viewModelSource.contains("BitwardenPageAutoSyncScheduler(") &&
                viewModelSource.contains("fun beginAllViewAutoSync()")
        )
    }

    @Test
    fun mainActivityLeavesAutoSyncToTheVisiblePage() {
        val mainActivitySource = projectFile("src/main/java/takagi/ru/monica/MainActivity.kt").readText()
        assertFalse(
            "Authentication alone must not schedule Bitwarden sync while viewing unrelated databases.",
            mainActivitySource.contains("requestStartupAutoSync(")
        )
    }

    private fun projectFile(relativePath: String): File {
        val fromModule = File(relativePath)
        if (fromModule.exists()) return fromModule
        val fromAndroidRoot = File("app", relativePath)
        if (fromAndroidRoot.exists()) return fromAndroidRoot
        return File("Monica for Android/app", relativePath)
    }
}
