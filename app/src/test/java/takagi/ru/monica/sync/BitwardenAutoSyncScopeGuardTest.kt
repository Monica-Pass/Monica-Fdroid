package takagi.ru.monica.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwardenAutoSyncScopeGuardTest {

    @Test
    fun authenticationDoesNotStartAnUnscopedBitwardenSync() {
        val source = projectFile(
            "src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt"
        ).readText()
        val mainActivity = projectFile("src/main/java/takagi/ru/monica/MainActivity.kt").readText()
        assertFalse(mainActivity.contains("requestStartupAutoSync("))
        assertFalse(source.contains("fun requestStartupAutoSync("))
    }

    @Test
    fun selectedVaultEntryUsesACancellablePageSessionWithoutAnActiveVaultFallback() {
        val source = projectFile(
            "src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt"
        ).readText()
        val body = source
            .substringAfter("fun beginPageEnterAutoSync(")
            .substringBefore("fun beginAllViewAutoSync(")

        assertTrue(body.contains("pageAutoSyncScheduler.begin("))
        assertTrue(body.contains("initialDelayMs = pageAutoSyncDelayMs()"))
        assertTrue(body.contains("SyncTriggerReason.PAGE_ENTER"))
        assertFalse(body.contains("_activeVault"))
        assertFalse(body.contains("requestAutoSyncWithStartupGrace"))
        val effect = projectFile("src/main/java/takagi/ru/monica/bitwarden/ui/BitwardenAutoSyncEffect.kt").readText()
        assertTrue(effect.contains("viewModel?.beginPageEnterAutoSync(selectedVaultId)"))
        assertTrue(effect.contains("viewModel?.endPageAutoSync(sessionId)"))
    }

    @Test
    fun allViewBatchUsesBackgroundPeriodicReason() {
        val source = projectFile(
            "src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt"
        ).readText()
        val schedulerBody = source
            .substringAfter("fun beginAllViewAutoSync(")
            .substringBefore("fun endPageAutoSync(")

        assertTrue(schedulerBody.contains("SyncTriggerReason.PERIODIC"))
        assertFalse(schedulerBody.contains("SyncTriggerReason.MANUAL"))
    }

    @Test
    fun allCapableListPagesUseExplicitAllViewScopeInsteadOfNullableVaultInference() {
        val expectedSources = mapOf(
            "src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt" to "isAllView = isAllView",
            "src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt" to "TotpCategoryFilter.All",
            "src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt" to "UnifiedCategoryFilterSelection.All",
            "src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt" to "NoteCategoryFilter.All",
            "src/main/java/takagi/ru/monica/ui/screens/PasskeyListScreen.kt" to "UnifiedCategoryFilterSelection.All",
            "src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt" to "UnifiedCategoryFilterSelection.All"
        )

        expectedSources.forEach { (path, allMarker) ->
            val source = projectFile(path).readText()
            assertTrue("$path must install the shared Bitwarden auto-sync scope effect.", source.contains("BitwardenAutoSyncEffect("))
            assertTrue("$path must identify ALL explicitly.", source.contains(allMarker))
        }
    }

    @Test
    fun localMutationKeepsTheOwningVaultId() {
        val source = projectFile(
            "src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt"
        ).readText()
        val bridgeBody = source.substringAfter("BitwardenMutationSyncBridge.register(this)")
            .substringBefore("observeVaultSnapshots()")
        val mutationBody = source.substringAfter("fun requestLocalMutationSync(")
            .substringBefore("fun requestManualSync(")

        assertTrue(bridgeBody.contains("vaultId = vaultId"))
        assertTrue(bridgeBody.contains("SyncTriggerReason.LOCAL_MUTATION"))
        assertTrue(mutationBody.contains("val targetVaultId = vaultId"))
        assertTrue(mutationBody.contains("vaultId = targetVaultId"))
    }

    private fun projectFile(relativePath: String): File {
        val fromModule = File(relativePath)
        if (fromModule.exists()) return fromModule
        val fromAndroidRoot = File("app", relativePath)
        if (fromAndroidRoot.exists()) return fromAndroidRoot
        return File("Monica for Android/app", relativePath)
    }
}
