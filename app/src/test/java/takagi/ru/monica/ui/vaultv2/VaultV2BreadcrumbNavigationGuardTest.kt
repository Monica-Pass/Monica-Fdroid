package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2BreadcrumbNavigationGuardTest {

    @Test
    fun `vault breadcrumbs navigate directly instead of opening the category picker`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val sharedBreadcrumbs = source("ui/password/PasswordQuickFolderSections.kt")
        val statusBar = pane
            .substringAfter("private fun VaultV2QuickStatusBar(")
            .substringBefore("private fun VaultV2QuickStatusIndicator(")
        val breadcrumbPath = pane
            .substringAfter("private fun VaultV2BreadcrumbPath(")
            .substringBefore("private fun VaultV2ItemCard(")

        assertTrue(statusBar.contains("currentFilter: CategoryFilter"))
        assertTrue(statusBar.contains("onNavigateFilter: (CategoryFilter) -> Unit"))
        assertTrue(breadcrumbPath.contains("currentFilter: CategoryFilter"))
        assertTrue(breadcrumbPath.contains("onNavigateFilter: (CategoryFilter) -> Unit"))
        assertFalse(breadcrumbPath.contains("clickable(onClick = onOpenStorageFilter)"))
        assertTrue(breadcrumbPath.contains("PasswordQuickFolderBreadcrumbPath("))
        assertTrue(sharedBreadcrumbs.contains("internal fun PasswordQuickFolderBreadcrumbPath("))
        assertTrue(sharedBreadcrumbs.contains("clickable(enabled = !crumb.isCurrent)"))
        assertTrue(sharedBreadcrumbs.contains("onNavigate(crumb.targetFilter)"))
        assertTrue(pane.contains("IconButton(onClick = { isStorageFilterSheetVisible = true })"))
    }

    @Test
    fun `vault status bar uses the same filter navigation path as quick folder chips`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val call = pane
            .substringAfter("VaultV2QuickStatusBar(")
            .substringBefore("val contentPullOffset")

        assertTrue(call.contains("currentFilter = categoryMenuFilter"))
        assertTrue(call.contains("onNavigateFilter = navigateCategoryFilter"))
        assertTrue(pane.contains("onNavigateFilter = navigateCategoryFilter"))
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/$relativePath"
        ).readText()
    }
}
