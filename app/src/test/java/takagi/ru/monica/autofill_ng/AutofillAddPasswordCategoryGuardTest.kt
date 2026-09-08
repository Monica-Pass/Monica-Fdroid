package takagi.ru.monica.autofill_ng

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillAddPasswordCategoryGuardTest {

    @Test
    fun pickerAddPasswordRepositoryIncludesLocalCategories() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt"
        ).readText()
        val pickerRepositorySetup = source
            .substringAfter("val database = PasswordDatabase.getDatabase(applicationContext)")
            .substringBefore("val localKeePassDao = database.localKeePassDatabaseDao()")

        assertTrue(
            "The add-password page opened from the autofill picker must receive CategoryDao so existing local folders are listed.",
            pickerRepositorySetup.contains("categoryDao = database.categoryDao()")
        )
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
