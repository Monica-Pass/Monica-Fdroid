package takagi.ru.monica.ui.cardwallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.utils.RememberedStorageTarget
import takagi.ru.monica.utils.SavedCategoryFilterState

class CardWalletInitialStorageTargetTest {

    @Test
    fun localFilterOverridesRememberedBitwardenTarget() {
        val resolved = resolveCardWalletInitialStorageTarget(
            explicitTarget = null,
            filterState = SavedCategoryFilterState(type = "local"),
            rememberedTarget = RememberedStorageTarget(
                bitwardenVaultId = 7L,
                bitwardenFolderId = "old-folder"
            )
        )

        assertEquals(StorageTarget.MonicaLocal(null), resolved)
    }

    @Test
    fun sourceSpecificFiltersResolveTheirOwnStorageTarget() {
        val remembered = RememberedStorageTarget(bitwardenVaultId = 99L)

        assertEquals(
            StorageTarget.MonicaLocal(3L),
            resolveCardWalletInitialStorageTarget(
                explicitTarget = null,
                filterState = SavedCategoryFilterState(type = "custom", primaryId = 3L),
                rememberedTarget = remembered
            )
        )
        assertEquals(
            StorageTarget.Bitwarden(4L, "folder"),
            resolveCardWalletInitialStorageTarget(
                explicitTarget = null,
                filterState = SavedCategoryFilterState(
                    type = "bitwarden_folder",
                    primaryId = 4L,
                    text = "folder"
                ),
                rememberedTarget = remembered
            )
        )
        assertEquals(
            StorageTarget.KeePass(5L, "Root/Wallet"),
            resolveCardWalletInitialStorageTarget(
                explicitTarget = null,
                filterState = SavedCategoryFilterState(
                    type = "keepass_group",
                    primaryId = 5L,
                    text = "Root/Wallet"
                ),
                rememberedTarget = remembered
            )
        )
        assertEquals(
            StorageTarget.Mdbx(6L),
            resolveCardWalletInitialStorageTarget(
                explicitTarget = null,
                filterState = SavedCategoryFilterState(type = "mdbx_database", primaryId = 6L),
                rememberedTarget = remembered
            )
        )
    }

    @Test
    fun explicitNavigationTargetWinsAndGlobalFilterUsesRememberedTarget() {
        val remembered = RememberedStorageTarget(bitwardenVaultId = 8L)
        val explicit = StorageTarget.KeePass(2L, null)

        assertEquals(
            explicit,
            resolveCardWalletInitialStorageTarget(
                explicitTarget = explicit,
                filterState = SavedCategoryFilterState(type = "local"),
                rememberedTarget = remembered
            )
        )
        assertEquals(
            StorageTarget.Bitwarden(8L, null),
            resolveCardWalletInitialStorageTarget(
                explicitTarget = null,
                filterState = SavedCategoryFilterState(type = "all"),
                rememberedTarget = remembered
            )
        )
    }

    @Test
    fun walletEditorsReadCompletePersistentStateBeforeResolvingInitialTarget() {
        listOf(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditBankCardScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditDocumentScreen.kt"
        ).forEach { path ->
            val source = projectFile(path).readText().replace("\r\n", "\n")

            assertTrue(source.contains("resolveCardWalletInitialStorageTarget("))
            assertTrue(
                source.contains(
                    ".categoryFilterStateFlow(SettingsManager.CategoryFilterScope.CARD_WALLET)\n" +
                        "            .first()"
                )
            )
            assertTrue(source.contains(".rememberedStorageTargetFlow("))
            assertFalse(source.contains("collectAsState(initial = null as RememberedStorageTarget?)"))
        }
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
