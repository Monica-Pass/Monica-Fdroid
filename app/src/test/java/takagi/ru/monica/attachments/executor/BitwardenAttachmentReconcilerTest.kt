package takagi.ru.monica.attachments.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData

class BitwardenAttachmentReconcilerTest {

    @Test
    fun ciphertextSizeDifferenceDoesNotInvalidatePlaintextCache() {
        val update = planBitwardenAttachmentMetadataUpdate(
            local = localAttachment(sizeBytes = 100),
            remote = remoteAttachment(size = "160"),
            now = 2_000
        )

        assertNull(update)
    }

    @Test
    fun changedAttachmentKeyInvalidatesCachedBytes() {
        val update = planBitwardenAttachmentMetadataUpdate(
            local = localAttachment(),
            remote = remoteAttachment(key = "new-key"),
            now = 2_000
        )

        assertNotNull(update)
        requireNotNull(update)
        assertTrue(update.invalidateCache)
        assertNull(update.attachment.localPath)
        assertNull(update.attachment.wrappedCek)
        assertNull(update.attachment.sha256Hex)
        assertEquals(0L, update.attachment.sizeBytes)
        assertEquals(AttachmentDownloadState.PENDING.name, update.attachment.downloadState)
    }

    @Test
    fun refreshedDownloadUrlKeepsCachedBytes() {
        val update = planBitwardenAttachmentMetadataUpdate(
            local = localAttachment(),
            remote = remoteAttachment(url = "https://fresh.example/attachment"),
            now = 2_000
        )

        assertNotNull(update)
        requireNotNull(update)
        assertFalse(update.invalidateCache)
        assertEquals("cache.enc", update.attachment.localPath)
        assertEquals("wrapped", update.attachment.wrappedCek)
        assertEquals("plain-sha", update.attachment.sha256Hex)
        assertEquals(100L, update.attachment.sizeBytes)
        assertEquals(AttachmentDownloadState.DOWNLOADED.name, update.attachment.downloadState)
        assertEquals("https://fresh.example/attachment", update.attachment.bitwardenUrl)
    }

    @Test
    fun missingDecodedFileNameDoesNotDiscardUsableCache() {
        val update = planBitwardenAttachmentMetadataUpdate(
            local = localAttachment(),
            remote = remoteAttachment(fileName = null),
            now = 2_000
        )

        assertNull(update)
    }

    private fun localAttachment(
        sizeBytes: Long = 100
    ) = Attachment(
        id = 7,
        parentPasswordId = 42,
        source = AttachmentSource.BITWARDEN.name,
        fileName = "account.maFile",
        mimeType = "application/json",
        sizeBytes = sizeBytes,
        sha256Hex = "plain-sha",
        wrappedCek = "wrapped",
        localPath = "cache.enc",
        bitwardenAttachmentId = "attachment-id",
        bitwardenUrl = "https://cached.example/attachment",
        bitwardenFileKeyEnc = "old-key",
        downloadState = AttachmentDownloadState.DOWNLOADED.name,
        createdAt = 1_000,
        updatedAt = 1_000
    )

    private fun remoteAttachment(
        fileName: String? = "account.maFile",
        size: String = "100",
        key: String? = "old-key",
        url: String? = "https://cached.example/attachment"
    ) = CipherAttachmentApiData(
        id = "attachment-id",
        fileName = fileName,
        size = size,
        key = key,
        url = url
    )
}
