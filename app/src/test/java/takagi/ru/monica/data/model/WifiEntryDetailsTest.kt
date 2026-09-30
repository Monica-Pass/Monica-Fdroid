package takagi.ru.monica.data.model

import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.utils.WifiQrParser
import takagi.ru.monica.utils.WifiQrPayload

class WifiEntryDetailsTest {
    private fun entry(raw: String) = PasswordEntry(title = "Old network", username = "", password = "", website = "", loginType = "WIFI", wifiMetadata = raw)
    @Test fun legacyBlankSsidUsesTitleInDetailsAndEditorWithoutRewritingData() {
        listOf("", "{}", """{"ssid":""}""").forEach { raw ->
            val e = entry(raw)
            assertEquals("Old network", WifiEntryDetails.from(e).ssid)
            assertEquals("Old network", TemplateCredentialDraft.load(e, emptyList())!!.value("ssid"))
            assertEquals(raw, e.wifiMetadata)
        }
    }
    @Test fun coreFieldsSurviveUnknownAdvancedSettingsAndQrEscapesSpecialCharacters() {
        val e = entry("""{"ssid":"Office;网络","security":"WPA3","hiddenNetwork":true,"proxy":{"kind":"FUTURE"},"ip":{"kind":"OTHER"}}""")
        val data = WifiEntryDetails.from(e).qrData()!!
        assertEquals("Office;网络", data.ssid)
        assertEquals(WifiSecurity.WPA3, data.security)
        val qr = WifiQrParser.parse(WifiQrPayload.build(data, "p;:ass,\\word"))!!
        assertEquals(data.ssid, qr.ssid)
        assertEquals("p;:ass,\\word", qr.password)
        assertTrue(qr.hidden)
    }
    @Test fun unreadableOrUnknownSecurityCannotCreateAnIncorrectQr() {
        listOf("broken", """{"ssid":12}""", """{"ssid":"known","security":"FUTURE"}""").forEach {
            assertNull(WifiEntryDetails.from(entry(it)).qrData())
        }
        assertNull(WifiQrPayload.build(WifiEntryDetails.from(entry("""{"security":"WPA2_ENTERPRISE"}""")).qrData()!!, "fixture"))
    }
}
