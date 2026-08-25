package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.CustomIcon
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassCustomIconEditorTest {
    @Test
    fun listSortsByNameAndKeepsReferenceCounts() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val items = KeePassCustomIconEditor.list(
            pool = mapOf(
                first to CustomIcon(data = byteArrayOf(1), name = "Zulu", lastModified = Instant.EPOCH),
                second to CustomIcon(data = byteArrayOf(2), name = "Alpha", lastModified = Instant.EPOCH),
            ),
            referencedUuids = mapOf(first to 2),
        )

        assertEquals(listOf(second, first), items.map { it.uuid })
        assertEquals(0, items[0].referenceCount)
        assertEquals(2, items[1].referenceCount)
        assertFalse(KeePassCustomIconEditor.canDelete(items[1]))
        assertTrue(KeePassCustomIconEditor.canDelete(items[0]))
    }

    @Test
    fun countReferencesCountsDuplicateReferences() {
        val uuid = UUID.randomUUID()
        assertEquals(
            mapOf(uuid to 3),
            KeePassCustomIconEditor.countReferences(listOf(uuid, uuid, uuid)),
        )
    }

    @Test
    fun newIconCopiesBytesAndNormalizesName() {
        val source = pngHeader() + byteArrayOf(1, 2, 3)
        val (_, icon) = KeePassCustomIconEditor.newIcon(source, "  Logo  ")
        source[0] = 9

        assertEquals("Logo", icon.name)
        assertEquals(0x89.toByte(), icon.data[0])
    }

    @Test
    fun rejectsUnknownOrOversizedIconData() {
        assertTrue(KeePassCustomIconEditor.validateImageBytes(byteArrayOf(1, 2, 3)).isFailure)
        assertTrue(
            KeePassCustomIconEditor.validateImageBytes(
                pngHeader() + ByteArray(KeePassCustomIconEditor.MAX_ICON_BYTES),
            ).isFailure,
        )
    }

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
}
