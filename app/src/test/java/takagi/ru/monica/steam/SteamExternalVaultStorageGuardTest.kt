package takagi.ru.monica.steam

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamExternalVaultStorageGuardTest {
    @Test
    fun storageSourcesAndPreferencesIncludeKeePassAndBitwarden() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamStorageSource.kt"
        ).readText()
        val preferences = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamQrAccountPreference.kt"
        ).readText()

        assertTrue(source.contains("data class KeePass"))
        assertTrue(source.contains("data class Bitwarden"))
        assertTrue(preferences.contains("STORAGE_SOURCE_KEEPASS"))
        assertTrue(preferences.contains("STORAGE_SOURCE_BITWARDEN"))
    }

    @Test
    fun externalStoresUseMarkerAndRealMaFileAttachments() {
        val keepass = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamKeePassAccountStore.kt"
        ).readText()
        val bitwarden = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/data/SteamBitwardenAccountStore.kt"
        ).readText()

        assertTrue(keepass.contains("SteamExternalMaFileContract.MARKER_FIELD"))
        assertTrue(keepass.contains("addAttachmentToEntry("))
        assertTrue(keepass.contains("memoryProtection = true"))
        assertTrue(bitwarden.contains("syncForUserVisibleRequest("))
        assertTrue(bitwarden.contains("refreshRemote: Boolean = false"))
        assertTrue(bitwarden.contains("addInlineAttachment("))
        assertTrue(bitwarden.contains("AttachmentSource.BITWARDEN"))
        assertTrue(bitwarden.contains("SteamExternalMaFileContract.candidateFileNames"))
        assertTrue(bitwarden.contains("LOGIN_TYPE_STEAM_MAFILE"))
        assertTrue(bitwarden.contains("if (!hasMarker && !entry.isExternalSteamMaFileEntry())"))
        assertTrue(bitwarden.contains("fetchAttachmentCipherSnapshot("))
        assertTrue(bitwarden.contains("reconcileBitwardenAttachments("))
        val bitwardenUpsertBody = bitwarden
            .substringAfter("suspend fun upsertAccount(")
            .substringBefore("suspend fun deleteAccount(")
        assertTrue(bitwardenUpsertBody.contains("PENDING_MARKER_VALUE"))
        assertTrue(bitwardenUpsertBody.contains("fetchAttachmentCipherSnapshot("))
        assertTrue(bitwardenUpsertBody.contains("requireSteamBitwardenSyncSuccess("))
        assertFalse(bitwardenUpsertBody.contains("getAttachmentBitwardenContext("))
        assertFalse(bitwardenUpsertBody.contains("requestLocalMutationSync("))
        val repository = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/repository/BitwardenRepository.kt"
        ).readText()
        assertTrue(repository.contains("BitwardenCipherKeyResolver.resolveCipherKey("))
        assertTrue(repository.contains("BitwardenAttachmentMetadataDecoder.decodeForStorage("))
        val attachmentExecutor = projectFile(
            "app/src/main/java/takagi/ru/monica/attachments/executor/BitwardenAttachmentExecutor.kt"
        ).readText()
        val downloadBody = attachmentExecutor
            .substringAfter("suspend fun download(")
            .substringBefore("suspend fun remove(")
        assertTrue(downloadBody.indexOf("getAttachmentDownload(") < downloadBody.indexOf("remote.url"))
        assertTrue(bitwarden.contains("bitwardenRepository.isVaultPremium(vaultId)"))
        assertFalse(bitwarden.contains("bitwardenPremium = true"))
        assertTrue(keepass.contains("SteamExternalMaFileContract.MAX_MAFILE_BYTES"))
        val previousAttachmentsBody = keepass
            .substringAfter("val previousAttachments")
            .substringBefore("val entryWrite")
        assertTrue(previousAttachmentsBody.contains("readEntryAttachments(databaseId, resolvedUuid)"))
        assertTrue(previousAttachmentsBody.contains(".getOrThrow()"))
        assertTrue(previousAttachmentsBody.contains(".filter { SteamExternalMaFileContract.isMaFile"))
        assertFalse(keepass.contains(".getOrElse { emptyList() }"))
        assertTrue(keepass.contains("SteamExternalMaFileContract.candidateFileNames"))

        val passwordRepository = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt"
        ).readText()
        val cipherSync = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt"
        ).readText()
        val passwordViewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()

        assertTrue(passwordRepository.contains("withoutExternalSteamMaFileEntries"))
        assertTrue(cipherSync.contains("SteamExternalMaFileContract.isMarked"))
        assertTrue(passwordViewModel.contains("SteamExternalMaFileContract.isMarked"))
    }

    @Test
    fun steamUiExposesExternalSourcesAndRoutesMutations() {
        val screen = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamViewModel.kt"
        ).readText()

        assertTrue(screen.contains("SteamStorageSource.KeePass(database.id)"))
        assertTrue(screen.contains("SteamStorageSource.Bitwarden(vault.id)"))
        assertTrue(viewModel.contains("reloadKeePassAccounts("))
        assertTrue(viewModel.contains("reloadBitwardenAccounts("))
        assertTrue(viewModel.contains("loadAccounts(source.vaultId, refreshRemote = true)"))
        assertTrue(viewModel.contains("keepassAccountStore?.deleteAccount("))
        assertTrue(viewModel.contains("bitwardenAccountStore?.deleteAccount("))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
