package takagi.ru.monica.utils

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.BottomNavContentTab

/** The device runner seeds the real settings file before each cold process launch. */
class RetiredSettingsStartupTest {
    @Test fun coldStartReadsMigratedSettingsAndPreservesUnrelatedPreferences() = runBlocking {
        val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext).settingsFlow.first()
        assertEquals(BottomNavContentTab.NOTES, settings.bottomNavOrder.first())
        assertTrue(settings.hideFabOnScroll)
    }
}
