package takagi.ru.monica.attachments.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentOwnerMigrationGuardTest {
    @Test
    fun attachmentSchemaSupportsPasswordAndSecureItemOwners() {
        val model = projectFile(
            "app/src/main/java/takagi/ru/monica/attachments/model/Attachment.kt"
        ).readText()
        val database = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt"
        ).readText()

        assertTrue(model.contains("parentSecureItemId"))
        assertTrue(model.contains("entity = SecureItem::class"))
        assertTrue(model.contains("AttachmentOwner"))
        assertTrue(database.contains("version = 77"))
        assertTrue(database.contains("MIGRATION_76_77"))
        assertTrue(database.contains("parent_secure_item_id"))
        assertTrue(database.contains("REFERENCES secure_items(id) ON DELETE CASCADE"))
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
