package takagi.ru.monica.steam.token.identity.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SteamIdentityConverterTest {
    @Test
    fun convertsSteamId64ToCommonIdentityFormats() {
        val formats = SteamIdentityConverter.fromSteamId64("76561197960287930")

        assertNotNull(formats)
        assertEquals("76561197960287930", formats?.steamId64)
        assertEquals("[U:1:22202]", formats?.steamId3)
        assertEquals("STEAM_0:0:11101", formats?.steamId2)
        assertEquals("22202", formats?.accountId)
        assertEquals(
            "https://steamcommunity.com/profiles/76561197960287930/",
            formats?.communityProfileUrl,
        )
    }

    @Test
    fun preservesSteamId2ParityAndUnsignedAccountIdRange() {
        val odd = SteamIdentityConverter.fromSteamId64("76561197960265729")
        val upperBoundary = SteamIdentityConverter.fromSteamId64("76561202255233023")

        assertEquals("STEAM_0:1:0", odd?.steamId2)
        assertEquals("4294967295", upperBoundary?.accountId)
        assertEquals("[U:1:4294967295]", upperBoundary?.steamId3)
        assertEquals("STEAM_0:1:2147483647", upperBoundary?.steamId2)
    }

    @Test
    fun rejectsMalformedOrOutOfRangeSteamIds() {
        assertNull(SteamIdentityConverter.fromSteamId64(""))
        assertNull(SteamIdentityConverter.fromSteamId64("7656119796028793x"))
        assertNull(SteamIdentityConverter.fromSteamId64("76561197960265727"))
        assertNull(SteamIdentityConverter.fromSteamId64("76561202255233024"))
        assertNull(SteamIdentityConverter.fromSteamId64("076561197960287930"))
    }
}
