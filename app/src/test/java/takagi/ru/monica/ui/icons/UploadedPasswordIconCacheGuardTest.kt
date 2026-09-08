package takagi.ru.monica.ui.icons

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadedPasswordIconCacheGuardTest {
    @Test
    fun uploadedIconsUseABoundedCacheAndInvalidateWhenTheFileChanges() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/icons/PasswordCustomIconSupport.kt"
        ).readText()

        assertTrue(source.contains("private object UploadedPasswordIconMemoryCache"))
        assertTrue(source.contains("private const val MAX_BYTES"))
        assertTrue(source.contains("file.lastModified()"))
        assertTrue(source.contains("UploadedPasswordIconMemoryCache.load"))
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
