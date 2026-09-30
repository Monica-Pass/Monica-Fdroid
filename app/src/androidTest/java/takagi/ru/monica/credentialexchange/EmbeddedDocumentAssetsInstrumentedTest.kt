package takagi.ru.monica.credentialexchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.*
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.EmbeddedDocumentEditorData
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmbeddedDocumentAssetsInstrumentedTest {
    @Test fun fullDocumentCopyAndEditRetainOriginalBytesAfterSourceDeletion() = runBlocking {
        val fixture = TransferFixture()
        val store = EmbeddedWalletDraftStore(fixture.context)
        try {
            val facade = AttachmentContainer.facade(fixture.context)
            val source = SecureItem(itemType = ItemType.DOCUMENT, title = "${fixture.prefix}-document", notes = "original",
                itemData = """{"documentType":"PASSPORT","documentNumber":"P0001","fullName":"张伟","firstName":"Wei",
                    "address3":"Unit 3","nationality":"CN","customFields":[{"label":"Secret","value":"001","type":"HIDDEN"}],
                    "future":{"keep":true},"cardFace":{"imageAttachmentName":"face.png","displayMode":"ALL"}}""")
            val id = fixture.db.secureItemDao().insertItem(source)
            val photoNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.DOCUMENT).toList()
            val payloads = linkedMapOf("face.png" to byteArrayOf(1, 2, 3), photoNames[0] to byteArrayOf(4, 5, 6),
                photoNames[1] to byteArrayOf(7, 8, 9), "original-file.bin" to ByteArray(1024 * 1024 + 7) { (it % 251).toByte() })
            payloads.forEach { (name, bytes) ->
                facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(id), AttachmentSource.LOCAL,
                    name, "application/octet-stream", bytes, true))
            }
            EmbeddedWalletCopyService(fixture.context).prepare(source.copy(id = id)).use { copy ->
                assertEquals(4, copy.snapshot.assets.size)
                assertEquals(EmbeddedWalletContent.AssetRole.entries.filter { it != EmbeddedWalletContent.AssetRole.INLINE_IMAGE }.toSet(),
                    copy.snapshot.assets.map { it.role }.toSet())
                val editor = EmbeddedDocumentEditorData(copy.snapshot)
                val result = EmbeddedWalletEditorResult(editor.edited(copy.snapshot.title, "edited", false, editor.data, editor.customFields),
                    listOf(copy.snapshot.assets.single { it.role == EmbeddedWalletContent.AssetRole.FRONT }.name,
                        copy.snapshot.assets.single { it.role == EmbeddedWalletContent.AssetRole.BACK }.name), emptyList())
                val prepared = prepareEmbeddedEdit(fixture.context, result, copy, null)
                store.add(prepared)
                assertEquals(copy.snapshot.itemData["future"], prepared.snapshot.itemData["future"])
                assertEquals(copy.snapshot.itemData["customFields"], prepared.snapshot.itemData["customFields"])
                facade.list(AttachmentOwner.secureItem(id)).forEach { facade.deleteAttachment(it.id) }
                fixture.db.secureItemDao().deleteItemById(id)
                val parent = PasswordEntry(title = "${fixture.prefix}-parent", username = "", password = "", website = "")
                val parentId = fixture.db.passwordEntryDao().insertPasswordEntry(parent)
                store.persist(parent.copy(id = parentId), listOf(prepared.snapshot), true)
                EmbeddedWalletAccess.open(fixture.context, parent.copy(id = parentId), prepared.snapshot).use { access ->
                    prepared.snapshot.assets.forEach { asset ->
                        val output = ByteArrayOutputStream()
                        access.copyTo(asset.name, output)
                        assertArrayEquals(payloads.getValue(asset.displayName), output.toByteArray())
                    }
                }
                assertEquals("original", copy.snapshot.notes)
            }
        } finally { store.close(); fixture.close() }
    }

    @Test fun failedPreparationAndCancelledDraftKeepOriginalAssetsReadable() = runBlocking {
        val fixture = TransferFixture()
        try {
            val bytes = byteArrayOf(11, 22, 33)
            val assets = EmbeddedWalletAssetDraft.prepare(File(fixture.context.cacheDir, "embedded-document-test"), listOf(
                EmbeddedWalletAssetDraft.Source("front.png", "image/png", EmbeddedWalletContent.AssetRole.FRONT) { it.write(bytes) }))
            val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.DOCUMENT, title = "Document",
                itemData = """{"documentType":"ID_CARD","documentNumber":"001","fullName":"Alice"}""")).withAssets(assets.assets)
            EmbeddedWalletCopyService.Prepared(snapshot, assets).use { original ->
                val before = snapshot.encode()
                val name = snapshot.assets.single().name
                val failed = runCatching { prepareEmbeddedEdit(fixture.context,
                    EmbeddedWalletEditorResult(snapshot, listOf(name, "missing-${fixture.prefix}.jpg"), emptyList()), original, null) }
                assertTrue(failed.isFailure)
                assertEquals(before, original.snapshot.encode())
                assertArrayEquals(bytes, original.assets.open(name).use { it.readBytes() })
                // Preparing and discarding an edit is cancellation: it must not consume the original draft.
                prepareEmbeddedEdit(fixture.context, EmbeddedWalletEditorResult(snapshot, listOf(name, ""), emptyList()), original, null).close()
                assertEquals(before, original.snapshot.encode())
                assertArrayEquals(bytes, original.assets.open(name).use { it.readBytes() })
            }
        } finally { fixture.close() }
    }
}
