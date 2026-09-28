package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.VaultListSort
import takagi.ru.monica.utils.SettingsManager

class VaultV2SortSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun largeTextDarkSheetScrollsToEveryOptionAndExposesSelection() {
        var chosen: VaultListSort? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    VaultV2SortSheet(VaultListSort.CREATED_DESC, { chosen = it }, {})
                }
            }
        }
        compose.onNodeWithTag("vault_sort_CREATED_DESC").performScrollTo().assertIsSelected()
        File(context.filesDir, "vault-sort-dark-large-text.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        VaultListSort.entries.forEach { mode ->
            compose.onNodeWithTag("vault_sort_${mode.name}").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(mode, chosen) }
        }
    }

    @Test fun sortSurvivesSettingsRecreationAndSettingsExportRestore() = runBlocking {
        val manager = SettingsManager(context)
        val original = manager.exportPageAdjustmentSettings()
        try {
            VaultListSort.entries.forEach { mode ->
                manager.updateVaultListSort(mode)
                val fresh = SettingsManager(context)
                val saved = fresh.exportPageAdjustmentSettings()
                assertEquals(mode, withTimeout(5_000) { fresh.settingsFlow.first { it.vaultListSort == mode }.vaultListSort })
                assertEquals(mode.name, saved.vaultListSort)
                manager.updateVaultListSort(VaultListSort.TITLE_ASC)
                manager.importPageAdjustmentSettings(saved)
                assertEquals(mode, withTimeout(5_000) { manager.settingsFlow.first { it.vaultListSort == mode }.vaultListSort })
            }
            manager.importPageAdjustmentSettings(original.copy(vaultListSort = "future-sort"))
            assertEquals(VaultListSort.TITLE_ASC, withTimeout(5_000) { manager.settingsFlow.first { it.vaultListSort == VaultListSort.TITLE_ASC }.vaultListSort })
        } finally {
            manager.importPageAdjustmentSettings(original)
        }
    }
}
