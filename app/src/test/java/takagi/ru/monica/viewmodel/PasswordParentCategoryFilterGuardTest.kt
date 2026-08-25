package takagi.ru.monica.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordParentCategoryFilterGuardTest {

    @Test
    fun `password page and vault use direct category scope without a setting`() {
        val root = locateProjectRoot()
        val passwordViewModel = File(
            root,
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()
        val aggregateContent = File(
            root,
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordAggregateListContent.kt"
        ).readText()
        val vaultPane = File(
            root,
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()

        val passwordPage = File(
            root,
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt"
        ).readText()
        assertTrue(aggregateContent.contains("localCategoryIdsInScope"))
        assertTrue(vaultPane.contains("localCategoryIdsInScope"))
        assertFalse(passwordPage.contains("categoryScopedPasswordEntries"))
        assertFalse(passwordViewModel.contains("passwordParentCategoryIncludesChildren"))
        assertFalse(passwordPage.contains("passwordParentCategoryIncludesChildren"))
        assertTrue(passwordViewModel.contains("categoryId?.let(::setOf).orEmpty()"))
        assertTrue(vaultPane.contains("includeDescendants = false"))
    }

    private fun locateProjectRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(current, "app/src/main/java").isDirectory) return current
            current = current.parentFile ?: error("Reached filesystem root while locating project")
        }
        error("Unable to locate Android project root from ${System.getProperty("user.dir")}")
    }
}
