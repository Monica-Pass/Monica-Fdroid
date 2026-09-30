package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2ScrollLookupTest {
    private val item = VaultV2Item("fixture", VaultV2ItemType.PASSWORD, "Fixture", "", false, "", emptyList())

    @Test fun headerRowsLeadingControlsAndTrailingPaddingKeepTheirSection() {
        val sections = listOf(
            VaultV2SectionLayout("2026/09/30", List(2) { item }, 0, 5),
            VaultV2SectionLayout("2026/09/29", List(3) { item }, 2, 8),
        )
        for (index in -2..15) {
            assertEquals(if (index <= 6) "2026/09/30" else "2026/09/29",
                vaultV2SectionTitleForLazyIndex(sections, index))
            val expected = when { index <= 5 -> 0; index == 6 -> 1; index <= 8 -> 2; index == 9 -> 3; else -> 4 }
            assertEquals(expected, vaultV2ItemIndexForLazyIndex(sections, index))
        }
        assertEquals(null, vaultV2SectionTitleForLazyIndex(emptyList(), 42))
        assertEquals(0, vaultV2ItemIndexForLazyIndex(emptyList(), 42))
    }

    @Test fun thousandsOfDateGroupsRequireOnlyLogarithmicLookups() {
        val backing = List(4096) { VaultV2SectionLayout("date-$it", listOf(item), it, it * 2 + 5) }
        var reads = 0
        val sections = object : AbstractList<VaultV2SectionLayout>() {
            override val size get() = backing.size
            override fun get(index: Int): VaultV2SectionLayout { reads++; return backing[index] }
        }
        for (section in listOf(0, 1024, 2048, 4095)) {
            reads = 0
            assertEquals("date-$section", vaultV2SectionTitleForLazyIndex(sections, section * 2 + 5))
            assertTrue("A drag label must not scan $reads sections", reads <= 15)
            reads = 0
            assertEquals(section, vaultV2ItemIndexForLazyIndex(sections, section * 2 + 5))
            assertTrue("Scroll progress must not scan $reads sections", reads <= 15)
        }
    }
}
