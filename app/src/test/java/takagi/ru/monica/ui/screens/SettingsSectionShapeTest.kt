package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSectionShapeTest {
    @Test
    fun `single item uses large corners on every edge`() {
        assertEquals(
            SettingsSectionCornerRadii(top = 24, bottom = 24),
            settingsSectionCornerRadii(index = 0, totalItems = 1)
        )
    }

    @Test
    fun `group items use PixelPlayer outer and adjacent corner radii`() {
        assertEquals(
            SettingsSectionCornerRadii(top = 24, bottom = 4),
            settingsSectionCornerRadii(index = 0, totalItems = 3)
        )
        assertEquals(
            SettingsSectionCornerRadii(top = 4, bottom = 4),
            settingsSectionCornerRadii(index = 1, totalItems = 3)
        )
        assertEquals(
            SettingsSectionCornerRadii(top = 4, bottom = 24),
            settingsSectionCornerRadii(index = 2, totalItems = 3)
        )
    }
}
