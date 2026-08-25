package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2FilterRestoreGuardTest {

    @Test
    fun `persisted filter is loaded before vault state is initialized`() {
        val source = File(
            locateProjectRoot(),
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt",
        ).readText()

        assertTrue(source.contains("collectAsState(initial = null)"))
        assertTrue(source.contains("val persistedFilter = savedCategoryFilterState ?: return@LaunchedEffect"))
        assertTrue(source.contains("persistedFilter.toVaultV2SavedStorageFilter()"))
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
