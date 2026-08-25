package takagi.ru.monica.attachments

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentOwnerApiGuardTest {
    @Test
    fun attachmentPipelineUsesExplicitOwnerAcrossCoreLayers() {
        val files = listOf(
            "app/src/main/java/takagi/ru/monica/attachments/repository/AttachmentRepository.kt",
            "app/src/main/java/takagi/ru/monica/attachments/facade/AttachmentFacade.kt",
            "app/src/main/java/takagi/ru/monica/attachments/executor/LocalAttachmentExecutor.kt",
            "app/src/main/java/takagi/ru/monica/attachments/executor/BitwardenAttachmentExecutor.kt",
            "app/src/main/java/takagi/ru/monica/attachments/executor/KeePassAttachmentExecutor.kt",
            "app/src/main/java/takagi/ru/monica/attachments/executor/BitwardenAttachmentReconciler.kt",
            "app/src/main/java/takagi/ru/monica/attachments/executor/KeePassAttachmentReconciler.kt"
        )

        files.forEach { relativePath ->
            val source = projectFile(relativePath).readText()
            assertTrue("$relativePath must use AttachmentOwner", source.contains("AttachmentOwner"))
        }
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
