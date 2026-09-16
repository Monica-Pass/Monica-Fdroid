package takagi.ru.monica.credentialexchange

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.keepass.KeePassDxPasskeyCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.util.DataExportImportManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.KeePassKdbxService

@RunWith(AndroidJUnit4::class)
class CredentialImportInstrumentedTest {
    private suspend fun scenario(block: suspend TransferFixture.() -> Unit) {
        val fixture = TransferFixture()
        try { fixture.block() } finally { fixture.close() }
    }

    @Test fun cxfPersistsToAllFourDestinationsAndRepeatedImportsDoNotOverwriteKeys() = runBlocking {
        scenario {
            val source = decoded()
            val destinations = listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())
            for (target in destinations) {
                val result = importer.importExchange(source, target)
                assertEquals("${target.kind}: $result", 2, result.imported)
                assertEquals(0, result.failed)
                assertEquals(target.bitwardenId != null, result.queuedToBitwarden)
                val password = importedPasswords(target).single()
                assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(password.password))
                assertEquals(rawUsername, password.username)
                assertEquals(website, password.website)
                val key = importedKeys(target).single()
                assertOriginalKey(key)
                assertEquals(password.id, key.boundPasswordId)
                assertNotEquals(source.items.single().passkeys.single().key, key.privateKeyAlias)
                target.keepassId?.let { id ->
                    val rows = keepassEntries(id)
                    val nativePassword = rows.single { it.fields["Title"]?.content == source.items.single().title }
                    assertEquals(rawPassword, nativePassword.fields.getValue("Password").content)
                    val nativeKey = rows.single { it.fields[KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID]?.content == credentialId }
                    assertEquals(rpId, nativeKey.fields.getValue(KeePassDxPasskeyCodec.FIELD_RELYING_PARTY).content)
                    assertEquals(userHandle, nativeKey.fields.getValue(KeePassDxPasskeyCodec.FIELD_USER_HANDLE).content)
                    assertKeySigns(nativeKey.fields.getValue(KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY).content)
                }
                target.mdbxId?.let { id ->
                    val rows = mdbx.readStoredEntries(id).filterNot { it.deleted }
                    assertEquals(rawPassword, JSONObject(rows.single { it.entryType == "login" }.payloadJson).getString("password_plain"))
                    val nativeKey = JSONObject(rows.single { it.entryType == "passkey" }.payloadJson)
                    assertEquals(credentialId, nativeKey.getString("credential_id"))
                    assertKeySigns(nativeKey.getString("private_key_alias"))
                }
                val repeat = importer.importExchange(source, target)
                assertEquals("${target.kind}: $repeat", 0, repeat.imported)
                assertEquals(2, repeat.skipped)
                assertEquals(0, repeat.failed)
                assertEquals(key, importedKeys(target).single())
            }
        }
    }

    @Test fun existingNonzeroCounterAndPrivateKeyStayUntouchedAndAreExcludedOnExport() = runBlocking {
        scenario {
            val target = ImportDestination.Local
            importer.importExchange(decoded(), target)
            val existing = importedKeys(target).single().copy(signCount = 5L)
            db.passkeyDao().update(existing)
            val repeat = importer.importExchange(decoded(), target)
            assertEquals(2, repeat.skipped)
            assertEquals(existing, importedKeys(target).single())
            val exported = CredentialExchangeExporter(context).prepare(target, setOf("passkey"))
            val exportedKeys = CxfCredentialCodec.decode(exported.json).items.flatMap { it.passkeys }
            assertFalse(exportedKeys.any { it.credentialId == credentialId })
            assertTrue(exported.skippedPasskeys >= 1)
            assertEquals(5L, importedKeys(target).single().signCount)
        }
    }

    @Test fun bitwardenUploadsPasswordAndStandardFido2FieldsAfterRetryingServerFailure() = runBlocking {
        scenario {
            val target = bitwarden()
            val result = importer.importExchange(decoded(), target)
            assertTrue(result.queuedToBitwarden)
            val vault = checkNotNull(db.bitwardenVaultDao().getVaultById(target.databaseId))
            val sync = BitwardenSyncService(context)
            remote.rejectWrites = true
            sync.uploadLocalEntries(vault, accessToken, vaultKey)
            assertTrue(remote.created.isEmpty())
            assertNull(importedPasswords(target).single().bitwardenCipherId)
            assertEquals("FAILED", importedKeys(target).single().syncStatus)
            assertTrue(db.passkeyDao().getLocalEntriesPendingUpload(target.databaseId)
                .any { it.id == importedKeys(target).single().id })
            remote.rejectWrites = false
            sync.uploadLocalEntries(vault, accessToken, vaultKey)
            assertEquals(2, remote.created.size)
            fun plain(cipher: JSONObject, value: String): String {
                val encryptedKey = cipher.optString("key").takeIf { it.isNotBlank() && it != "null" }
                if (encryptedKey == null) return BitwardenCrypto.decryptToString(value, vaultKey)
                val bytes = BitwardenCrypto.decrypt(encryptedKey, vaultKey)
                val itemKey = BitwardenCrypto.SymmetricCryptoKey(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
                return try { BitwardenCrypto.decryptToString(value, itemKey) } finally { itemKey.clear(); bytes.fill(0) }
            }
            val passwordCipher = remote.created.single { (it.getJSONObject("login").optJSONArray("fido2Credentials")?.length() ?: 0) == 0 }
            assertEquals(rawPassword, plain(passwordCipher, passwordCipher.getJSONObject("login").getString("password")))
            val passkeyCipher = remote.created.single { (it.getJSONObject("login").optJSONArray("fido2Credentials")?.length() ?: 0) > 0 }
            val key = passkeyCipher.getJSONObject("login").getJSONArray("fido2Credentials").getJSONObject(0)
            assertEquals("0", plain(passkeyCipher, key.getString("counter")))
            assertEquals(rpId, plain(passkeyCipher, key.getString("rpId")))
            assertEquals(userHandle, plain(passkeyCipher, key.getString("userHandle")))
            assertKeySigns(plain(passkeyCipher, key.getString("keyValue")))
            assertNotNull(importedPasswords(target).single().bitwardenCipherId)
            assertEquals("SYNCED", importedKeys(target).single().syncStatus)
            sync.uploadLocalEntries(vault, accessToken, vaultKey)
            assertEquals("Retry after acknowledgement must not upload duplicates", 2, remote.created.size)
        }
    }

    @Test fun csvUsesEverySelectedDestinationAndPreservesCredentialCharacters() = runBlocking {
        scenario {
            fun record(values: List<String>) = values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
            val formats = listOf(
                Triple(DataExportImportManager.CsvFormat.CHROME_PASSWORD, "name,url,username,password,note", listOf("TITLE", website, rawUsername, rawPassword, "Notes\r\nnext")),
                Triple(DataExportImportManager.CsvFormat.KEEPASS_PASSWORD, "Title,User Name,Password,URL,Notes", listOf("TITLE", rawUsername, rawPassword, website, "Notes")),
                Triple(DataExportImportManager.CsvFormat.BITWARDEN_PASSWORD, "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp", listOf("", "0", "login", "TITLE", "Notes", "", "0", website, rawUsername, rawPassword, "")),
                Triple(DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD, "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault", listOf("login", "TITLE", website, "e@example.invalid", rawUsername, rawPassword, "Notes", "", "1700000000", "1700000001", "Fixture")),
                Triple(DataExportImportManager.CsvFormat.PASSWORD_KEYBOARD, "username,password,title,remarks,url,tag,custom", listOf(rawUsername, rawPassword, "TITLE", "Notes", website, "", "")),
                Triple(DataExportImportManager.CsvFormat.APP_EXPORT, "ID,Type,Title,Data,Notes,IsFavorite,ImagePaths,CreatedAt,UpdatedAt",
                    listOf("700101", "PASSWORD", "TITLE", takagi.ru.monica.util.CsvPasswordData.encode(rawUsername,
                        security.encryptData(rawPassword), website), "Notes", "true", "", "1700000000000", "1700000000001")),
            )
            val targets = listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())
            for (target in targets) for ((format, header, values) in formats) {
                val title = "$prefix-${format.name}"
                val file = File(root, "${format.name}.csv").apply { writeText("\uFEFF$header\r\n${record(values.map { if (it == "TITLE") title else it })}\r\n") }
                assertEquals("${target.kind} $format", 1, model.importData(Uri.fromFile(file), format, destination = target).getOrThrow())
                val stored = importedPasswords(target).single { it.title == title }
                assertEquals(rawUsername, stored.username)
                assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(stored.password))
                assertEquals(0, model.importData(Uri.fromFile(file), format, destination = target).getOrThrow())
                assertEquals(1, model.lastImportSummary.value!!.skipped)
                if (format == DataExportImportManager.CsvFormat.APP_EXPORT) {
                    assertEquals("Automatic detection of Monica CSV", 0,
                        model.importData(Uri.fromFile(file), destination = target).getOrThrow())
                }
            }
        }
    }

    @Test fun malformedCsvRowsAreReportedAndUnterminatedPasswordWritesNothing() = runBlocking {
        scenario {
            val file = File(root, "partial.csv").apply {
                writeText("name,url,username,password\n$prefix-valid,https://example.invalid,alice,secret\ninvalid\n")
            }
            assertEquals(1, model.importChromeCsv(Uri.fromFile(file), ImportDestination.Local).getOrThrow())
            assertEquals(1, model.lastImportSummary.value!!.failed)
            file.writeText("name,url,username,password\n$prefix-new,https://example.invalid,alice,\"unclosed\n")
            assertTrue(model.importChromeCsv(Uri.fromFile(file), ImportDestination.Local).isFailure)
            assertEquals(1, importedPasswords(ImportDestination.Local).size)
        }
    }

    @Test fun plaintextCipherPrefixesSurviveCxfCsvAndNativeExport() = runBlocking {
        scenario {
            val samples = listOf("MDK|a real password", "V2|not ciphertext", "C2|literal text",
                security.encryptData("The ciphertext itself is the imported password"))
            for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                val source = decoded().copy(items = samples.mapIndexed { index, secret ->
                    decoded().items.single().copy(title = "$prefix-prefix-$index", passkeys = emptyList(),
                        logins = listOf(CxfCredentialCodec.Login("alice", secret)))
                })
                assertEquals(target.kind.name, samples.size, importer.importExchange(source, target).imported)
                val restored = importedPasswords(target).sortedBy { it.title }.map { security.decryptDataIfMonicaCiphertext(it.password) }
                assertEquals(samples, restored)
                val exported = CredentialExchangeExporter(context).prepare(target, setOf("basic-auth"))
                assertEquals(samples, CxfCredentialCodec.decode(exported.json).items.filter { it.title.startsWith(prefix) }
                    .sortedBy { it.title }.flatMap { it.logins }.map { it.password })
                val file = File(root, "prefix.csv").apply {
                    writeText("name,url,username,password\n$prefix-csv-prefix,https://example.invalid,alice,C2|literal text\n")
                }
                assertEquals(1, model.importChromeCsv(Uri.fromFile(file), target).getOrThrow())
                assertEquals("C2|literal text", security.decryptDataIfMonicaCiphertext(importedPasswords(target)
                    .single { it.title == "$prefix-csv-prefix" }.password))
            }
        }
    }

    @Test fun existingNativeCredentialsAreNotDuplicatedWhenRoomIndexHasNotLoaded() = runBlocking {
        scenario {
            for (target in listOf(keepass(), mdbx())) {
                val source = decoded()
                assertEquals(2, importer.importExchange(source, target).imported)
                val password = importedPasswords(target).single()
                val key = importedKeys(target).single()
                val beforeMdbx = target.mdbxId?.let { mdbx.readStoredEntries(it).filterNot { row -> row.deleted } }
                val beforeKeePass = target.keepassId?.let { keepassEntries(it).map { row -> row.uuid } }
                // Simulate the real file being present before the app's local projection is populated.
                db.passkeyDao().delete(key)
                db.passwordEntryDao().deletePasswordEntryById(password.id)
                try {
                    val repeat = importer.importExchange(source, target)
                    assertEquals("${target.kind}: $repeat", 0, repeat.imported)
                    assertEquals(2, repeat.skipped)
                    assertEquals(0, repeat.failed)
                    target.mdbxId?.let { assertEquals(beforeMdbx, mdbx.readStoredEntries(it).filterNot { row -> row.deleted }) }
                    target.keepassId?.let { assertEquals(beforeKeePass, keepassEntries(it).map { row -> row.uuid }) }
                } finally {
                    // Keep the protected synthetic key reachable so normal fixture cleanup removes it.
                    if (importedKeys(target).isEmpty()) db.passkeyDao().insert(key.copy(id = 0))
                }
            }
        }
    }

    @Test fun repeatedImportFindsTheMatchingCustomFieldsAmongSimilarPasswords() = runBlocking {
        scenario {
            val source = TargetedImportCoordinator.exchangeContent(decoded()).copy(passkeys = emptyList())
            val id = source.passwords.single().id
            for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                val first = source.copy(customFieldsMap = mapOf(id to listOf(takagi.ru.monica.utils.CustomFieldBackupEntry("Secret", "first", true))))
                val second = source.copy(customFieldsMap = mapOf(id to listOf(takagi.ru.monica.utils.CustomFieldBackupEntry("Secret", "second", true))))
                val repeatedInFile = first.copy(passwords = first.passwords + first.passwords.single().copy(id = id + 1),
                    customFieldsMap = first.customFieldsMap + ((id + 1) to first.customFieldsMap.getValue(id)))
                val initial = importer.apply(repeatedInFile, target)
                assertEquals("${target.kind}: duplicates inside one file", 1, initial.imported)
                assertEquals(1, initial.skipped)
                assertEquals(1, db.customFieldDao().getFieldsByEntryIds(importedPasswords(target).map { it.id }).size)
                assertEquals(1, importer.apply(second, target).imported)
                assertEquals("${target.kind}: the older matching variant must also be found", 0,
                    importer.apply(first, target).imported)
                assertEquals("${target.kind}: an earlier candidate with different fields must not hide the actual duplicate", 0,
                    importer.apply(second, target).imported)
                assertEquals(2, importedPasswords(target).size)
            }
        }
    }

    @Test fun encryptedZipChecksPasswordThenImportsDataOnlyToEveryDestination() = runBlocking {
        scenario {
            val beforeCategories = db.categoryDao().getAllCategories().first()
            val beforeVaultIds = db.bitwardenVaultDao().getAllVaults().map { it.id }.toSet()
            val plain = File(root, "fixture.zip")
            val password = JSONObject().put("id", 987654).put("title", "$prefix-zip").put("username", rawUsername)
                .put("password", rawPassword).put("website", website).put("notes", "ZIP\nfixture")
                .put("categoryId", 998877).put("categoryName", "$prefix-old-category")
                .put("keepassDatabaseId", 998877).put("bitwardenVaultId", 998877)
                .put("customFields", org.json.JSONArray().put(JSONObject().put("title", "Test secret").put("value", "  Value;with:space  ").put("isProtected", true)))
            ZipOutputStream(plain.outputStream()).use {
                it.putNextEntry(ZipEntry("passwords/fixture.json")); it.write(password.toString().toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("monica_config/bitwarden_vaults.json")); it.write("not configuration to restore".toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("timeline/fixture.json")); it.write("not timeline to restore".toByteArray()); it.closeEntry()
            }
            val encrypted = File(root, "fixture.enc.zip")
            EncryptionHelper.encryptFile(plain, encrypted, "test backup passphrase", AppLocaleStringResolver(context)).getOrThrow()
            assertTrue(model.importZipBackup(Uri.fromFile(encrypted), destination = ImportDestination.Local).isFailure)
            assertTrue(model.importZipBackup(Uri.fromFile(encrypted), "wrong", ImportDestination.Local).isFailure)
            assertTrue(importedPasswords(ImportDestination.Local).isEmpty())
            for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                assertEquals("${target.kind}", 1, model.importZipBackup(Uri.fromFile(encrypted), "test backup passphrase", target).getOrThrow())
                val imported = importedPasswords(target).single()
                assertEquals(rawPassword, security.decryptDataIfMonicaCiphertext(imported.password))
                assertNull(imported.categoryId)
                assertEquals("  Value;with:space  ", db.customFieldDao().getFieldsByEntryIds(listOf(imported.id)).single().value)
                assertEquals(0, model.importZipBackup(Uri.fromFile(encrypted), "test backup passphrase", target).getOrThrow())
            }
            assertEquals(beforeCategories, db.categoryDao().getAllCategories().first())
            assertEquals(beforeVaultIds + vaultIds, db.bitwardenVaultDao().getAllVaults().map { it.id }.toSet())
        }
    }

    @Test fun missingAndLockedTargetsFailBeforeWritingAndCancellationDoesNotFallBackToLocal() = runBlocking {
        scenario {
            for (target in listOf(ImportDestination(ImportDestinationKind.KEEPASS, Long.MAX_VALUE),
                ImportDestination(ImportDestinationKind.MDBX, Long.MAX_VALUE), bitwarden(unlocked = false))) {
                assertTrue(runCatching { importer.importExchange(decoded(), target) }.isFailure)
                assertTrue(importedPasswords(ImportDestination.Local).isEmpty())
                assertTrue(importedKeys(ImportDestination.Local).isEmpty())
            }
            val readOnlyTarget = keepass()
            val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            service.setDatabaseReadOnly(readOnlyTarget.databaseId, true)
            try {
                assertTrue(runCatching { importer.importExchange(decoded(), readOnlyTarget) }.isFailure)
                assertTrue(importedPasswords(readOnlyTarget).isEmpty())
                assertTrue(keepassEntries(readOnlyTarget.databaseId).isEmpty())
                assertTrue(importedPasswords(ImportDestination.Local).isEmpty())
            } finally { service.setDatabaseReadOnly(readOnlyTarget.databaseId, false) }
            val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { importer.importExchange(decoded(), ImportDestination.Local) }
            job.cancel(); job.join()
            assertTrue(importedPasswords(ImportDestination.Local).isEmpty())
        }
    }

    @Test fun failedNativeKeePassWriteIsNotCountedAsACommittedImport() = runBlocking {
        scenario {
            val target = keepass()
            val writer = ImportDestinationWriter(context, target, passwords, secureItems)
            writer.validate()
            val source = TargetedImportCoordinator.exchangeContent(decoded()).passwords.single()
            writer.insertPassword(source.copy(password = security.encryptData(source.password)))
            // Fail the actual source-file write after validation, then check projection cleanup.
            val original = db.localKeePassDatabaseDao().getDatabaseById(target.databaseId)!!
            db.localKeePassDatabaseDao().updateDatabase(original.copy(filePath = "${root.name}/missing/fixture.kdbx"))
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            writer.finish()
            assertEquals(1, writer.uncommittedPasswordCount)
            assertTrue(importedPasswords(target).isEmpty())
            assertTrue(keepassEntries(target.databaseId).isEmpty())
            db.localKeePassDatabaseDao().updateDatabase(original)
        }
    }
}
