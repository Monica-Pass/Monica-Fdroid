package takagi.ru.monica.attachments.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitwardenAttachmentDownloadMetadataTest {
    @Test
    fun freshDownloadUrlReplacesTheCachedSyncUrl() {
        assertEquals(
            "https://fresh.example/attachment",
            resolveBitwardenAttachmentDownloadUrl(
                freshUrl = "https://fresh.example/attachment",
                cachedUrl = "https://expired.example/attachment"
            )
        )
    }

    @Test
    fun cachedDownloadUrlRemainsACompatibilityFallback() {
        assertEquals(
            "https://cached.example/attachment",
            resolveBitwardenAttachmentDownloadUrl(
                freshUrl = null,
                cachedUrl = "https://cached.example/attachment"
            )
        )
        assertNull(resolveBitwardenAttachmentDownloadUrl(null, " "))
    }

    @Test
    fun freshAttachmentKeyTakesPriorityAcrossDevices() {
        assertEquals(
            "fresh-key",
            resolveBitwardenAttachmentKey(
                freshKey = "fresh-key",
                remoteKey = "sync-key",
                storedKey = "local-key"
            )
        )
        assertEquals(
            "sync-key",
            resolveBitwardenAttachmentKey(
                freshKey = null,
                remoteKey = "sync-key",
                storedKey = "local-key"
            )
        )
    }
}
