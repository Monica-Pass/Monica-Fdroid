package takagi.ru.monica.bitwarden.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwardenPasswordCustomFieldAdapterTest {

    @Test
    fun `extract user fields keeps order duplicates and hidden type`() {
        val remote = listOf(
            BitwardenPlainCustomField("monica_email", "internal@example.com", 0),
            BitwardenPlainCustomField("test", "one", 0),
            BitwardenPlainCustomField("test", "two", 1),
            BitwardenPlainCustomField("flag", "true", 2),
            BitwardenPlainCustomField("linked", null, 3, linkedId = 100)
        )

        assertEquals(
            listOf(
                MonicaPlainCustomField("test", "one", isProtected = false),
                MonicaPlainCustomField("test", "two", isProtected = true)
            ),
            BitwardenPasswordCustomFieldAdapter.extractUserFields(remote)
        )
    }

    @Test
    fun `same revision merges historical local and remote fields without losing either`() {
        val result = BitwardenPasswordCustomFieldAdapter.mergeIncoming(
            local = listOf(MonicaPlainCustomField("test2", "test2", false)),
            remote = listOf(MonicaPlainCustomField("test", "test", false)),
            sameRevision = true
        )

        assertEquals(
            listOf(
                MonicaPlainCustomField("test", "test", false),
                MonicaPlainCustomField("test2", "test2", false)
            ),
            result.fields
        )
        assertTrue(result.needsUpload)
    }

    @Test
    fun `new remote revision replaces clean local cache`() {
        val result = BitwardenPasswordCustomFieldAdapter.mergeIncoming(
            local = listOf(MonicaPlainCustomField("old", "local", false)),
            remote = listOf(MonicaPlainCustomField("new", "remote", true)),
            sameRevision = false
        )

        assertEquals(
            listOf(MonicaPlainCustomField("new", "remote", true)),
            result.fields
        )
        assertFalse(result.needsUpload)
    }

    @Test
    fun `first upload preserves unmatched remote user fields and unsupported fields`() {
        val remote = listOf(
            BitwardenPlainCustomField("monica_email", "old@example.com", 0),
            BitwardenPlainCustomField("remote", "keep", 0),
            BitwardenPlainCustomField("same", "value", 1),
            BitwardenPlainCustomField("flag", "true", 2)
        )
        val local = listOf(
            MonicaPlainCustomField("same", "value", true),
            MonicaPlainCustomField("local", "add", false)
        )

        val indexes = BitwardenPasswordCustomFieldAdapter.remoteIndexesToPreserveForUpload(
            remote = remote,
            local = local,
            localSystemFieldNames = setOf("monica_email"),
            initialized = false
        )

        assertEquals(setOf(1, 3), indexes)
    }

    @Test
    fun `initialized upload replaces editable remote fields while preserving other types`() {
        val remote = listOf(
            BitwardenPlainCustomField("remote", "delete", 0),
            BitwardenPlainCustomField("secret", "replace", 1),
            BitwardenPlainCustomField("flag", "true", 2),
            BitwardenPlainCustomField(null, "opaque", 0)
        )

        val indexes = BitwardenPasswordCustomFieldAdapter.remoteIndexesToPreserveForUpload(
            remote = remote,
            local = emptyList(),
            localSystemFieldNames = emptySet(),
            initialized = true
        )

        assertEquals(setOf(2, 3), indexes)
    }
}
