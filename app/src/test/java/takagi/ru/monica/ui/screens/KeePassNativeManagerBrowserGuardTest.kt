package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeManagerBrowserGuardTest {

    @Test
    fun `browser keeps search collapsed and selection actions in the shared page model`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeManagerScreen.kt",
        ).readText()
        val browser = source
            .substringAfter("private fun NativeBrowserPage(")
            .substringBefore("@OptIn(ExperimentalLayoutApi::class)")

        assertTrue(browser.contains("searchExpanded: Boolean"))
        assertTrue(browser.contains("shouldShowNativeManagerSearch"))
        assertTrue(browser.contains("onToggleSelectAll"))
        assertTrue(browser.contains("BoxWithConstraints"))
        assertTrue(browser.contains("NativeManagerSummaryRow"))
        assertFalse(browser.contains("OutlinedCard("))
    }

    @Test
    fun `new folder reuses the complete group property editor`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeManagerScreen.kt",
        ).readText()

        assertTrue(source.contains("NativeCreateGroupDialog("))
        assertTrue(source.contains("expires = expires"))
        assertTrue(source.contains("expiryTime = expiryTime"))
        assertTrue(source.contains("tags = parsedTags"))
        assertTrue(source.contains("enableSearching = searching"))
    }

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
