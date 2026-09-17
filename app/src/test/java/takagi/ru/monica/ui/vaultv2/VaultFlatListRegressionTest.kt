package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class VaultFlatListRegressionTest {
    @Test fun equivalentAndDistinctPasswordsRemainSeparateRows() {
        val passwords = (1L..3L).map { id ->
            PasswordEntry(id = id, title = "Account", username = if (id < 3) "same" else "other",
                password = "fixture", website = "https://example.test")
        }
        val items = buildVaultV2PasswordItems(passwords)
        val sections = buildVaultV2Sections(items)
        val rows = sections.flatMap { it.second }
        assertEquals(items.map { it.key }, rows.map { it.key })
        assertTrue(rows.all { it.stackedItems.isEmpty() })
        assertEquals(3, buildVaultV2SectionLayouts(sections, 0).sumOf { it.items.size })
    }

    @Test fun vaultPageCannotRouteThroughPasswordStackSettingsOrRenderers() {
        val path = "src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        val source = listOf(File("app/$path"), File(path)).first { it.exists() }.readText()
        val sections = source.substringAfter("val sectionedItems = remember(")
            .substringBefore("val showQuickFiltersInList")
        assertTrue(sections.contains("buildVaultV2Sections(filteredItems)"))
        assertFalse(sections.contains("stackCardMode"))
        assertFalse(source.contains("buildVaultV2StackedSections("))
        assertFalse(source.contains("VaultV2PasswordStackCard("))
    }
}
