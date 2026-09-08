package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2FirstFramePipelineGuardTest {

    @Test
    fun `display model keeps immediate merge and filtering in one asynchronous stage`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val asyncListStages = Regex(
            "val\\s+\\w+\\s*=\\s*rememberVaultV2AsyncComputedValue\\("
        ).findAll(pane).count()

        assertEquals(2, asyncListStages)
        val displayBlock = pane
            .substringAfter("val displayListStateAsync =")
            .substringBefore("val displayedListState")

        assertTrue(displayBlock.contains("buildVaultV2DisplayListStateFromItems("))
        assertTrue(pane.contains("buildVaultV2InitialDisplayListStateFromItems("))
        assertTrue(pane.contains("lazy(LazyThreadSafetyMode.SYNCHRONIZED)"))
        assertTrue(pane.contains("currentPasswordItems = currentPasswordItems.value"))
        assertFalse(pane.contains("val visibleListState = remember("))
    }

    @Test
    fun `independent secondary item types are parsed concurrently`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val computedBlock = pane
            .substringAfter("val computedListStateAsync =")
            .substringBefore("val computedListState =")

        assertTrue(computedBlock.contains("val secondaryLists = coroutineScope"))
        assertTrue(computedBlock.indexOf("val visiblePasswordIds =") < computedBlock.indexOf("coroutineScope"))
        assertTrue(Regex("async\\(Dispatchers\\.Default\\)").findAll(computedBlock).count() >= 5)
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
