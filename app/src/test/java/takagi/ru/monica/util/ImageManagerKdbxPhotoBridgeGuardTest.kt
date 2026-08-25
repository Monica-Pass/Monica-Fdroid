package takagi.ru.monica.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageManagerKdbxPhotoBridgeGuardTest {
    @Test
    fun kdbxPhotoBridgeReadsAndWritesOnlyEncryptedValidatedImageBytes() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/util/ImageManager.kt"
        ).readText()

        assertTrue(source.contains("suspend fun readImageBytes("))
        assertTrue(source.contains("suspend fun saveImageBytes("))
        assertTrue(source.contains("MAX_IMPORTED_IMAGE_BYTES"))
        assertTrue(source.contains("inJustDecodeBounds = true"))
        assertTrue(source.contains("val encryptedData = encrypt(bytes)"))
        assertTrue(source.contains("decrypt(file.readBytes())"))
        assertFalse(source.contains("FileOutputStream(file).use { it.write(bytes) }"))
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
