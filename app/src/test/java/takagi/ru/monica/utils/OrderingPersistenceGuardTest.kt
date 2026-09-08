package takagi.ru.monica.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.ui.cardwallet.mergeVisibleWalletOrder
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.ui.mergeVisibleQuickFilterOrder

class OrderingPersistenceGuardTest {

    @Test
    fun cardWalletUsesManualOrderBeforeUpdateTimeAndPersistsOneBatch() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt"
        ).readText()
        val orderingBlock = source
            .substringAfter("val allWalletItems = remember(walletItems)")
            .substringBefore("val nestedScrollConnection")
        val dragBlock = source
            .substringAfter("var walletWasDragging")
            .substringBefore("LazyColumn(")

        assertTrue(orderingBlock.indexOf("thenBy { it.item.sortOrder }") >= 0)
        assertTrue(
            "Manual order must win over update time or a dragged card immediately jumps back.",
            orderingBlock.indexOf("thenBy { it.item.sortOrder }") <
                orderingBlock.indexOf("thenByDescending { it.item.updatedAt.time }")
        )
        assertTrue(dragBlock.contains("mergeVisibleWalletOrder("))
        assertTrue(dragBlock.contains("bankCardViewModel.updateSortOrders(newOrders)"))
        assertFalse(
            "ALL view must not split one drag into competing per-type Room emissions.",
            dragBlock.contains("docOrders") || dragBlock.contains("addressOrders")
        )
    }

    @Test
    fun filteredWalletReorderKeepsHiddenItemsInTheirSlots() {
        assertEquals(
            listOf(1L, 4L, 3L, 2L, 5L),
            mergeVisibleWalletOrder(
                allItemIds = listOf(1L, 2L, 3L, 4L, 5L),
                reorderedVisibleItemIds = listOf(4L, 2L)
            )
        )
    }

    @Test
    fun mdbxPersistsAndRestoresManualOrder() {
        val mdbx2 = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt"
        ).readText()
        val legacyMdbx = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readText()
        val importSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val secureRepository = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt"
        ).readText()

        assertTrue(mdbx2.countOccurrences(".put(\"sort_order\",") >= 2)
        assertTrue(legacyMdbx.countOccurrences(".put(\"sort_order\",") >= 2)
        assertTrue(importSource.countOccurrences("payload.has(\"sort_order\")") >= 2)
        assertTrue(secureRepository.contains("mirrorSortOrderItems(reorderedItems)"))
        assertTrue(secureRepository.contains("catch (error: MdbxVaultNotFoundException)"))
        assertFalse(secureRepository.contains("runCatching { repository.upsertSecureItems(group) }"))
    }

    @Test
    fun quickFilterGridReordersOnlyVisibleItemsWithoutLosingHiddenSettings() {
        val favorite = PasswordListQuickFilterItem.FAVORITE
        val twoFa = PasswordListQuickFilterItem.TWO_FA
        val hiddenCardWallet = PasswordListQuickFilterItem.CARD_WALLET
        val notes = PasswordListQuickFilterItem.NOTES

        assertEquals(
            listOf(notes, twoFa, hiddenCardWallet, favorite),
            mergeVisibleQuickFilterOrder(
                fullOrder = listOf(favorite, twoFa, hiddenCardWallet, notes),
                reorderedVisibleItems = listOf(notes, twoFa, favorite)
            )
        )

        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFilterEditGrid.kt"
        ).readText()
        assertTrue(source.contains("GridCells.Fixed(2)"))
        assertTrue(source.contains("shouldShowQuickFilterItem("))
        assertFalse(source.contains("GridCells.Adaptive"))

        val chipsSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFilterChips.kt"
        ).readText()
        assertTrue(
            chipsSource.contains(
                "if (item == PasswordListQuickFilterItem.AUTHENTICATOR) return false"
            )
        )
    }

    @Test
    fun staleMdbxSortReferencesAreNarrowlyHandledForPasswordsAndSecureItems() {
        val router = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxRepositoryRouter.kt"
        ).readText()
        val passwordRepository = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val secureRepository = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt"
        ).readText()

        assertTrue(router.contains("throw MdbxVaultNotFoundException(databaseId)"))
        assertTrue(passwordRepository.contains("catch (error: MdbxVaultNotFoundException)"))
        assertTrue(secureRepository.contains("catch (error: MdbxVaultNotFoundException)"))
        assertFalse(passwordRepository.contains("runCatching { repository.upsertPasswords(group) }"))
        assertFalse(secureRepository.contains("runCatching { repository.upsertSecureItems(group) }"))
    }

    @Test
    fun keepassCredentialFieldsUseTheSharedMultilingualKeyboardPolicy() {
        val policy = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassCredentialKeyboardOptions.kt"
        ).readText()
        val entryPoints = listOf(
            "LocalKeePassScreen.kt",
            "KeePassNativeDatabaseSettingsScreen.kt",
            "LocalKeePassGoogleDriveBrowser.kt",
            "LocalKeePassOneDriveBrowser.kt",
            "LocalKeePassWebDavBrowser.kt"
        ).map { name ->
            projectFile("app/src/main/java/takagi/ru/monica/ui/screens/$name").readText()
        }

        assertTrue(policy.contains("keyboardType = KeyboardType.Text"))
        assertTrue(policy.contains("autoCorrectEnabled = false"))
        assertTrue(policy.contains("capitalization = KeyboardCapitalization.None"))
        assertTrue(entryPoints.all { it.contains("keepassCredentialKeyboardOptions()") })
    }

    @Test
    fun fullBackupRoundTripsManualOrderWithLegacyDefault() {
        val webDav = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readText()
        val exportModel = projectFile(
            "app/src/main/java/takagi/ru/monica/util/DataExportImportManager.kt"
        ).readText()
        val restoreApplier = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/BackupRestoreApplier.kt"
        ).readText()

        assertTrue(webDav.countOccurrences("val sortOrder: Int = 0") >= 4)
        assertTrue(webDav.contains("sortOrder = password.sortOrder"))
        assertTrue(webDav.countOccurrences("sortOrder = item.sortOrder") >= 3)
        assertTrue(webDav.countOccurrences("sortOrder = longField(\"sortOrder\").toInt()") >= 3)
        assertTrue(exportModel.contains("val sortOrder: Int = 0"))
        assertTrue(restoreApplier.contains("sortOrder = exportItem.sortOrder"))
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length, step = 1).count { it == needle }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to find project file: $relativePath")
    }
}
