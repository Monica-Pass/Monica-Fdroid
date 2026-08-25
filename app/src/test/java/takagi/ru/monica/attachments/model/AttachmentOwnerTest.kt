package takagi.ru.monica.attachments.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentOwnerTest {
    @Test
    fun attachmentResolvesPasswordAndSecureItemOwnersWithoutIdCollision() {
        val passwordAttachment = attachment(parentPasswordId = 7L)
        val secureItemAttachment = attachment(parentSecureItemId = 7L)

        assertEquals(AttachmentOwner.password(7L), passwordAttachment.owner)
        assertEquals(AttachmentOwner.secureItem(7L), secureItemAttachment.owner)
        assertEquals(passwordAttachment.owner?.id, secureItemAttachment.owner?.id)
        assertEquals(false, passwordAttachment.owner == secureItemAttachment.owner)
    }

    @Test
    fun invalidAmbiguousOwnershipDoesNotResolve() {
        assertNull(attachment().owner)
        assertNull(attachment(parentPasswordId = 1L, parentSecureItemId = 2L).owner)
    }

    private fun attachment(
        parentPasswordId: Long? = null,
        parentSecureItemId: Long? = null
    ): Attachment = Attachment(
        parentPasswordId = parentPasswordId,
        parentSecureItemId = parentSecureItemId,
        source = AttachmentSource.LOCAL.name,
        fileName = "sample.txt",
        mimeType = "text/plain",
        sizeBytes = 1L,
        downloadState = AttachmentDownloadState.DOWNLOADED.name,
        createdAt = 1L,
        updatedAt = 1L
    )
}
