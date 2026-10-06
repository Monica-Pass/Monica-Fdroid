package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import takagi.ru.monica.data.MdbxTigaMode

class MdbxTigaModeAvailabilityTest {
    private val ordinary = listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER)

    @Test fun creationOffersOnlyTheThreeClientModes() {
        assertEquals(ordinary, mdbxTigaModesForCreation())
        ordinary.forEach { requireMdbxTigaCreationMode(it) }
    }

    @Test fun remoteGlitterIsUnavailableWithoutChangingTheOriginalModes() {
        assertEquals(ordinary, mdbxTigaModesForCreation())
        ordinary.forEach { requireMdbxTigaCreationMode(it) }
        assertThrows(IllegalArgumentException::class.java) {
            requireMdbxTigaCreationMode(MdbxTigaMode.GLITTER)
        }
    }
}
