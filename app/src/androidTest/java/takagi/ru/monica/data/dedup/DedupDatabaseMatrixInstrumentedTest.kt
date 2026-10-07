package takagi.ru.monica.data.dedup

import android.net.Uri
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.LegacyImageAttachmentSupport
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardFaceAttachment
import takagi.ru.monica.data.model.CardFaceConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Real KDBX and Rust MDBX files; Bitwarden uses an encrypted synthetic server. */
@RunWith(AndroidJUnit4::class)
class DedupDatabaseMatrixInstrumentedTest {
    @Test fun localToMdbx() = runBlocking { combination(ImportDestinationKind.LOCAL, true) }
    @Test fun mdbxToLocal() = runBlocking { combination(ImportDestinationKind.MDBX, false) }
    @Test fun mdbxToAnotherMdbx() = runBlocking { combination(ImportDestinationKind.MDBX, true) }
    @Test fun keepassToLocal() = runBlocking { combination(ImportDestinationKind.KEEPASS, false) }
    @Test fun keepassToMdbx() = runBlocking { combination(ImportDestinationKind.KEEPASS, true) }
    @Test fun bitwardenToLocal() = runBlocking { combination(ImportDestinationKind.BITWARDEN, false) }
    @Test fun bitwardenToMdbx() = runBlocking { combination(ImportDestinationKind.BITWARDEN, true) }
    @Test fun uncachedKeepassAttachmentsAreReadFromTheKdbxFile() = runBlocking { combination(ImportDestinationKind.KEEPASS, true, true) }
    @Test fun uncachedBitwardenAttachmentsAreDownloadedAndDecrypted() = runBlocking { combination(ImportDestinationKind.BITWARDEN, true, true) }

    private suspend fun combination(kind: ImportDestinationKind, intoMdbx: Boolean, discardCache: Boolean = false) {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = destination(kind)
            seed(source)
            if (discardCache) {
                val owners = importedPasswords(source).map { AttachmentOwner.password(it.id) } +
                    db.secureItemDao().getAllItems().first().filter { source.contains(it) }.map { AttachmentOwner.secureItem(it.id) }
                val facade = AttachmentContainer.facade(context)
                for (owner in owners) for (row in facade.list(owner)) {
                    assertTrue(row.sourceEnum != AttachmentSource.LOCAL)
                    val cache = row.localPath?.let { File(File(context.filesDir, "secure_attachments"), it).canonicalFile }
                    if (cache != null) {
                        check(cache.parentFile == File(context.filesDir, "secure_attachments").canonicalFile)
                        cache.delete()
                    }
                    db.attachmentDao().update(row.copy(localPath = null, wrappedCek = null, sha256Hex = null,
                        downloadState = "PENDING"))
                }
            }
            val target = if (intoMdbx) mdbx() else ImportDestination.Local
            verifyMerge(listOf(source), target)
        } } finally { fixture.close() }
    }

    @Test fun fourSourcesConsolidateIntoAnIndependentMdbxWithoutChangingSources() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val sources = ImportDestinationKind.entries.map { destination(it).also { seed(it) } }
            verifyMerge(sources, mdbx())
        } } finally { fixture.close() }
    }

    private suspend fun TransferFixture.destination(kind: ImportDestinationKind): ImportDestination = when (kind) {
        ImportDestinationKind.LOCAL -> ImportDestination.Local
        ImportDestinationKind.MDBX -> mdbx()
        ImportDestinationKind.KEEPASS -> keepass()
        ImportDestinationKind.BITWARDEN -> bitwarden()
    }

    private fun ImportDestination.sourceKey(): String = when (kind) {
        ImportDestinationKind.LOCAL -> "monica"
        ImportDestinationKind.MDBX -> "mdbx:$databaseId"
        ImportDestinationKind.KEEPASS -> "keepass:$databaseId"
        ImportDestinationKind.BITWARDEN -> "bitwarden:$databaseId"
    }

    private fun TransferFixture.service() = DedupMergeService(passwords, secureItems, passkeys,
        CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(),
        db.bitwardenVaultDao(), security, AppLocaleStringResolver(context), db, DedupAttachmentSupport(context, db))

    private val payload = ByteArray(4097) { (it * 31).toByte() }

    @Test fun targetThatCannotUnlockCreatesNoCopiesAndDoesNotAlterAnySource() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = keepass()
            seed(source)
            val passwordsBefore = importedPasswords(source)
            val keysBefore = importedKeys(source)
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val database = db.localMdbxDatabaseDao().getDatabaseById(target.databaseId)!!
            Mdbx2NativeReadSessions.clear()
            db.localMdbxDatabaseDao().updateDatabase(database.copy(encryptedPassword = security.encryptData("Incorrect synthetic password")))
            val result = merger.executePlan(plan)
            assertEquals(result.toString(), 0, result.insertedItems)
            assertEquals(5, result.failedItems)
            assertTrue(importedPasswords(target).isEmpty())
            assertTrue(importedKeys(target).isEmpty())
            assertEquals(passwordsBefore, importedPasswords(source))
            assertEquals(keysBefore, importedKeys(source))
            assertOriginalKey(importedKeys(source).single())
            db.localMdbxDatabaseDao().updateDatabase(database)
        } } finally { fixture.close() }
    }

    @Test fun sameNameDifferentContentSurvivesAndIncompleteTargetDoesNotCountAsADuplicate() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = keepass()
            seed(source)
            val incoming = importedPasswords(source).single()
            val existingId = passwords.insertPasswordEntry(ImportDestination.Local.password(incoming))
            val facade = AttachmentContainer.facade(context)
            // A metadata-only matching target must not hide the incoming attachment.
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MonicaLocal)
            assertEquals(1, plan.writablePasswords)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(incoming.id), AttachmentSource.LOCAL, "fixture.bin",
                "application/octet-stream", byteArrayOf(1, 2, 3), true))
            val result = merger.executePlan(plan)
            assertEquals(result.failures.toString(), 5, result.insertedItems)
            val copy = importedPasswords(ImportDestination.Local).single { it.id != existingId }
            val attachments = facade.list(AttachmentOwner.password(copy.id))
            assertEquals(2, attachments.size)
            assertEquals(setOf(payload.toList(), listOf<Byte>(1, 2, 3)), attachments.map {
                facade.readAttachmentBytes(it.id, 8192).toList()
            }.toSet())
            assertTrue(facade.list(AttachmentOwner.password(existingId)).isEmpty())
            assertEquals(0, merger.executePlan(plan).insertedItems)
        } } finally { fixture.close() }
    }

    @Test fun unreadableAttachmentRollsBackOnlyItsNewOwnerAndRetainsTheSource() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = keepass()
            seed(source)
            val incoming = importedPasswords(source).single()
            val facade = AttachmentContainer.facade(context)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(incoming.id), AttachmentSource.LOCAL, "missing.bin",
                "application/octet-stream", payload, true)).also {
                val directory = File(context.filesDir, "secure_attachments").canonicalFile
                val file = File(directory, it.localPath!!).canonicalFile
                check(file.parentFile == directory)
                assertTrue(file.delete())
            }
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf(source.sourceKey()), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val result = merger.executePlan(plan)
            assertEquals(1, result.failedPasswords)
            assertEquals(4, result.insertedItems)
            assertTrue(importedPasswords(target).isEmpty())
            assertEquals(incoming, importedPasswords(source).single())
            assertFalse(mdbx.readStoredEntries(target.databaseId).any { !it.deleted && it.entryType == "login" })
        } } finally { fixture.close() }
    }

    @Test fun corruptedAttachmentAfterAnEarlierCopyLeavesNoTargetBlobs() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val source = keepass()
            seed(source)
            val incoming = importedPasswords(source).single()
            val facade = AttachmentContainer.facade(context)
            val corrupt = facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(incoming.id), AttachmentSource.LOCAL, "corrupt.bin",
                "application/octet-stream", payload, true))
            val path = File(File(context.filesDir, "secure_attachments"), corrupt.localPath!!)
            val bytes = path.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            path.writeBytes(bytes)
            val target = mdbx()
            val result = service().executePlan(service().buildPlan(setOf(source.sourceKey()),
                DedupMergeTarget.MdbxDatabase(target.databaseId, "Target")))
            assertEquals(1, result.failedPasswords)
            assertTrue(importedPasswords(target).isEmpty())
            val liveAttachments = mdbx.readStoredAttachments(target.databaseId)
            assertEquals("Only the successfully copied note attachment remains", 1, liveAttachments.size)
            assertEquals(incoming, importedPasswords(source).single())
        } } finally { fixture.close() }
    }

    @Test fun legacyPhotosAndCardFacesHaveIndependentCopiesAndPortableMdbxContent() = runBlocking {
        val fixture = TransferFixture()
        val imageManager = ImageManager(fixture.context)
        val imageFiles = mutableListOf<String>()
        try { with(fixture) {
            val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val path = checkNotNull(imageManager.saveImage(bitmap)).also { imageFiles += it }
            bitmap.recycle()
            val photoBytes = checkNotNull(imageManager.readImageBytes(path))
            val face = CardFaceAttachment.newFileName()
            val card = SecureItem(title = "$prefix-photo-card", itemType = ItemType.BANK_CARD,
                itemData = Json.encodeToString(BankCardData("4111111111111111", "Alice", "09", "2030",
                    cardFace = CardFaceConfig(face))), imagePaths = Json.encodeToString(listOf(path, "")))
            val id = secureItems.insertItem(card)
            // A newer duplicate may contain the UI's empty front/back slots. It must not hide a real photo.
            secureItems.insertItem(card.copy(imagePaths = Json.encodeToString(listOf("", "")),
                updatedAt = java.util.Date(System.currentTimeMillis() + 1000)))
            val facade = AttachmentContainer.facade(context)
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(AttachmentOwner.secureItem(id),
                AttachmentSource.LOCAL, face, "image/jpeg", photoBytes, true))
            val target = mdbx()
            val merger = service()
            val plan = merger.buildPlan(setOf("monica"), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"))
            val newest = merger.buildPlan(setOf("monica"), DedupMergeTarget.MdbxDatabase(target.databaseId, "Target"), DedupConflictPolicy.NEWEST)
            assertEquals(card.imagePaths, newest.previewSecureItems.single { it.item.title == card.title }.item.imagePaths)
            assertEquals(1, merger.executePlan(plan).insertedSecureItems)
            val copy = db.secureItemDao().getAllItems().first().single { target.contains(it) && it.title == card.title }
            val copiedPath = Json.decodeFromString<List<String>>(copy.imagePaths).first().also { imageFiles += it }
            assertNotEquals(path, copiedPath)
            assertArrayEquals(photoBytes, imageManager.readImageBytes(copiedPath))
            val attachments = facade.list(AttachmentOwner.secureItem(copy.id))
            assertEquals(2, attachments.size)
            assertArrayEquals(photoBytes, facade.readAttachmentBytes(attachments.single { it.fileName == face }.id, 8192))
            Mdbx2NativeReadSessions.clear()
            assertEquals(2, mdbx.readStoredAttachments(target.databaseId).size)
            imageManager.deleteImage(copiedPath)
            LegacyImageAttachmentSupport(context).restoreMissing(copy, facade)
            assertArrayEquals(photoBytes, imageManager.readImageBytes(copiedPath))
            assertEquals(0, merger.executePlan(plan).insertedItems)
            imageManager.deleteImage(path)
            assertArrayEquals(photoBytes, imageManager.readImageBytes(copiedPath))
        } } finally {
            fixture.close()
            imageManager.deleteImages(imageFiles)
        }
    }

    private suspend fun TransferFixture.seed(target: ImportDestination) {
        val original = decoded().items.single()
        val exchange = CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(
            original.copy(passkeys = emptyList()),
            original.copy(id = "standalone-key", title = "$prefix-key", logins = emptyList(), notes = "")
        ), "Fixture", setOf("basic-auth", "passkey", "note")))
        val imported = importer.importExchange(exchange, target)
        assertEquals(imported.toString(), 2, imported.imported)
        assertNull(importedKeys(target).single().boundPasswordId)

        val (noteData, noteContent) = NoteContentCodec.encode("Keep this multilingual note: 换行 Zażółć")
        val file = File(root, "items.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, data: JSONObject) {
                zip.putNextEntry(ZipEntry(path)); zip.write(data.toString().toByteArray()); zip.closeEntry()
            }
            put("notes/note.json", JSONObject().put("id", 710001).put("title", "$prefix-note")
                .put("itemData", noteData).put("notes", noteContent))
            put("totp/totp.json", JSONObject().put("id", 710002).put("title", "$prefix-otp")
                .put("itemData", JSONObject().put("secret", "JBSWY3DPEHPK3PXP")
                    .put("issuer", "Fixture").put("accountName", "alice").toString()))
            put("bank_cards/card.json", JSONObject().put("id", 710003).put("title", "$prefix-card")
                .put("itemData", JSONObject().put("cardNumber", "4111111111111111").put("cardholderName", "Alice")
                    .put("expiryMonth", "09").put("expiryYear", "2030").put("cvv", "123").toString()))
        }
        assertEquals(3, model.importZipBackup(Uri.fromFile(file), null, target).getOrThrow())
        target.bitwardenId?.let { id ->
            val vault = db.bitwardenVaultDao().getVaultById(id)!!
            val result = BitwardenSyncService(context).uploadLocalEntries(vault, accessToken, vaultKey)
            assertTrue(result.toString(), result is UploadResult.Success && result.failed == 0)
            assertEquals(5, remote.created.size)
        }
        val password = importedPasswords(target).single()
        val note = db.secureItemDao().getAllItems().first().single {
            target.contains(it) && it.title == "$prefix-note"
        }
        val facade = AttachmentContainer.facade(context)
        suspend fun add(owner: AttachmentOwner, keepassUuid: String?, cipherId: String?) {
            val vault = target.bitwardenId?.let { db.bitwardenVaultDao().getVaultById(it)!! }
            val bitwardenContext = vault?.let {
                BitwardenRepository.getInstance(context).getAttachmentBitwardenContext(it, cipherId)
            }
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                owner = owner, source = when (target.kind) {
                    ImportDestinationKind.KEEPASS -> AttachmentSource.KEEPASS
                    ImportDestinationKind.BITWARDEN -> AttachmentSource.BITWARDEN
                    else -> AttachmentSource.LOCAL
                }, fileName = "fixture.bin", mimeType = "application/octet-stream", bytes = payload,
                isPlusActivated = true, bitwardenPremium = true, kdbxSoftLimitAccepted = true,
                bitwardenContext = bitwardenContext,
                keepassContext = target.keepassId?.let { AttachmentFacade.KeePassContext(it, keepassUuid!!) }))
        }
        add(AttachmentOwner.password(password.id), password.keepassEntryUuid, password.bitwardenCipherId)
        add(AttachmentOwner.secureItem(note.id), note.keepassEntryUuid, note.bitwardenCipherId)
    }

    private suspend fun TransferFixture.verifyMerge(sources: List<ImportDestination>, target: ImportDestination) {
        val passwordsBefore = db.passwordEntryDao().getAllPasswordEntriesSync().filter { p -> sources.any { it.contains(p) } }
        val itemsBefore = db.secureItemDao().getAllItems().first().filter { p -> sources.any { it.contains(p) } }
        val keysBefore = passkeys.getAllPasskeysSync().filter { p -> sources.any { it.contains(p) } }
        val sourceFiles = sources.mapNotNull { it.keepassId }.associateWith { keepassFile(it).readBytes() }
        val sourceMdbx = sources.mapNotNull { it.mdbxId }.associateWith { mdbx.readStoredEntries(it) }
        val ciphersBefore = remote.created.map { it.toString() }
        val merger = service()
        val selectedTarget = target.mdbxId?.let { DedupMergeTarget.MdbxDatabase(it, "Target") }
            ?: DedupMergeTarget.MonicaLocal
        val sourceKeys = sources.map { it.sourceKey() }.toSet()
        val plan = merger.buildPlan(sourceKeys, selectedTarget)
        assertEquals(plan.toString(), 5, plan.writableItems)
        assertEquals(0, plan.unsupportedSourcePasskeys)
        val result = merger.executePlan(plan)
        assertEquals(result.failures.toString(), 5, result.insertedItems)
        assertEquals(0, result.failedItems)
        val password = importedPasswords(target).single()
        assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(password.password))
        assertEquals(rawUsername, password.username)
        assertEquals(website, password.website)
        assertOriginalKey(importedKeys(target).single())
        val items = db.secureItemDao().getAllItems().first().filter { target.contains(it) && it.title.startsWith(prefix) }
        assertEquals(setOf(ItemType.NOTE, ItemType.TOTP, ItemType.BANK_CARD), items.map { it.itemType }.toSet())
        val note = items.single { it.itemType == ItemType.NOTE }
        val facade = AttachmentContainer.facade(context)
        val targets = listOf(AttachmentOwner.password(password.id), AttachmentOwner.secureItem(note.id))
        targets.forEach { owner ->
            val copies = facade.list(owner)
            assertEquals("Merged owner $owner must retain exactly one complete attachment", 1, copies.size)
            assertArrayEquals(payload, facade.readAttachmentBytes(copies.single().id, 8192))
            assertEquals(AttachmentSource.LOCAL, copies.single().sourceEnum)
            assertNull(copies.single().bitwardenAttachmentId)
            assertNull(copies.single().keepassBinaryRef)
        }
        target.mdbxId?.let { id ->
            Mdbx2NativeReadSessions.clear()
            assertEquals(5, mdbx.readStoredEntries(id).count { !it.deleted })
            assertEquals(2, mdbx.readStoredAttachments(id).size)
            val key = JSONObject(mdbx.readStoredEntries(id).single { it.entryType == "passkey" && !it.deleted }.payloadJson)
            assertKeySigns(key.getString("private_key_alias"))
        }
        val repeat = merger.executePlan(plan)
        assertEquals(repeat.toString(), 0, repeat.insertedItems)
        // Existing passkeys are reported per source record; the password and three secure-item groups are consolidated.
        assertEquals(4 + sources.size, repeat.skippedExistingItems)
        assertEquals(passwordsBefore, db.passwordEntryDao().getAllPasswordEntriesSync().filter { p -> sources.any { it.contains(p) } })
        assertEquals(itemsBefore, db.secureItemDao().getAllItems().first().filter { p -> sources.any { it.contains(p) } })
        assertEquals(keysBefore, passkeys.getAllPasskeysSync().filter { p -> sources.any { it.contains(p) } })
        sourceFiles.forEach { (id, bytes) -> assertArrayEquals(bytes, keepassFile(id).readBytes()) }
        sourceMdbx.forEach { (id, entries) -> assertEquals(entries, mdbx.readStoredEntries(id)) }
        assertEquals(ciphersBefore, remote.created.map { it.toString() })
        assertFalse(merger.buildPlan(setOf(target.sourceKey()), selectedTarget).writableItems > 0)
    }
}
