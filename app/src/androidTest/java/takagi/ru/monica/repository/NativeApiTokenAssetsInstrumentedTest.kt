package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.*
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeApiTokenAssetsInstrumentedTest {
    private val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/api/v4/","token":"synthetic-native-assets-token"}"""

    @Test fun completeWalletBytesSurviveCopyMoveReopenBackupAndManualSync() = runBlocking<Unit> {
        val fixture = Fixture()
        val prepared = mutableListOf<EmbeddedWalletCopyService.Prepared>()
        try {
            val source = fixture.create()
            val sourceFile = fixture.files.single().second
            val replicaFile = File(fixture.context.cacheDir, "native-assets-replica-${UUID.randomUUID()}.mdbx")
            uniffi.mdbx_ffi.createPortableBackup(sourceFile.absolutePath, replicaFile.absolutePath)
            val replica = fixture.register(replicaFile)
            val destination = fixture.create()
            val facade = AttachmentContainer.facade(fixture.context)
            val card = SecureItem(itemType = ItemType.BANK_CARD, title = "Synthetic copied card", notes = "Private card notes",
                itemData = """{"cardNumber":"4242424242424242","pin":"0123","future":{"keep":true},"cardFace":{"imageAttachmentName":"face.png","displayMode":"ALL"}}""")
            val note = SecureItem(itemType = ItemType.NOTE, title = "Synthetic copied note",
                itemData = """{"content":"# Copied\n![image](monica-image://inline.png)","tags":["test"],"isMarkdown":true,"future":7}""")
            val expected = linkedMapOf<String, ByteArray>()
            for (item in listOf(card, note)) {
                val id = fixture.room.secureItemDao().insertItem(item).also { fixture.secureIds += it }
                val names = if (item.itemType == ItemType.BANK_CARD) listOf("face.png", "statement.bin") else listOf("inline.png", "note.bin")
                val sourceBytes = names.mapIndexed { index, name -> name to ByteArray(4096 + index) { (it % 251).toByte() } }.toMap()
                for ((name, bytes) in sourceBytes) facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                    AttachmentOwner.secureItem(id), AttachmentSource.LOCAL, name, "application/octet-stream", bytes, true))
                val copy = EmbeddedWalletCopyService(fixture.context).prepare(item.copy(id = id)).also { prepared += it }
                copy.snapshot.assets.forEach { expected[it.name] = sourceBytes.getValue(it.displayName) }
                // The draft must already own its bytes before the source disappears.
                facade.list(AttachmentOwner.secureItem(id)).forEach { facade.deleteAttachment(it.id) }
                fixture.room.secureItemDao().deleteItemById(id)
            }
            var fields = emptyList<CustomFieldDraft>()
            prepared.forEach { fields = EmbeddedWalletContent.put(fields, it.snapshot) }
            val metadata = ApiTokenMetadata.withCustomFields(ApiTokenMetadata.empty(), fields)
            val uploads = prepared.flatMap { copy -> copy.assets.assets.map { asset ->
                NativeApiTokenUpload(asset.name, asset.mimeType, asset.size, asset.sha256) { copy.assets.open(asset.name) }
            } }
            val saved = fixture.repository.saveNativeApiToken(source, null, "synthetic-assets", payload,
                metadata = metadata, uploads = uploads)
            assertEquals(4, fixture.repository.readNativeApiToken(saved).attachments.size)
            assertTrue(fixture.room.passwordEntryDao().getByMdbxDatabaseIdSync(source).isEmpty())
            fixture.assertBytes(saved, expected)
            val snapshot = fixture.repository.createSnapshot(source, "Assets together")
            val copy = fixture.repository.transferNativeApiToken(saved, destination, null, true)
            assertNotEquals(saved.entryId, copy.entryId)
            fixture.assertBytes(copy, expected)
            val folder = fixture.repository.createFolder(source, "Moved assets", null)
            val movedWithin = fixture.repository.transferNativeApiToken(saved, source, folder.folderId, false)
            assertEquals(saved.entryId, movedWithin.entryId)
            fixture.assertBytes(movedWithin, expected)
            val moved = fixture.repository.transferNativeApiToken(movedWithin, destination, null, false)
            assertEquals(saved.entryId, moved.entryId)
            assertTrue(fixture.repository.listNativeApiTokens(source).isEmpty())
            fixture.assertBytes(moved, expected)
            fixture.repository.revertToSnapshot(source, snapshot.snapshotId)
            val restored = fixture.repository.listNativeApiTokens(source).single()
            fixture.assertBytes(restored, expected)
            val bundle = fixture.repository.exportSyncBundle(source, null)
            fixture.repository.importSyncBundle(replica, bundle)
            fixture.assertBytes(fixture.repository.listNativeApiTokens(replica).single(), expected)
            val backupFile = File(fixture.context.cacheDir, "native-assets-backup-${UUID.randomUUID()}.mdbx")
            uniffi.mdbx_ffi.createPortableBackup(sourceFile.absolutePath, backupFile.absolutePath)
            val backup = fixture.register(backupFile)
            fixture.assertBytes(fixture.repository.listNativeApiTokens(backup).single(), expected)
            assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(fixture.repository.readNativeApiToken(restored).payload))
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(fixture.repository.readNativeApiToken(restored).extras!!.payload))
        } finally { prepared.forEach { it.close() }; fixture.close() }
    }

    @Test fun badOrMissingBytesAndStaleEditorNeverPublishOrRemoveOriginals() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            val database = fixture.create()
            val bytes = byteArrayOf(9, 8, 7)
            val saved = fixture.repository.saveNativeApiToken(database, null, "original-assets", payload,
                uploads = listOf(NativeApiTokenUpload("original.bin", "application/octet-stream", 3) { bytes.inputStream() }))
            val original = fixture.repository.readNativeApiToken(saved)
            val failures = listOf(
                NativeApiTokenUpload("wrong-hash.bin", "application/octet-stream", 3, "0".repeat(64)) { bytes.inputStream() },
                NativeApiTokenUpload("missing.bin", "application/octet-stream") { throw IOException("Synthetic unreadable source") },
                NativeApiTokenUpload("cancelled.bin", "application/octet-stream") { throw kotlinx.coroutines.CancellationException("Synthetic cancelled picker") },
            )
            for (upload in failures) {
                assertTrue(runCatching { fixture.repository.saveNativeApiToken(database, original, "must-not-publish", original.payload,
                    metadata = ApiTokenMetadata.withNotes(ApiTokenMetadata.empty(), "Must not publish"), uploads = listOf(upload),
                    removedAttachmentIds = original.attachments.map { it.id }.toSet()) }.isFailure)
                val after = fixture.repository.readNativeApiToken(saved)
                assertEquals("original-assets", after.summary.title)
                assertNull(after.extras)
                assertEquals(original.attachments, after.attachments)
                assertArrayEquals(bytes, fixture.repository.readNativeApiTokenAttachment(after, after.attachments.single().id))
            }
            val missingSnapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "Missing bytes", itemData = "{}"))
                .withAssets(listOf(EmbeddedWalletContent.Asset("wallet-missing", "missing.bin", "application/octet-stream",
                    EmbeddedWalletContent.AssetRole.ATTACHMENT, 3, NativeApiTokenAssets.digest(bytes))))
            assertTrue(runCatching { fixture.repository.saveNativeApiToken(database, original, "metadata-only", payload,
                metadata = ApiTokenMetadata.withCustomFields(ApiTokenMetadata.empty(), EmbeddedWalletContent.put(emptyList(), missingSnapshot))) }.isFailure)
            fixture.repository.saveNativeApiToken(database, original, original.summary.title, payload,
                uploads = listOf(NativeApiTokenUpload("new.bin", "application/octet-stream", 3) { bytes.inputStream() }))
            assertTrue(runCatching { fixture.repository.saveNativeApiToken(database, original, "stale", payload) }.isFailure)
            assertTrue(runCatching { fixture.repository.deleteNativeApiToken(original) }.isFailure)
            assertEquals(2, fixture.repository.readNativeApiToken(saved).attachments.size)
        } finally { fixture.close() }
    }

    @Test fun encryptedZipRestoresNativeFilesAndRejectsOmittedOrDamagedBytes() = runBlocking<Unit> {
        val fixture = Fixture()
        var archive: File? = null
        try {
            val source = fixture.create()
            val destination = fixture.create()
            val bytes = ByteArray(1024 * 1024 + 5) { (it % 251).toByte() }
            val wallet = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "ZIP copied note",
                itemData = """{"content":"independent","future":{"keep":42}}"""))
                .withAssets(listOf(EmbeddedWalletContent.Asset("wallet-zip", "note.bin", "application/octet-stream",
                    EmbeddedWalletContent.AssetRole.ATTACHMENT, bytes.size.toLong(), NativeApiTokenAssets.digest(bytes))))
            val metadata = ApiTokenMetadata.withCustomFields("""{"schema":"monica.api-token.fields.v1","future":{"keep":true}}""",
                EmbeddedWalletContent.put(emptyList(), wallet))
            fixture.repository.saveNativeApiToken(source, null, "zip-native-assets", payload, metadata = metadata,
                uploads = listOf(NativeApiTokenUpload("wallet-zip", "application/octet-stream", bytes.size.toLong()) { bytes.inputStream() }))
            val sourceTarget = takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.MDBX, source)
            val target = takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.MDBX, destination)
            val exporter = takagi.ru.monica.transfer.DatabaseArchiveExporter(fixture.context)
            assertTrue(runCatching { exporter.prepare(sourceTarget, BackupPreferences(includeImages = false), "synthetic ZIP password") }.isFailure)
            assertTrue(runCatching { exporter.prepare(sourceTarget, BackupPreferences(), null) }.isFailure)
            archive = exporter.prepare(sourceTarget, BackupPreferences(), "synthetic ZIP password").first
            val content = takagi.ru.monica.utils.WebDavHelper(fixture.context).restoreFromBackupFile(archive,
                decryptPassword = "synthetic ZIP password", restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
            val backup = content.nativeTokens.single()
            assertEquals(1, backup.attachmentCount)
            val coordinator = takagi.ru.monica.credentialexchange.TargetedImportCoordinator(fixture.context,
                PasswordRepository(fixture.room.passwordEntryDao()), SecureItemRepository(fixture.room.secureItemDao(),
                    decryptSensitiveValue = fixture.security::decryptDataIfMonicaCiphertext))
            val imported = coordinator.apply(content, target)
            assertEquals(1, imported.imported)
            assertEquals(0, imported.failed)
            val saved = fixture.repository.listNativeApiTokens(destination).single()
            fixture.assertBytes(saved, mapOf("wallet-zip" to bytes))
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(fixture.repository.readNativeApiToken(saved).extras!!.payload))
            assertEquals(1, coordinator.apply(content, target).skipped)
            val missing = content.copy(nativeTokens = listOf(backup.copy(attachments = emptyList())))
            assertEquals(1, coordinator.apply(missing, target).failed)
            val corrupt = content.copy(nativeTokens = listOf(backup.copy(attachments = backup.attachments.map {
                it.copy(contentBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            })))
            assertEquals(1, coordinator.apply(corrupt, target).failed)
            assertEquals(1, fixture.repository.listNativeApiTokens(destination).size)
            fixture.assertBytes(saved, mapOf("wallet-zip" to bytes))
        } finally { archive?.delete(); fixture.close() }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val files = mutableListOf<Pair<Long, File>>()
        val secureIds = mutableListOf<Long>()
        val password = "Synthetic native assets password 123"
        suspend fun create(): Long = register(repository.createInitializedVaultFile(MdbxTigaMode.SKY, password))
        suspend fun register(file: File): Long = dao.insertDatabase(LocalMdbxDatabase(name = "Synthetic native assets",
            filePath = file.absolutePath, engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData(password),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue)).also { files += it to file }
        suspend fun assertBytes(summary: NativeApiTokenSummary, expected: Map<String, ByteArray>) {
            val reopened = Mdbx2Repository(context, dao, security)
            val token = reopened.readNativeApiToken(summary)
            assertEquals(expected.keys, token.attachments.map { it.fileName }.toSet())
            token.attachments.forEach { assertArrayEquals(expected.getValue(it.fileName), reopened.readNativeApiTokenAttachment(token, it.id)) }
        }
        suspend fun close() {
            val facade = AttachmentContainer.facade(context)
            secureIds.forEach { id ->
                facade.list(AttachmentOwner.secureItem(id)).forEach { facade.deleteAttachment(it.id) }
                room.secureItemDao().deleteItemById(id)
            }
            files.forEach { (id, file) -> dao.deleteDatabaseById(id); repository.deleteOwnedVaultFile(file) }
        }
    }
}
