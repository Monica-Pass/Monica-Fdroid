package takagi.ru.monica.attachments.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource

class AttachmentBackupOwnerTest {
    @Test
    fun manifestRoundTripPreservesPasswordAndSecureItemOwners() {
        val encoded = AttachmentBackupCodec.encode(
            listOf(
                attachment(parentPasswordId = 4L, fileName = "password.txt"),
                attachment(parentSecureItemId = 4L, fileName = "note.txt")
            )
        )

        val decoded = AttachmentBackupCodec.decode(encoded)

        assertEquals(2, decoded.version)
        assertEquals(2, decoded.entries.size)
        val password = decoded.entries.single { it.fileName == "password.txt" }
        val secureItem = decoded.entries.single { it.fileName == "note.txt" }
        assertEquals(4L, password.parentPasswordId)
        assertNull(password.parentSecureItemId)
        assertEquals(4L, secureItem.parentSecureItemId)
        assertNull(secureItem.parentPasswordId)
    }

    private fun attachment(
        parentPasswordId: Long? = null,
        parentSecureItemId: Long? = null,
        fileName: String
    ): Attachment = Attachment(
        parentPasswordId = parentPasswordId,
        parentSecureItemId = parentSecureItemId,
        source = AttachmentSource.LOCAL.name,
        fileName = fileName,
        mimeType = "text/plain",
        sizeBytes = 1L,
        sha256Hex = "hash-$fileName",
        wrappedCek = "wrapped-$fileName",
        localPath = "$fileName.enc",
        downloadState = AttachmentDownloadState.DOWNLOADED.name,
        createdAt = 1L,
        updatedAt = 2L
    )
}
