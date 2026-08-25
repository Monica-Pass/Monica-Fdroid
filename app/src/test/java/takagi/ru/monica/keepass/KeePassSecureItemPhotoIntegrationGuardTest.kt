package takagi.ru.monica.keepass

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassSecureItemPhotoIntegrationGuardTest {
    @Test
    fun secureItemWritesLoadEncryptedPhotosAndAppendAttachmentChangeSets() {
        val service = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()

        assertTrue(service.contains("buildSecureItemPhotoUpdates(item)"))
        assertTrue(service.contains("KeePassSecureItemPhotoAttachments.synchronize("))
        assertTrue(service.contains("buildSecureItemPhotoChangeSets("))
        assertTrue(service.contains("imageManager.readImageBytes("))
        assertTrue(service.contains("KeePassChangeTarget.SECURE_ITEM"))
    }

    @Test
    fun secureItemReadsHydrateManagedBinariesIntoEncryptedLocalImagePaths() {
        val service = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()

        assertTrue(service.contains("hydrateSecureItemImagePaths("))
        assertTrue(service.contains("KeePassSecureItemPhotoAttachments.readManagedPhotos("))
        assertTrue(service.contains("imageManager.saveImageBytes("))
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
