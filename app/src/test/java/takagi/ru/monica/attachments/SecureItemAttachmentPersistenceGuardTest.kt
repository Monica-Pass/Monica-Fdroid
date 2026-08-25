package takagi.ru.monica.attachments

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureItemAttachmentPersistenceGuardTest {
    @Test
    fun secureItemAttachmentsAreConnectedToEveryExistingPersistenceBackend() {
        val bitwardenSync = source(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenSyncService.kt"
        )
        val mdbxImport = source(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        )
        val facade = source(
            "app/src/main/java/takagi/ru/monica/attachments/facade/AttachmentFacade.kt"
        )
        val portableBackup = source(
            "app/src/main/java/takagi/ru/monica/attachments/backup/PortableAttachmentBackup.kt"
        )
        val restore = source(
            "app/src/main/java/takagi/ru/monica/utils/BackupRestoreApplier.kt"
        )
        val secureRepository = source(
            "app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt"
        )
        val facadeSource = source(
            "app/src/main/java/takagi/ru/monica/attachments/facade/AttachmentFacade.kt"
        )
        val secureItemCopies = listOf(
            source("app/src/main/java/takagi/ru/monica/viewmodel/BankCardViewModel.kt"),
            source("app/src/main/java/takagi/ru/monica/viewmodel/DocumentViewModel.kt"),
            source("app/src/main/java/takagi/ru/monica/viewmodel/NoteViewModel.kt")
        )

        assertTrue(bitwardenSync.contains("AttachmentOwner.secureItem"))
        assertTrue(mdbxImport.contains("importedSecureItemIds"))
        assertTrue(mdbxImport.contains("parentSecureItemId = owner.secureItemId"))
        assertTrue(facade.contains("mdbxSecureItemObjectId"))
        assertTrue(facade.contains("secureItemDao?.getItemById"))
        assertTrue(portableBackup.contains("parentSecureItemId"))
        assertTrue(restore.contains("restoreSecureItemAttachments"))
        assertTrue(secureRepository.contains("attachmentRepository?.softDelete"))
        assertTrue(secureRepository.contains("attachmentRepository?.restore"))
        assertTrue(facadeSource.contains("cloneAttachmentsToNewOwner"))
        secureItemCopies.forEach { source ->
            assertTrue(source.contains("cloneAttachmentsToNewOwner"))
        }
    }

    private fun source(relativePath: String): String = projectFile(relativePath).readText()

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
