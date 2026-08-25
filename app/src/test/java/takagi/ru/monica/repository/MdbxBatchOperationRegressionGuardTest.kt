package takagi.ru.monica.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MdbxBatchOperationRegressionGuardTest {

    @Test
    fun secureItemRepositoryUsesTransactionalRoomBatchesAndSingleMdbxBatchCalls() {
        val daoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/SecureItemDao.kt"
        ).readText()
        val repositorySource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt"
        ).readText()

        assertTrue(daoSource.contains("suspend fun insertItems(items: List<SecureItem>): List<SecureItem>"))
        assertTrue(daoSource.contains("suspend fun updateItems(items: List<SecureItem>)"))
        assertTrue(daoSource.contains("suspend fun deleteItemsByIds(ids: List<Long>)"))
        assertTrue(repositorySource.contains("mdbxRepository?.upsertSecureItems(batch.items)"))
        assertTrue(repositorySource.contains("mdbxRepository?.upsertSecureItems(normalizedItems.filter"))
        assertTrue(repositorySource.contains("mdbxRepository?.deleteSecureItems(movedFromOtherMdbxDatabases)"))
    }

    @Test
    fun mixedPasswordPageRoutesSupportedMdbxItemsThroughBatchEntryPoints() {
        val mixedMoveSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveMixedSupport.kt"
        ).readText()
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()

        assertTrue(mixedMoveSource.contains("viewModel.copySecureItemsToMdbxBatch("))
        assertTrue(mixedMoveSource.contains("viewModel.moveSecureItemsToMdbxBatch("))
        assertTrue(mixedMoveSource.contains("passkeyViewModel?.updateMdbxDatabaseForPasskeys("))
        assertTrue(passwordViewModelSource.contains("secureRepository.insertItems(pendingCopies.map"))
        assertTrue(passwordViewModelSource.contains("secureRepository.insertItems(prepared.map"))
        assertTrue(passwordViewModelSource.contains("secureRepository.updateItems(prepared)"))
    }

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
