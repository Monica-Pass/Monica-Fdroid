package takagi.ru.monica.viewmodel

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem

class ItemDataSnapshotCacheTest {
    @Test
    fun failedDecodingCanRecoverWithoutChangingTheEncryptedPayload() {
        val cache = ItemDataSnapshotCache<String>()
        val item = SecureItem(id = 1, itemType = ItemType.BANK_CARD, title = "", itemData = "encrypted")
        assertEquals(listOf<String?>(null), cache.parse(listOf(item)) { null })
        assertEquals(listOf("decoded"), cache.parse(listOf(item)) { "decoded" })
    }

    @Test
    fun reparsesOnlyPayloadChangesAndDropsRemovedEntries() {
        val cache = ItemDataSnapshotCache<String>()
        var calls = 0
        val parse: (String) -> String = { calls++; it.uppercase() }
        val a = SecureItem(id = 1, itemType = ItemType.BANK_CARD, title = "A", itemData = "a")
        val b = a.copy(id = 2, itemData = "b")
        assertEquals(listOf("A", "B"), cache.parse(listOf(a, b), parse))
        cache.parse(listOf(b, a.copy(isFavorite = true, updatedAt = Date(10))), parse)
        assertEquals(2, calls)
        assertEquals(listOf("C"), cache.parse(listOf(a.copy(itemData = "c")), parse))
        assertEquals(3, calls)
        cache.parse(listOf(b), parse)
        assertEquals(4, calls)
    }

    @Test
    fun largeSnapshotsDoNotClearTheCacheMidwayThroughASecondPass() {
        val cache = ItemDataSnapshotCache<String>()
        var calls = 0
        val items = (1L..5000L).map { SecureItem(id = it, itemType = ItemType.DOCUMENT, title = "", itemData = "$it") }
        repeat(2) { cache.parse(items) { calls++; it } }
        assertEquals(items.size, calls)
    }
}
