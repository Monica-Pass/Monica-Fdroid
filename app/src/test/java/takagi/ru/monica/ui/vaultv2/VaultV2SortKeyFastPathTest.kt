package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultV2SortKeyFastPathTest {

    @Test
    fun `ascii titles are normalized without transliteration`() {
        assertEquals("Example 123", normalizeAsciiVaultV2SortKey("  Example   123  "))
        assertEquals("AB", normalizeAsciiVaultV2SortKey("A--B"))
        assertEquals("#", normalizeAsciiVaultV2SortKey("   "))
    }

    @Test
    fun `non ascii titles fall through to ICU transliteration`() {
        assertNull(normalizeAsciiVaultV2SortKey("中文标题"))
        assertNull(normalizeAsciiVaultV2SortKey("Café"))
    }
}
