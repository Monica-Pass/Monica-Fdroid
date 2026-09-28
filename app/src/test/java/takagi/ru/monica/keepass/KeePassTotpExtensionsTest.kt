package takagi.ru.monica.keepass

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData

class KeePassTotpExtensionsTest {
    @Test fun extensionUrisPreserveTypeSecretPinAndNamesWithoutNativeTotpFallback() {
        for (type in listOf(OtpType.MOTP, OtpType.YANDEX)) {
            val original = TotpData(
                secret = if (type == OtpType.MOTP) "a1b2c3d4e5f60708" else "JBSWY3DPEHPK3PXP",
                issuer = "Example:Work", accountName = "a+b&ops@example.invalid", otpType = type,
                period = if (type == OtpType.MOTP) 10 else 45,
                digits = if (type == OtpType.MOTP) 6 else 8,
                algorithm = if (type == OtpType.MOTP) "SHA1" else "SHA256", pin = "0421"
            )
            val fields = KeePassTotpCodec.toKeePassFields(original, "Title")
            assertEquals(setOf("otp"), fields.keys)
            assertTrue(KeePassTotpCodec.isSecretField("otp"))
            val restored = requireNotNull(KeePassTotpCodec.parseFields({ fields[it].orEmpty() }))
            assertEquals(original, restored)
        }
    }

    @Test fun pinInNativeUriWinsOverStaleMonicaMetadata() {
        val original = TotpData("JBSWY3DPEHPK3PXP", otpType = OtpType.YANDEX, pin = "1234")
        val raw = kotlinx.serialization.json.Json.encodeToString(TotpData.serializer(), original)
        val restored = requireNotNull(KeePassSecureItemPayload.resolveTotp(raw, original.copy(pin = "0421")))
        assertEquals("0421", restored.pin)
        val changed = requireNotNull(KeePassSecureItemPayload.resolveTotp(raw, original.copy(secret = "OTHER", pin = "")))
        assertEquals("", changed.pin)
    }
}
