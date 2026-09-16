package takagi.ru.monica.credentialexchange

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.models.Group
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.utf8Size
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.backup.PortableAttachmentBackup
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.data.*
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.transfer.*
import takagi.ru.monica.utils.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class DatabaseTransferInstrumentedTest {
    private val archivePassword = "Synthetic ZIP password"
    private val payload = ByteArray(8_192) { (it * 37).toByte() }

    private suspend fun scenario(block: suspend TransferFixture.() -> Unit) {
        val fixture = TransferFixture()
        try { fixture.block() } finally { fixture.close() }
    }

    /** A legacy Monica archive, so migration tests do not just round-trip the new writer. */
    private fun TransferFixture.archive(suffix: String): File {
        val passwordId = 771001L
        val noteId = 771002L
        val key = checkNotNull(CxfPasskeyMaterial.decode(pair.private.encoded))
        val password = JSONObject().put("id", passwordId).put("title", "$prefix-$suffix")
            .put("username", rawUsername).put("password", rawPassword).put("website", website)
            .put("creditCardNumber", "4111111111111111").put("creditCardCVV", "123")
            .put("customFields", org.json.JSONArray().put(JSONObject().put("title", "Extra")
                .put("value", "field-$suffix").put("isProtected", true)))
        val passkey = JSONObject().put("credentialId", credentialId).put("rpId", rpId).put("rpName", "$prefix-$suffix")
            .put("userId", userHandle).put("userName", rawUsername).put("userDisplayName", "Alice")
            .put("publicKeyAlgorithm", -7).put("publicKey", key.cosePublicKey)
            .put("privateKeyAlias", Base64.getEncoder().encodeToString(pair.private.encoded))
            .put("boundPasswordId", passwordId).put("signCount", 7L).put("passkeyMode", "BW_COMPAT")
        val note = JSONObject().put("id", noteId).put("title", "$prefix-$suffix-note")
            .put("itemData", "{\"content\":\"Synthetic note\"}").put("notes", "Synthetic note")
        val attachment = PortableAttachmentBackup.Entry(parentPasswordId = passwordId, fileName = "$suffix.bin",
            mimeType = "application/octet-stream", sizeBytes = payload.size.toLong(),
            sha256Hex = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
            payloadPath = "attachments_portable/payload.bin", createdAt = 1, updatedAt = 1)
        val plain = File(root, "$suffix.zip")
        ZipOutputStream(plain.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("passwords/password.json", password.toString().toByteArray())
            put("notes/note.json", note.toString().toByteArray())
            put("passkeys/key.json", passkey.toString().toByteArray())
            put(PortableAttachmentBackup.MANIFEST_ENTRY, PortableAttachmentBackup.encodeManifest(listOf(attachment)).toByteArray())
            put(attachment.payloadPath, payload)
        }
        return File(root, "$suffix.enc.zip").also {
            EncryptionHelper.encryptFile(plain, it, archivePassword, AppLocaleStringResolver(context)).getOrThrow()
        }
    }

    @Test fun encryptedZipAsksBeforeCopyingAndWrongPasswordWritesNothingForEveryDestination() = runBlocking {
        scenario {
            val helper = WebDavHelper(context)
            val previous = helper.getEncryptionConfig()
            helper.setEncryptionConfig(true, archivePassword)
            try {
                val largeHeader = File(root, "large.enc.zip")
                RandomAccessFile(largeHeader, "rw").use { file ->
                    file.write("MONICA_ENC_V1".toByteArray())
                    file.setLength(64L * 1024 * 1024)
                }
                for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                    val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("import_temp_") }.toSet()
                    val missing = model.importZipBackup(Uri.fromFile(largeHeader), destination = target)
                    assertTrue("${target.kind}: explicit ZIP password is required even when a cloud password is saved",
                        missing.exceptionOrNull() is WebDavHelper.PasswordRequiredException)
                    assertEquals(before, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("import_temp_") }.toSet())
                    val file = archive(target.kind.name)
                    assertTrue(model.importZipBackup(Uri.fromFile(file), "wrong password", target).isFailure)
                    assertTrue(importedPasswords(target).isEmpty())
                    assertTrue(importedKeys(target).isEmpty())
                    target.keepassId?.let { assertTrue(keepassEntries(it).isEmpty()) }
                    target.mdbxId?.let { assertTrue(mdbx.readStoredEntries(it).isEmpty()) }
                    assertEquals("${target.kind}: correct password retry", 3,
                        model.importZipBackup(Uri.fromFile(file), archivePassword, target).getOrThrow())
                    assertEquals(0, model.lastImportSummary.value!!.failed)
                    val key = importedKeys(target).single()
                    assertEquals(7L, key.signCount)
                    assertKeySigns(PasskeyPrivateKeyStore.resolve(context, key.privateKeyAlias))
                    assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(importedPasswords(target).single().password))
                    val imported = importedPasswords(target).single()
                    // PasswordEntry payment fields are already readable to the detail/copy UI;
                    // the KDBX writer itself encrypts its protected fields.
                    assertEquals("4111111111111111", imported.creditCardNumber)
                    assertEquals("123", imported.creditCardCVV)
                    assertEquals(0, model.importZipBackup(Uri.fromFile(file), archivePassword, target).getOrThrow())
                    assertEquals(key, importedKeys(target).single())
                }
            } finally { helper.setEncryptionConfig(previous.enabled, previous.password) }
        }
    }

    @Test fun zipExportIncludesOnlySelectedDatabaseWithItsKeysFieldsAndAttachments() = runBlocking {
        scenario {
            val targets = listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())
            for (target in targets) {
                model.importZipBackup(Uri.fromFile(archive(target.kind.name)), archivePassword, target).getOrThrow()
            }
            for (source in targets) {
                val phases = mutableListOf<TransferProgress>()
                val prepared = model.prepareZipBackup(backupEncryptionPassword = archivePassword, source = source,
                    progress = TransferProgressReporter { phases += it }).getOrThrow().first
                try {
                    val restored = WebDavHelper(context).restoreFromBackupFile(prepared, archivePassword,
                        restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                    try {
                        val selected = restored.content.passwords.filter { it.title.startsWith(prefix) }
                        assertEquals(source.kind.name, listOf("$prefix-${source.kind.name}"), selected.map { it.title })
                        assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(selected.single().password))
                        assertEquals("4111111111111111", selected.single().creditCardNumber)
                        assertEquals("123", selected.single().creditCardCVV)
                        assertEquals("field-${source.kind.name}", restored.content.customFieldsMap.getValue(selected.single().id).single().value)
                        val keys = restored.content.passkeys.filter { it.credentialId == credentialId }
                        assertEquals(1, keys.size)
                        assertEquals(7L, keys.single().signCount)
                        assertKeySigns(keys.single().privateKeyAlias)
                        val attachments = restored.content.portableAttachments
                        val own = attachments.entries.filter { it.parentPasswordId == selected.single().id }
                        assertEquals(listOf("${source.kind.name}.bin"), own.map { it.fileName })
                        assertArrayEquals(payload, attachments.payloads.getValue(own.single().payloadPath).readBytes())
                        assertEquals(MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
                            own.single().sha256Hex)
                        assertFalse(restored.monicaConfigDetected)
                        assertTrue(phases.any { it.phase == TransferPhase.PACKING && it.fraction == 1f })
                    } finally { restored.content.portableAttachments.payloads.values.forEach(File::delete) }
                    assertEquals("Export must not rewrite the live counter", 7L, importedKeys(source).single().signCount)
                } finally { prepared.delete() }
            }
        }
    }

    @Test fun nativeDatabaseExportDoesNotDependOnRoomProjection() = runBlocking {
        scenario {
            for (source in listOf(keepass(), mdbx())) {
                importer.importExchange(decoded(source.kind.name), source)
                val key = importedKeys(source).single()
                val password = importedPasswords(source).single()
                db.passkeyDao().delete(key)
                db.passwordEntryDao().deletePasswordEntryById(password.id)
                try {
                    val prepared = model.prepareZipBackup(backupEncryptionPassword = archivePassword, source = source,
                        preferences = BackupPreferences(includeImages = false)).getOrThrow().first
                    try {
                        val restored = WebDavHelper(context).restoreFromBackupFile(prepared, archivePassword,
                            restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
                        assertEquals(1, restored.passwords.size)
                        assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(restored.passwords.single().password))
                        assertEquals(1, restored.passkeys.size)
                        assertKeySigns(restored.passkeys.single().privateKeyAlias)
                    } finally { prepared.delete() }
                } finally { db.passkeyDao().insert(key.copy(id = 0)) }
            }
        }
    }

    @Test fun mdbxImportsUseOneRustBatchInsteadOfPerRowWrites() = runBlocking {
        scenario {
            val baseline = mdbx()
            val target = mdbx()
            val size = 24
            fun rows(suffix: String, destination: ImportDestination) = (0 until size).map { i ->
                PasswordEntry(title = "$prefix-$suffix-$i", website = website, username = rawUsername,
                    password = security.encryptData(rawPassword), mdbxDatabaseId = destination.databaseId)
            }
            suspend fun commits(id: Long) = mdbx.withReadVaultForSync(id) { _, vault -> vault.listCommitHistory(100u, null).items.size }
            val beforeNormal = commits(baseline.databaseId)
            val normalMs = measureTimeMillis {
                val repo = PasswordRepository(db.passwordEntryDao(), mdbxRepository = mdbx)
                for (row in rows("individual", baseline)) repo.insertPasswordEntry(row)
            }
            val beforeBatch = commits(target.databaseId)
            val progress = mutableListOf<TransferProgress>()
            val batchMs = measureTimeMillis {
                val result = importer.apply(BackupContent(rows("batch", target), emptyList()), target,
                    progress = TransferProgressReporter { progress += it })
                assertEquals(size, result.imported)
                assertEquals(0, result.failed)
            }
            val individualCommits = commits(baseline.databaseId) - beforeNormal
            val batchCommits = commits(target.databaseId) - beforeBatch
            assertTrue("Batch must make fewer native commits", batchCommits < individualCommits)
            assertEquals(size, mdbx.readStoredEntries(target.databaseId).count { !it.deleted })
            assertTrue(progress.any { it.phase == TransferPhase.WRITING && it.completed == size.toLong() && it.total == size.toLong() })
            val report = File(context.getExternalFilesDir(null), "database-transfer-validation").apply { mkdirs() }
            File(report, "mdbx-benchmark.txt").writeText("Synthetic passwords: $size\nIndividual: ${normalMs}ms, $individualCommits commits\nBatch: ${batchMs}ms, $batchCommits commits\n")
        }
    }

    @Test fun cancellationAfterNativeCommitKeepsAcknowledgedRowsAndRetryDoesNotDuplicate() = runBlocking {
        scenario {
            val target = mdbx()
            val content = TargetedImportCoordinator.exchangeContent(decoded()).copy(passkeys = emptyList())
            try {
                importer.apply(content, target, progress = TransferProgressReporter {
                    if (it.phase == TransferPhase.WRITING && it.completed > 0) throw CancellationException("Synthetic cancellation after acknowledgement")
                })
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(1, importedPasswords(target).size)
            assertEquals(1, mdbx.readStoredEntries(target.databaseId).count { !it.deleted })
            assertEquals(0, importer.apply(content, target).imported)
        }
    }

    @Test fun kdbxExportScopesEverySourceAndPreservesSigningKeys() = runBlocking {
        scenario {
            val targets = listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())
            for (target in targets) importer.importExchange(decoded(target.kind.name), target)
            for (source in targets) {
                val output = File(root, "export-${source.kind}.kdbx")
                model.exportKdbxBackup(Uri.fromFile(output), archivePassword, source).getOrThrow()
                val native = output.inputStream().use { KeePassDatabase.decode(it,
                    Credentials.from(EncryptedValue.fromString(archivePassword))) }
                fun entries(group: Group): List<app.keemobile.kotpass.models.Entry> = group.entries + group.groups.flatMap(::entries)
                val rows = entries(native.content.group)
                val passwordRows = rows.filter { it.fields["Title"]?.content?.startsWith(prefix) == true &&
                    it.fields["MonicaPasskeyData"] == null }
                assertEquals(listOf("$prefix-${source.kind.name}"), passwordRows.map { it.fields.getValue("Title").content })
                assertEquals(rawPassword, passwordRows.single().fields.getValue("Password").content)
                val keyField = takagi.ru.monica.keepass.KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID
                val exportedKey = rows.single { it.fields[keyField]?.content == credentialId }
                assertKeySigns(exportedKey.fields.getValue(takagi.ru.monica.keepass.KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY).content)
            }
        }
    }

    @Test fun foregroundExportFinishesAfterCallerReturnsAndPreventsDuplicateJobs() = runBlocking {
        scenario {
            val source = mdbx()
            importer.importExchange(decoded(), source)
            val output = File(root, "background.enc.zip")
            DatabaseExportJobs.dismiss()
            assertTrue(DatabaseExportJobs.start(context, source.key, Uri.fromFile(output)) { progress ->
                kotlinx.coroutines.delay(250)
                model.exportZipBackup(Uri.fromFile(output), backupEncryptionPassword = archivePassword,
                    source = source, progress = progress)
            })
            assertFalse(DatabaseExportJobs.start(context, source.key, Uri.fromFile(output)) { error("Duplicate job") })
            val result = withTimeout(90_000) {
                DatabaseExportJobs.state.first { it != null && it.status != ExportJobStatus.RUNNING }!!
            }
            assertEquals(result.message, ExportJobStatus.SUCCEEDED, result.status)
            assertTrue(output.length() > 0)
            assertTrue(EncryptionHelper.hasEncryptedFileHeader(output))
            val restored = WebDavHelper(context).restoreFromBackupFile(output, archivePassword,
                restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
            assertKeySigns(restored.passkeys.single().privateKeyAlias)
            DatabaseExportJobs.dismiss()
        }
    }

    @Test fun portableZipPreservesLiteralCipherPrefixesAcrossAllDestinations() = runBlocking {
        scenario {
            val source = mdbx()
            val samples = listOf("MDK|a literal password", "V2|not encrypted", "C2|literal",
                security.encryptData("The encrypted text itself is a password"))
            val decoded = decoded().copy(items = samples.mapIndexed { index, value ->
                decoded().items.single().copy(title = "$prefix-literal-$index", passkeys = emptyList(),
                    logins = listOf(CxfCredentialCodec.Login("alice", value)))
            })
            assertEquals(samples.size, importer.importExchange(decoded, source).imported)
            val prepared = model.prepareZipBackup(backupEncryptionPassword = archivePassword,
                source = source, preferences = BackupPreferences(includeImages = false)).getOrThrow().first
            try {
                for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                    assertEquals(samples.size, model.importZipBackup(Uri.fromFile(prepared), archivePassword, target).getOrThrow())
                    assertEquals(target.kind.name, samples, importedPasswords(target).sortedBy { it.title }
                        .map { security.decryptDataIfMonicaCiphertext(it.password) })
                }
            } finally { prepared.delete() }
        }
    }

    @Test fun nativeTokensRetainMetadataAndFavoritesAndUnsupportedTargetsReportSkipped() = runBlocking {
        scenario {
            val source = mdbx()
            val target = mdbx()
            val metadata = ApiTokenMetadata.withNotes(ApiTokenMetadata.empty(), "Synthetic token notes")
            var value = ApiTokenPayload.empty().toString()
            value = ApiTokenPayload.update(value, "provider", "fixture")
            value = ApiTokenPayload.update(value, "api_base", "https://example.invalid")
            value = ApiTokenPayload.update(value, "token", "synthetic-transfer-token")
            mdbx.saveNativeApiToken(source.databaseId, null, "$prefix-token", value, isFavorite = true, metadata = metadata)
            val prepared = model.prepareZipBackup(backupEncryptionPassword = archivePassword,
                source = source, preferences = BackupPreferences(includeImages = false)).getOrThrow().first
            try {
                assertEquals(1, model.importZipBackup(Uri.fromFile(prepared), archivePassword, target).getOrThrow())
                val restored = mdbx.readNativeApiToken(mdbx.listNativeApiTokens(target.databaseId).single())
                assertTrue(restored.summary.isFavorite)
                assertEquals("synthetic-transfer-token", ApiTokenPayload.text(ApiTokenPayload.decode(restored.payload), "token"))
                assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(metadata),
                    kotlinx.serialization.json.Json.parseToJsonElement(restored.extras!!.payload))
                assertEquals(0, model.importZipBackup(Uri.fromFile(prepared), archivePassword, target).getOrThrow())
                assertEquals(1, model.lastImportSummary.value!!.skipped)
                for (unsupported in listOf(ImportDestination.Local, keepass(), bitwarden())) {
                    assertEquals(0, model.importZipBackup(Uri.fromFile(prepared), archivePassword, unsupported).getOrThrow())
                    assertEquals(1, model.lastImportSummary.value!!.skipped)
                    assertEquals(0, model.lastImportSummary.value!!.failed)
                }
            } finally { prepared.delete() }
        }
    }

    @Test fun largeMdbxImportCancelsBetweenBatchesAndRetriesWithoutDuplicates() = runBlocking {
        scenario {
            val target = mdbx()
            val size = 4_097 // Exceeds even one native operation's hard command ceiling.
            val encryptedPassword = security.encryptData(rawPassword)
            val rows = List(size) { index -> PasswordEntry(id = index + 1L,
                title = "$prefix-large-$index", username = rawUsername, website = website, password = encryptedPassword) }
            val content = BackupContent(rows, emptyList())
            val importingJob = Job()
            try {
                try {
                    withContext(importingJob) {
                        importer.apply(content, target, progress = TransferProgressReporter { value ->
                            if (value.phase == TransferPhase.WRITING && value.completed > 0) importingJob.cancel()
                        })
                    }
                    fail("Cancellation must stop before the next native batch")
                } catch (_: CancellationException) { }
                val committed = importedPasswords(target)
                assertEquals(256, committed.size)
                assertEquals(committed.size, mdbx.readStoredEntries(target.databaseId).count { !it.deleted })
                val progress = mutableListOf<Long>()
                val result = importer.apply(content, target, progress = TransferProgressReporter {
                    if (it.phase == TransferPhase.WRITING && it.completed > 0) progress += it.completed
                })
                assertEquals(size - committed.size, result.imported)
                assertEquals(committed.size, result.skipped)
                assertEquals(0, result.failed)
                assertEquals(size, importedPasswords(target).size)
                val native = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
                assertEquals(rows.map { it.title }.toSet(), native.map { it.title }.toSet())
                assertTrue(native.all { JSONObject(it.payloadJson).getString("password_plain") == rawPassword })
                assertTrue((listOf(0L) + progress).zipWithNext().all { (before, after) -> after - before in 1..256 })
                val report = File(context.getExternalFilesDir(null), "database-transfer-validation").apply { mkdirs() }
                File(report, "mdbx-large-import.txt").writeText(
                    "Rows: $size\nCommitted before cancellation: ${committed.size}\n" +
                        "Retry imported: ${result.imported}; skipped: ${result.skipped}; failed: ${result.failed}\n" +
                        "Retry batch acknowledgements: ${progress.joinToString()}\n")
            } finally {
                importingJob.cancel()
                // The fixture owns this database. Remove its projections in Room directly;
                // fixture.close() then deletes its native file without thousands of delete commits.
                db.passwordEntryDao().deleteAllByMdbxDatabaseId(target.databaseId)
            }
        }
    }

    @Test fun mdbxImportSplitsLargeUtf8PayloadsAndPreservesEscapedText() = runBlocking {
        scenario {
            val target = mdbx()
            val checkpoint = mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.incrementalSyncCheckpoint() }
            val notes = "漢🔑\"\\\n".repeat(40_000)
            val encryptedPassword = security.encryptData(rawPassword)
            val rows = List(20) { index -> PasswordEntry(id = index + 1L, title = "$prefix-payload-$index",
                username = rawUsername, password = encryptedPassword, website = website, notes = notes) }
            val acknowledged = mutableListOf<Long>()
            try {
                val result = importer.apply(BackupContent(rows, emptyList()), target, progress = TransferProgressReporter {
                    if (it.phase == TransferPhase.WRITING && it.completed > 0) acknowledged += it.completed
                })
                assertEquals(rows.size, result.imported)
                assertEquals(0, result.failed)
                assertTrue("Payload bytes must split this import even with fewer than 256 rows", acknowledged.size > 1)
                assertEquals(rows.size.toLong(), acknowledged.last())
                val native = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
                assertEquals(rows.size, native.size)
                assertTrue(native.all { JSONObject(it.payloadJson).getString("notes") == notes })
                assertTrue(importedPasswords(target).all { it.notes == notes })
                var segments = 0
                var wireBytes = 0L
                val syncStarted = System.nanoTime()
                mdbx.withReadVaultForSync(target.databaseId) { _, vault ->
                    val expectedEnd = vault.incrementalSyncCheckpoint()
                    var nextBase = checkpoint
                    var resume: uniffi.mdbx_ffi.MdbxIncrementalSyncResume? = null
                    do {
                        val file = File(root, "large-text-${segments++}.mdbxsync")
                        try {
                            val segment = vault.exportIncrementalSyncSegment(file.absolutePath, nextBase, resume, 1u)
                            assertSameSegment(segment, vault.inspectIncrementalSyncSegment(file.absolutePath))
                            assertEquals(nextBase, segment.base)
                            assertTrue("Every nonfinal segment must advance its inventory checkpoint",
                                segment.isLast || segment.result != nextBase)
                            wireBytes += segment.fileSizeBytes.toLong()
                            nextBase = segment.result
                            resume = segment.nextResume
                            assertTrue("Sync pagination must advance", segment.isLast || resume != null)
                            if (segment.isLast) break
                            assertTrue("Sync pagination must stay bounded", segments <= rows.size + 2)
                        } finally { file.delete() }
                    } while (true)
                    assertEquals("Export must cover every committed batch", expectedEnd, nextBase)
                }
                val syncElapsedMs = (System.nanoTime() - syncStarted) / 1_000_000
                val report = File(context.getExternalFilesDir(null), "database-transfer-validation").apply { mkdirs() }
                File(report, "mdbx-large-text.txt").writeText(
                    "Rows: ${rows.size}\nBatch acknowledgements: ${acknowledged.joinToString()}\n" +
                        "Incremental sync segments exported and inspected: $segments\n" +
                        "Sync wire bytes: $wireBytes; export and inspection ms: $syncElapsedMs\n" +
                        "All UTF-8 notes and passwords preserved.\n")
            } finally {
                db.passwordEntryDao().deleteAllByMdbxDatabaseId(target.databaseId)
            }
        }
    }

    @Test fun indivisibleMdbxSyncLimitFailsWithoutCommittingAndNextImportStillWorks() = runBlocking {
        scenario {
            val target = mdbx()
            val checkpoint = mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.incrementalSyncCheckpoint() }
            val row = PasswordEntry(title = "$prefix-too-large", username = rawUsername, website = website,
                password = security.encryptData(rawPassword), notes = "x".repeat(1_800_000))
            val failed = importer.apply(BackupContent(listOf(row), emptyList()), target)
            assertEquals(0, failed.imported)
            assertEquals("Count the rejected entry once", 1, failed.failed)
            assertTrue(importedPasswords(target).isEmpty())
            assertTrue(mdbx.readStoredEntries(target.databaseId).isEmpty())
            assertEquals(checkpoint, mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.incrementalSyncCheckpoint() })
            val valid = row.copy(notes = "Small valid retry")
            assertEquals(1, importer.apply(BackupContent(listOf(valid), emptyList()), target).imported)
            assertEquals(valid.notes, importedPasswords(target).single().notes)
        }
    }

    @Test fun mdbxImportRetriesLargeTitleGroupsOnlyAfterNativeRollback() = runBlocking {
        scenario {
            val target = mdbx()
            val before = mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.listCommitHistory(100u, null).items.size }
            // Each title fits the 64 KiB presentation limit. Together the encrypted
            // titles exceed the sync limit while the entry payloads fit one batch.
            val rows = List(40) { index -> PasswordEntry(title = "$prefix-${"x".repeat(50_000)}-$index",
                username = rawUsername, website = website, password = security.encryptData(rawPassword)) }
            val acknowledgements = mutableListOf<Long>()
            try {
                val result = importer.apply(BackupContent(rows, emptyList()), target, progress = TransferProgressReporter {
                    if (it.phase == TransferPhase.WRITING && it.completed > 0) acknowledgements += it.completed
                })
                assertEquals(rows.size, result.imported)
                assertEquals(0, result.failed)
                assertEquals(listOf(20L, 40L), acknowledgements)
                assertEquals(rows.map { it.title }.toSet(), importedPasswords(target).map { it.title }.toSet())
                assertEquals(rows.size, mdbx.readStoredEntries(target.databaseId).size)
                val after = mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.listCommitHistory(100u, null).items.size }
                assertEquals("The failed parent must not leave a commit or acknowledge rows", 2, after - before)
            } finally {
                db.passwordEntryDao().deleteAllByMdbxDatabaseId(target.databaseId)
            }
        }
    }

    @Test fun mdbxRejectsTitlesBeyondNativeUtf8LimitWithoutCommitting() = runBlocking {
        scenario {
            val target = mdbx()
            val checkpoint = mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.incrementalSyncCheckpoint() }
            val remaining = (65_536L - prefix.utf8Size()).toInt()
            val title = prefix + "漢".repeat(remaining / 3) + "x".repeat(remaining % 3)
            val row = PasswordEntry(title = title + "a", username = rawUsername, website = website,
                password = security.encryptData(rawPassword))
            try {
                val failed = importer.apply(BackupContent(listOf(row), emptyList()), target)
                assertEquals(0, failed.imported)
                assertEquals(1, failed.failed)
                assertTrue(importedPasswords(target).isEmpty())
                assertTrue(mdbx.readStoredEntries(target.databaseId).isEmpty())
                assertEquals(checkpoint, mdbx.withReadVaultForSync(target.databaseId) { _, vault -> vault.incrementalSyncCheckpoint() })
                val accepted = importer.apply(BackupContent(listOf(row.copy(title = title)), emptyList()), target)
                assertEquals(1, accepted.imported)
                assertEquals(0, accepted.failed)
                assertEquals(title, importedPasswords(target).single().title)
                assertEquals(title, mdbx.readStoredEntries(target.databaseId).single().title)
            } finally {
                db.passwordEntryDao().deleteAllByMdbxDatabaseId(target.databaseId)
            }
        }
    }

    private fun assertSameSegment(
        expected: uniffi.mdbx_ffi.MdbxIncrementalSyncSegmentInfo,
        actual: uniffi.mdbx_ffi.MdbxIncrementalSyncSegmentInfo,
    ) {
        assertEquals(expected.vaultId, actual.vaultId)
        assertEquals(expected.sourceDeviceId, actual.sourceDeviceId)
        assertEquals(expected.transferId, actual.transferId)
        assertEquals(expected.segmentIndex, actual.segmentIndex)
        assertEquals(expected.isLast, actual.isLast)
        assertEquals(expected.base, actual.base)
        assertEquals(expected.result, actual.result)
        assertEquals(expected.commitCount, actual.commitCount)
        assertEquals(expected.deltaCount, actual.deltaCount)
        assertEquals(expected.fileSizeBytes, actual.fileSizeBytes)
        assertArrayEquals(expected.payloadSha256, actual.payloadSha256)
        assertEquals(expected.nextResume?.transferId, actual.nextResume?.transferId)
        assertEquals(expected.nextResume?.nextSegmentIndex, actual.nextResume?.nextSegmentIndex)
        assertArrayEquals(expected.nextResume?.previousSegmentSha256, actual.nextResume?.previousSegmentSha256)
    }

    @Test fun largeKdbxImportPreservesCustomFieldsAcrossRoomQueryBatches() = runBlocking {
        scenario {
            val target = keepass()
            val encryptedPassword = security.encryptData(rawPassword)
            val rows = List(1_001) { index -> PasswordEntry(id = index + 1L, title = "$prefix-fields-${index + 1}",
                username = rawUsername, password = encryptedPassword, website = website) }
            val fields = rows.associate { row -> row.id to listOf(CustomFieldBackupEntry(
                title = "Bulk field", value = "value-${row.id}", isProtected = true)) }
            try {
                val result = importer.apply(BackupContent(rows, emptyList(), customFieldsMap = fields), target)
                assertEquals(rows.size, result.imported)
                assertEquals(0, result.failed)
                val native = keepassEntries(target.databaseId).filter { it.fields["Title"]?.content?.startsWith(prefix) == true }
                assertEquals(rows.size, native.size)
                native.forEach { entry ->
                    val originalId = entry.fields.getValue("Title").content.substringAfterLast('-')
                    assertEquals("value-$originalId", entry.fields.getValue("Bulk field").content)
                    assertEquals(rawPassword, entry.fields.getValue("Password").content)
                }
            } finally {
                db.passwordEntryDao().deleteByKeePassDatabaseId(target.databaseId)
            }
        }
    }

    @Test fun selectedTrashStaysDeletedAndRepeatedImportDoesNotMergeItIntoActiveRows() = runBlocking {
        scenario {
            val source = mdbx()
            val rows = listOf(false, true).mapIndexed { i, deleted ->
                PasswordEntry(id = 9100L + i, title = "$prefix-trash", website = website,
                    username = rawUsername, password = security.encryptData(rawPassword),
                    isDeleted = deleted, deletedAt = if (deleted) java.util.Date(1_000L) else null)
            }
            assertEquals(2, importer.apply(BackupContent(rows, emptyList()), source).imported)
            val prepared = model.prepareZipBackup(backupEncryptionPassword = archivePassword, source = source,
                preferences = BackupPreferences(includeImages = false, includeTrash = true)).getOrThrow().first
            try {
                for (target in listOf(ImportDestination.Local, mdbx(), keepass(), bitwarden())) {
                    val supportsTrash = target.kind in setOf(ImportDestinationKind.LOCAL, ImportDestinationKind.MDBX)
                    assertEquals(if (supportsTrash) 2 else 1,
                        model.importZipBackup(Uri.fromFile(prepared), archivePassword, target).getOrThrow())
                    assertEquals(1, importedPasswords(target).size)
                    assertEquals(if (supportsTrash) 1 else 0,
                        db.passwordEntryDao().getDeletedEntriesSync().count { target.contains(it) && it.title.startsWith(prefix) })
                    assertEquals(0, model.importZipBackup(Uri.fromFile(prepared), archivePassword, target).getOrThrow())
                }
            } finally { prepared.delete() }
        }
    }

    @Test fun malformedOrCancelledArchiveParsingRemovesStagedPlaintext() = runBlocking {
        scenario {
            val target = mdbx()
            val invalid = File(root, "invalid.zip").apply { writeText("Synthetic invalid archive $prefix") }
            val encrypted = File(root, "invalid.enc.zip")
            EncryptionHelper.encryptFile(invalid, encrypted, archivePassword, AppLocaleStringResolver(context)).getOrThrow()
            val previous = context.cacheDir.listFiles().orEmpty().toSet()
            try {
                assertTrue(model.importZipBackup(Uri.fromFile(encrypted), archivePassword, target).isFailure)
                assertTrue(importedPasswords(target).isEmpty())
                assertTrue("Invalid encrypted input must not leave its decrypted file",
                    (context.cacheDir.listFiles().orEmpty().toSet() - previous).none { it.name.startsWith("restore_decrypted_") })

                val cancelled = File(root, "cancelled.zip")
                ZipOutputStream(cancelled.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("attachments_portable/cancel-test-payload.bin"))
                    zip.write(payload)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("database_export.json"))
                    zip.write("{\"version\":1}".toByteArray())
                    zip.closeEntry()
                }
                try {
                    WebDavHelper(context).restoreFromBackupFile(cancelled, restoreMonicaConfig = false,
                        importDataOnly = true, progress = TransferProgressReporter {
                            if (it.phase == TransferPhase.READING && it.completed == 1L) {
                                throw CancellationException("Synthetic cancellation after staging attachment")
                            }
                        }).getOrThrow()
                    fail("Expected cancellation")
                } catch (_: CancellationException) { }
                assertTrue("Cancelled input must not leave its staged attachment",
                    context.cacheDir.listFiles().orEmpty().none { it.name.endsWith("cancel-test-payload.bin") })
            } finally {
                // Remove only synthetic leftovers if this regression test fails against an older build.
                (context.cacheDir.listFiles().orEmpty().toSet() - previous).forEach { file ->
                    if (file.name.endsWith("cancel-test-payload.bin") ||
                        (file.name.startsWith("restore_decrypted_") && file.readText() == invalid.readText())) file.delete()
                }
            }
        }
    }
}
