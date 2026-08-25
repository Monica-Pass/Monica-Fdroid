package takagi.ru.monica.steam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.LOGIN_TYPE_STEAM_MAFILE
import takagi.ru.monica.data.model.isExternalSteamMaFileEntry

class SteamExternalPasswordEntryTest {
    @Test
    fun steamMaFileLoginTypeIsHiddenFromGenericPasswordFeatures() {
        val steamEntry = passwordEntry(loginType = LOGIN_TYPE_STEAM_MAFILE)
        val regularEntry = passwordEntry(loginType = "PASSWORD")

        assertTrue(steamEntry.isExternalSteamMaFileEntry())
        assertFalse(regularEntry.isExternalSteamMaFileEntry())
    }

    @Test
    fun steamMaFileLoginTypeMatchingIgnoresCaseAndWhitespace() {
        assertTrue(passwordEntry(loginType = "  steam_mafile  ").isExternalSteamMaFileEntry())
    }

    private fun passwordEntry(loginType: String) = PasswordEntry(
        title = "Steam",
        website = "https://steamcommunity.com",
        username = "account",
        password = "",
        loginType = loginType
    )
}
