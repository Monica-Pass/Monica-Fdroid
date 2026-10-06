package takagi.ru.monica.bitwarden.mapper

import org.junit.Assert.*
import org.junit.Test

class PasskeyNotesCodecTest {
    @Test fun ordinaryNotesAreExactIncludingSeparatorsWhitespaceAndExplicitClear() {
        for (notes in listOf("", "  ", "first---second", " before\r\n---\r\nafter ", "[Monica Passkey Metadata]\nmy own text", "中文\n🔐 private")) {
            var returned = notes
            repeat(5) { returned = PasskeyNotesCodec.decode(returned) }
            assertEquals(notes, returned)
        }
        assertEquals("", PasskeyNotesCodec.decode(null))
    }

    @Test fun completeHistoricalFooterIsRecognizedWithoutEatingOrdinarySeparators() {
        val footer = "\n🔐 This is a Passkey entry synced from Monica\n" +
            "ℹ️ Private key availability depends on client capability.\n\n---\n[Monica Passkey Metadata]\n" +
            "credentialId: AA\nrpId: example.com\nrpName: Example\nuserId: dXNlcg\nuserDisplayName: User\n" +
            "publicKeyAlgorithm: -7\nsignCount: 0\ncreatedAt: 1\nlastUsedAt: 2\n"
        assertEquals("", PasskeyNotesCodec.decode(footer))
        assertEquals("first---second", PasskeyNotesCodec.decode("first---second\n" + footer))
        assertEquals(footer + "extra user content", PasskeyNotesCodec.decode(footer + "extra user content"))
        assertEquals(footer.replace("signCount: 0", "signCount: invalid"), PasskeyNotesCodec.decode(footer.replace("signCount: 0", "signCount: invalid")))
    }
}
