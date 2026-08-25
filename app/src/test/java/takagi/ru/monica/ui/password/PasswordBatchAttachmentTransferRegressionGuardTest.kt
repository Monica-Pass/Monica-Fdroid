package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordBatchAttachmentTransferRegressionGuardTest {

    @Test
    fun preparedAttachmentCountsRemainAddressableBySourcePassword() {
        val prepared = PreparedPasswordBatchAttachments(
            countsByPasswordId = mapOf(10L to 2, 20L to 1)
        )

        assertEquals(3, prepared.totalAttachmentCount)
        assertEquals(2, prepared.countFor(10L))
        assertEquals(0, prepared.countFor(30L))
    }

    @Test
    fun batchTransferPreparesRemoteBytesAndCompletesEverySupportedTarget() {
        val facadeSource = projectFile(
            "app/src/main/java/takagi/ru/monica/attachments/facade/AttachmentFacade.kt"
        ).readText()
        val batchSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveSupport.kt"
        ).readText()
        val helperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchAttachmentTransferSupport.kt"
        ).readText()

        assertTrue(facadeSource.contains("suspend fun ensureAttachmentsReadyForTransfer("))
        assertTrue(facadeSource.contains("suspend fun copyAttachmentsToBitwardenEntry("))
        assertTrue(facadeSource.contains("suspend fun relocateMdbxAttachments("))
        assertTrue(facadeSource.contains("mirrorAttachmentToMdbx(saved, requireSuccess = true)"))
        assertTrue(batchSource.contains("preparePasswordBatchAttachments("))
        assertTrue(batchSource.contains("completePasswordBatchBitwardenAttachments("))
        assertTrue(batchSource.contains("completePasswordBatchLocalOrKeePassAttachmentCopies("))
        assertTrue(helperSource.contains("facade.copyAttachmentsToKeePassEntry("))
        assertTrue(helperSource.contains("facade.cloneAttachmentsToNewParent(sourceId, targetId)"))
    }

    @Test
    fun movingPasswordsKeepsAttachmentsBeforeRemovingRemoteSources() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()

        assertTrue(viewModelSource.contains("facade.relocateMdbxAttachments(sourceEntry, targetEntry)"))
        assertTrue(viewModelSource.contains("suspend fun movePasswordsToMdbxFoldersAwait("))
        assertTrue(viewModelSource.contains("facade.cloneAttachmentsToNewParent(entry.id, newId)"))
        assertTrue(viewModelSource.contains("facade.purgeByPassword(entry.id)"))
        assertTrue(viewModelSource.contains("attachmentRepository.convertSourceToLocal("))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            candidates += File(directory, relativePath)
            directory = directory.parentFile
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to find project file: $relativePath")
    }
}
