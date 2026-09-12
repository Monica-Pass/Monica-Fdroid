package takagi.ru.monica.attachments.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.api.AttachmentDownloadInfo
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData

class BitwardenAttachmentDownloadMetadataTest {
    @Test
    fun attachmentMetadataAcceptsBothServerPropertyConventions() {
        val lower = """{"id":"attachment","fileName":"encrypted-name","size":"65","key":"wrapped-key","url":"https://example.test/attachment"}"""
        val upper = """{"Id":"attachment","FileName":"encrypted-name","Size":"65","Key":"wrapped-key","Url":"https://example.test/attachment"}"""
        assertEquals(Json.decodeFromString<CipherAttachmentApiData>(lower), Json.decodeFromString<CipherAttachmentApiData>(upper))
        assertEquals(Json.decodeFromString<AttachmentDownloadInfo>(lower), Json.decodeFromString<AttachmentDownloadInfo>(upper))
    }

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
