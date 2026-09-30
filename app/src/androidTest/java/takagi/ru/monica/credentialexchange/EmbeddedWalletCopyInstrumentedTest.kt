package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
import takagi.ru.monica.data.model.EmbeddedWalletContent
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class EmbeddedWalletCopyInstrumentedTest {
    @Test fun housekeepingDoesNotDeleteEncryptedBlobBeforeRoomRegistration() = runBlocking {
        val fixture = TransferFixture()
        val storage = takagi.ru.monica.attachments.storage.AttachmentStorage(fixture.context)
        val blob = storage.writeEncrypted(byteArrayOf(9,8,7).inputStream())
        try {
            AttachmentContainer.facade(fixture.context).purgeOrphanedLocalBlobs()
            assertTrue(storage.exists(blob.relativePath))
            assertArrayEquals(byteArrayOf(9,8,7), storage.openDecryptedStream(blob.relativePath, blob.cek).use { it.readBytes() })
        } finally { storage.delete(blob.relativePath); blob.cek.fill(0); fixture.close() }
    }

    @Test fun completeCardCopySurvivesSourceDeletionAndRetriesAcrossTwoPasswords() = runBlocking {
        val fixture = TransferFixture()
        val store = EmbeddedWalletDraftStore(fixture.context)
        try {
            val source = SecureItem(itemType = ItemType.BANK_CARD, title = "${fixture.prefix}-card", notes = "private notes",
                itemData = """{"cardNumber":"4242424242424242","cardholderName":"ALICE","expiryMonth":"09","expiryYear":"2030","pin":"0123","future":{"keep":true},"cardFace":{"imageAttachmentName":"face.jpg","displayMode":"ALL"}}""")
            val sourceId = fixture.db.secureItemDao().insertItem(source)
            val facade = AttachmentContainer.facade(fixture.context)
            val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(sourceId), AttachmentSource.LOCAL,
                "face.jpg", "image/png", testImage(), true))
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(sourceId), AttachmentSource.LOCAL,
                "full-statement.bin", "application/octet-stream", bytes, true))
            val copy = EmbeddedWalletCopyService(fixture.context).prepare(source.copy(id = sourceId))
            store.add(copy)
            assertEquals(2, copy.snapshot.assets.size)
            assertEquals("0123", copy.snapshot.itemData["pin"]?.jsonPrimitive?.content)
            assertEquals(true, copy.snapshot.itemData["future"]?.jsonObject?.get("keep")?.jsonPrimitive?.boolean)
            facade.list(AttachmentOwner.secureItem(sourceId)).forEach { facade.deleteAttachment(it.id) }
            fixture.db.secureItemDao().deleteItemById(sourceId)
            repeat(2) { index ->
                val entry = PasswordEntry(title = "${fixture.prefix}-target-$index", username = "", password = "", website = "")
                val targetId = fixture.db.passwordEntryDao().insertPasswordEntry(entry)
                repeat(2) { store.persist(entry.copy(id = targetId), listOf(copy.snapshot), true) }
                val actual = facade.list(AttachmentOwner.password(targetId))
                assertEquals(2, actual.size)
                val payload = actual.single { it.fileName == copy.snapshot.assets.single { asset -> asset.displayName == "full-statement.bin" }.name }
                val output = ByteArrayOutputStream()
                facade.copyAttachmentTo(payload.id, output)
                assertArrayEquals(bytes, output.toByteArray())
                EmbeddedWalletAccess.open(fixture.context, entry.copy(id = targetId), copy.snapshot).use { access ->
                    val face = requireNotNull(access.image(copy.snapshot.assets.single { it.role == EmbeddedWalletContent.AssetRole.CARD_FACE }.name))
                    assertEquals(16, face.width)
                    assertEquals(android.graphics.Color.BLUE, face.getPixel(0, 0))
                    face.recycle()
                }
            }
        } finally { store.close(); fixture.close() }
    }

    @Test fun noteRetainsBodyFormattingTagsUnknownFieldsAndIndependentInlineImage() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = SecureItem(itemType = ItemType.NOTE, title = "${fixture.prefix}-note", notes = "other notes",
                itemData = """{"content":"# Header\n![image](monica-image://picture.png)\n- [ ] Task","tags":["tag"],"isMarkdown":true,"future":{"x":7}}""")
            val id = fixture.db.secureItemDao().insertItem(source)
            val facade = AttachmentContainer.facade(fixture.context)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(id), AttachmentSource.LOCAL,
                "picture.png", "image/png", byteArrayOf(4,5,6), true))
            EmbeddedWalletCopyService(fixture.context).prepare(source.copy(id = id)).use { copy ->
                val image = copy.snapshot.assets.single()
                assertEquals(EmbeddedWalletContent.AssetRole.INLINE_IMAGE, image.role)
                assertEquals("# Header\n![image](monica-image://${image.name})\n- [ ] Task", copy.snapshot.itemData["content"]?.jsonPrimitive?.content)
                assertEquals(true, copy.snapshot.itemData["isMarkdown"]?.jsonPrimitive?.boolean)
                assertEquals(7, copy.snapshot.itemData["future"]?.jsonObject?.get("x")?.jsonPrimitive?.int)
                assertEquals("tag", copy.snapshot.itemData["tags"]?.jsonArray?.single()?.jsonPrimitive?.content)
                assertArrayEquals(byteArrayOf(4,5,6), copy.assets.open(image.name).use { it.readBytes() })
            }
        } finally { fixture.close() }
    }

    @Test fun missingCardFaceDoesNotProduceAPartialCopy() = runBlocking {
        val fixture = TransferFixture()
        try {
            val item = SecureItem(itemType = ItemType.BANK_CARD, title = "${fixture.prefix}-missing",
                itemData = """{"cardFace":{"imageAttachmentName":"missing.jpg"}}""")
            val id = fixture.db.secureItemDao().insertItem(item)
            val result = runCatching { EmbeddedWalletCopyService(fixture.context).prepare(item.copy(id = id)) }
            assertTrue(result.isFailure)
            assertNotNull(fixture.db.secureItemDao().getItemById(id))
        } finally { fixture.close() }
    }

    @Test fun copiedContentIsPublishedOnlyAfterNativeAttachmentsExist() = runBlocking {
        val fixture = TransferFixture()
        val model = takagi.ru.monica.viewmodel.PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(fixture.db.customFieldDao()),
            context = fixture.context, localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(),
            strings = takagi.ru.monica.utils.AppLocaleStringResolver(fixture.context))
        val store = EmbeddedWalletDraftStore(fixture.context)
        var stage = "prepare source"
        var failure: Throwable? = null
        try {
            val source = SecureItem(itemType = ItemType.NOTE, title = "${fixture.prefix}-portable",
                itemData = """{"content":"portable body","isMarkdown":true,"future":true}""")
            val sourceId = fixture.db.secureItemDao().insertItem(source)
            val facade = AttachmentContainer.facade(fixture.context)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(sourceId), AttachmentSource.LOCAL,
                "source.bin", "application/octet-stream", byteArrayOf(11,22,33), true))
            val prepared = EmbeddedWalletCopyService(fixture.context).prepare(source.copy(id = sourceId))
            store.add(prepared)
            val fields = EmbeddedWalletContent.put(emptyList(), prepared.snapshot)
            val targets = listOf(takagi.ru.monica.data.model.StorageTarget.MonicaLocal(null),
                takagi.ru.monica.data.model.StorageTarget.KeePass(fixture.keepass().databaseId, null),
                takagi.ru.monica.data.model.StorageTarget.Mdbx(fixture.mdbx().databaseId, null))
            // Publication must honor the explicit save target even when the list shows another vault.
            model.setCategoryFilter(takagi.ru.monica.viewmodel.CategoryFilter.MdbxDatabase(
                (targets.last() as takagi.ru.monica.data.model.StorageTarget.Mdbx).databaseId))
            for ((index, target) in targets.withIndex()) {
                stage = "save destination $index"
                val deferred = takagi.ru.monica.data.model.DeferredEmbeddedContentSave(store.pendingIds())
                val completed = kotlinx.coroutines.CompletableDeferred<Long?>()
                model.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = "${fixture.prefix}-published-$index",
                    username = "alice", password = "test-only", website = ""), listOf("test-only"), listOf(target), fields,
                    embeddedContentSave = deferred, onComplete = { completed.complete(it) })
                val id = requireNotNull(kotlinx.coroutines.withTimeout(30000) { completed.await() })
                stage = "read destination $index"
                assertFalse(model.getCustomFieldsByEntryIdSync(id).any { EmbeddedWalletContent.isMetadata(it.title) })
                val entry = requireNotNull(model.getPasswordEntryById(id))
                stage = "persist assets destination $index"
                store.persist(entry, listOf(prepared.snapshot), true)
                stage = "publish destination $index"
                model.publishEmbeddedContent(id, requireNotNull(deferred.commit(id)))
                val saved = requireNotNull(model.getPasswordEntryById(id))
                assertEquals(entry.keepassDatabaseId, saved.keepassDatabaseId)
                assertEquals(entry.mdbxDatabaseId, saved.mdbxDatabaseId)
                assertEquals(entry.bitwardenVaultId, saved.bitwardenVaultId)
                val published = model.getCustomFieldsByEntryIdSync(id).single { EmbeddedWalletContent.isMetadata(it.title) }
                assertEquals(prepared.snapshot.encode(), published.value)
                stage = "verify assets destination $index"
                EmbeddedWalletAccess.open(fixture.context, requireNotNull(model.getPasswordEntryById(id)), prepared.snapshot).use { access ->
                    val bytes = ByteArrayOutputStream()
                    access.copyTo(prepared.snapshot.assets.single().name, bytes)
                    assertArrayEquals(byteArrayOf(11,22,33), bytes.toByteArray())
                }
            }
        } catch (error: Throwable) {
            throw AssertionError("Copied content failed at $stage", error).also { failure = it }
        } finally {
            store.close(); model.viewModelScope.cancel()
            try { fixture.close() } catch (cleanupError: Throwable) {
                if (failure != null) failure!!.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
    private fun testImage(): ByteArray {
        val image = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        return try { ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { image.recycle() }
    }

}
