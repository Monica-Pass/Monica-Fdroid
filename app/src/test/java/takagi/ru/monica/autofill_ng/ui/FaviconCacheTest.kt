package takagi.ru.monica.autofill_ng.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaviconCacheTest {

    @Test
    fun distinguishesWebAddressesFromAppUris() {
        assertTrue(isWebAddress("linux.do"))
        assertTrue(isWebAddress("https://linux.do/login"))
        assertFalse(isWebAddress("android://com.github.android"))
        assertFalse(isWebAddress("mailto:admin@example.com"))
        assertFalse(isWebAddress(""))
    }
}
