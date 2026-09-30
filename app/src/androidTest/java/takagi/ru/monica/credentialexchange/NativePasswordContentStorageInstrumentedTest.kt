package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keemobile.kotpass.models.EntryValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.BackupContent
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Native files must retain every existing password field even when the login is empty. */
@RunWith(AndroidJUnit4::class)
class NativePasswordContentStorageInstrumentedTest {
    @Test fun explicitMultiPasswordGroupSurvivesBatchMoveToMdbx() = runBlocking {
        scenario { passwords ->
            val done = CompletableDeferred<Long?>()
            val expected = listOf("first-synthetic-secret", "second-synthetic-secret")
            passwords.savePasswordsAcrossTargets(emptyList(),
                PasswordEntry(title = "$prefix-multiple", username = "alice", password = "", website = "https://example.invalid"),
                expected, listOf(StorageTarget.MonicaLocal(null)), onComplete = { done.complete(it) })
            val first = requireNotNull(withTimeout(30_000) { done.await() })
            val before = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title == "$prefix-multiple" }
            assertEquals(2, before.size)
            assertEquals(first, before.minOf { it.id })
            assertEquals(1, before.map { takagi.ru.monica.ui.password.getPasswordInfoKey(it) }.distinct().size)
            val target = mdbx()
            passwords.movePasswordsToMdbxDatabaseAwait(before.map { it.id }, target.databaseId)
            Mdbx2NativeReadSessions.clear()
            val native = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
            assertEquals("Both passwords must be stored", 2, native.size)
            assertEquals(expected.toSet(), native.map { JSONObject(it.payloadJson).getString("password_plain") }.toSet())
            val moved = importedPasswords(target)
            assertEquals(2, moved.size)
            assertEquals("Both passwords must remain visible in the same project", 2,
                takagi.ru.monica.ui.screens.resolvePasswordDetailGroupPasswords(moved.first(), moved).size)
            val archive = model.prepareZipBackup(backupEncryptionPassword = "synthetic archive password", source = target,
                preferences = BackupPreferences(includeImages = false)).getOrThrow().first
            try {
                val restored = WebDavHelper(context).restoreFromBackupFile(archive, "synthetic archive password",
                    restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content.passwords
                assertEquals(2, restored.size)
                assertEquals(moved.first().passwordGroupId, restored.first().passwordGroupId)
                assertEquals(2, takagi.ru.monica.ui.screens.resolvePasswordDetailGroupPasswords(restored.first(), restored).size)
            } finally { archive.delete() }
        }
    }

    @Test fun matchingIndependentEntriesAreNotOneMultiPasswordProject() {
        val first = PasswordEntry(id = 701, title = "same", username = "alice", password = "one", website = "https://example.invalid")
        val second = first.copy(id = 702, password = "two")
        assertNotEquals(takagi.ru.monica.ui.password.getPasswordInfoKey(first),
            takagi.ru.monica.ui.password.getPasswordInfoKey(second))
    }
    private val cardNumber = "4242424242424242"
    private val note = "部署说明\n\n订阅：年度计划 🔐\n  保留缩进和末尾空白  \n"
    private val extra = listOf(
        CustomFieldDraft(title = "Recovery hint", value = "synthetic hint", isProtected = true),
        CustomFieldDraft(title = "monica.content.payment.pin", value = "8642", isProtected = true),
        CustomFieldDraft(title = "monica.content.contact.passportNumber", value = "EX1234567", isProtected = true),
        CustomFieldDraft(title = "monica.content.address.apartment", value = "Suite 9"),
        CustomFieldDraft(title = "monica.content.order", value = "PAYMENT,CONTACT,ADDRESS,FUTURE"),
        CustomFieldDraft(title = "monica.content.future.unknown", value = "future payload", isProtected = true)
    ).let { base ->
        takagi.ru.monica.data.model.PasswordContentBlocks.Kind.entries.fold(base) { fields, kind ->
            var block = takagi.ru.monica.data.model.PasswordContentBlocks.create(kind).edited("Synthetic $kind",
                takagi.ru.monica.data.model.PasswordContentBlocks.editableKeys(kind).associateWith { " synthetic-测试-$it\n".repeat(120) })
            if (kind == takagi.ru.monica.data.model.PasswordContentBlocks.Kind.QR_CODE) block = block.edited(block.title,
                mapOf("mode" to "template", "templateVersion" to "1", "content" to takagi.ru.monica.data.model.PasswordQrTemplate.WIFI))
            takagi.ru.monica.data.model.PasswordContentBlocks.put(fields, block)
        }
    }
    private fun assertExtras(actual: List<CustomFieldDraft>, orderMayExtend: Boolean = false) {
        extra.forEach { expected ->
            val found = actual.single { it.title == expected.title }
            if (orderMayExtend && expected.title == takagi.ru.monica.data.model.EntryContentFields.ORDER) {
                val original = takagi.ru.monica.data.model.EntryContentFields.order(extra)
                val updated = takagi.ru.monica.data.model.EntryContentFields.order(actual)
                assertEquals("Existing content order must remain a prefix", original, updated.take(original.size))
                assertEquals("Retry must not duplicate order tokens", updated.distinct(), updated)
            } else {
                assertEquals(expected.title, expected.value, found.value)
            }
            assertEquals(expected.title, expected.isProtected, found.isProtected)
        }
    }

    @Test fun templateSavedFromDetailsReachesNativeVaultAndUsesUpdatedFields() = runBlocking {
        scenario { passwords ->
            for (target in listOf(keepass(), mdbx())) {
                val storage = if (target.kind == ImportDestinationKind.KEEPASS) StorageTarget.KeePass(target.databaseId, null)
                    else StorageTarget.Mdbx(target.databaseId, null)
                val id = save(passwords, entry("$prefix-template").copy(username = "wifi;network", password = "first-secret"), storage)
                val template = takagi.ru.monica.data.model.PasswordContentBlocks.create(takagi.ru.monica.data.model.PasswordContentBlocks.Kind.QR_CODE)
                    .edited("Dynamic Wi-Fi", mapOf("mode" to "template", "templateVersion" to "1", "content" to takagi.ru.monica.data.model.PasswordQrTemplate.WIFI))
                passwords.appendPasswordQrTemplate(id, template)
                // Retrying a completed save must not create another block or change storage ownership.
                passwords.appendPasswordQrTemplate(id, template)
                val owner = requireNotNull(passwords.getRawPasswordEntryById(id))
                assertEquals(if (target.kind == ImportDestinationKind.KEEPASS) target.databaseId else null, owner.keepassDatabaseId)
                assertEquals(if (target.kind == ImportDestinationKind.MDBX) target.databaseId else null, owner.mdbxDatabaseId)
                val current = passwords.getCustomFieldsByEntryIdSync(id).map { field -> CustomFieldDraft(
                    id = field.id, title = field.title, value = security.decryptDataIfMonicaCiphertext(field.value), isProtected = field.isProtected) }
                assertExtras(current, orderMayExtend = true)
                assertEquals(takagi.ru.monica.data.model.PasswordContentBlocks.token(template.id),
                    takagi.ru.monica.data.model.EntryContentFields.order(current).last())
                assertEquals(1, takagi.ru.monica.data.model.PasswordContentBlocks.read(current).count { it.block?.id == template.id })
                val updated = requireNotNull(passwords.getPasswordEntryById(id)).copy(password = "second;secret")
                val done = CompletableDeferred<Long?>()
                passwords.savePasswordsAcrossTargets(listOf(id), updated, listOf(updated.password), listOf(storage), current, onComplete = { done.complete(it) })
                requireNotNull(withTimeout(30_000) { done.await() })
                val rendered = takagi.ru.monica.data.model.PasswordQrTemplate.resolve(template, passwords.readQrTemplateValues(id))
                assertEquals("WIFI:T:WPA;S:wifi\\;network;P:second\\;secret;H:false;;", rendered)
                val reopened = if (target.kind == ImportDestinationKind.KEEPASS) {
                    KeePassKdbxService.invalidateProcessCache(target.databaseId)
                    KeePassKdbxService(context, db.localKeePassDatabaseDao(), security).loadWorkspace(target.databaseId).getOrThrow()
                        .passwords.single().customFields.map { CustomFieldDraft(title = it.title, value = it.value, isProtected = it.isProtected) }
                } else {
                    Mdbx2NativeReadSessions.clear()
                    val fields = JSONObject(mdbx.readStoredEntries(target.databaseId).single { !it.deleted }.payloadJson).getJSONArray("custom_fields")
                    (0 until fields.length()).map { fields.getJSONObject(it) }.map { CustomFieldDraft(title = it.getString("title"), value = it.getString("value"), isProtected = it.getBoolean("is_protected")) }
                }
                val restored = takagi.ru.monica.data.model.PasswordContentBlocks.read(reopened).single { it.block?.id == template.id }.block!!
                assertEquals(takagi.ru.monica.data.model.PasswordQrTemplate.WIFI, restored.value("content"))
                assertFalse(restored.raw.toString().contains("second;secret"))
            }
        }
    }
    private fun assertNativeExtras(payload: JSONObject) {
        val fields = payload.getJSONArray("custom_fields")
        assertEquals(extra.size, fields.length())
        extra.forEach { expected ->
            val found = (0 until fields.length()).map { fields.getJSONObject(it) }
                .single { it.getString("title") == expected.title }
            assertEquals(expected.value, found.getString("value"))
            assertEquals(expected.isProtected, found.getBoolean("is_protected"))
        }
    }

    private suspend fun scenario(block: suspend TransferFixture.(PasswordViewModel) -> Unit) {
        val fixture = TransferFixture()
        val passwords = PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
            localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))
        try { fixture.block(passwords) } finally { passwords.viewModelScope.cancel(); fixture.close() }
    }

    private fun entry(title: String) = PasswordEntry(title = title, username = "", password = "", website = "",
        notes = note, email = "alice@example.invalid", phone = "+1 202 555 0140", addressLine = "12 Example Street",
        city = "Example City", state = "EX", zipCode = "10000", country = "US",
        creditCardNumber = cardNumber, creditCardHolder = "ALICE EXAMPLE", creditCardExpiry = "09/30", creditCardCVV = "123")

    private suspend fun save(passwords: PasswordViewModel, entry: PasswordEntry, target: StorageTarget): Long {
        val done = CompletableDeferred<Long?>()
        passwords.savePasswordsAcrossTargets(listOf(entry.id).filter { it > 0 }, entry, listOf(entry.password),
            listOf(target), extra, onComplete = { done.complete(it) })
        return requireNotNull(withTimeout(30_000) { done.await() }) { "Content save did not complete" }
    }

    @Test fun keepassCanSaveContentWithoutLoginAndReopenAllNativeFields() = runBlocking {
        scenario { passwords ->
            val target = keepass()
            val storage = StorageTarget.KeePass(target.databaseId, null)
            val id = save(passwords, entry("$prefix-content"), storage)
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            val first = service.loadWorkspace(target.databaseId).getOrThrow().passwords.single()
            assertEquals("", first.password)
            assertEquals("", first.username)
            assertEquals(note, first.notes)
            assertEquals(cardNumber, first.creditCardNumber)
            assertEquals("123", first.creditCardCVV)
            assertEquals("ALICE EXAMPLE", first.creditCardHolder)
            assertEquals("09/30", first.creditCardExpiry)
            assertEquals("alice@example.invalid", first.email)
            assertEquals("12 Example Street", first.addressLine)
            assertTrue(first.customFields.any { it.title == "Recovery hint" && it.isProtected })
            val native = keepassEntries(target.databaseId).single()
            assertTrue(native.fields.getValue("Card Number") is EntryValue.Encrypted)
            assertTrue(native.fields.getValue("Card CVV") is EntryValue.Encrypted)
            assertFalse(native.fields.containsKey("MonicaItemType"))
            assertFalse(keepassFile(target.databaseId).readText(Charsets.ISO_8859_1).contains(cardNumber))

            val original = requireNotNull(passwords.getPasswordEntryById(id))
            save(passwords, original.copy(title = "$prefix-renamed", notes = note + "更新\n"), storage)
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            val reopened = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                .loadWorkspace(target.databaseId).getOrThrow().passwords.single()
            assertExtras(reopened.customFields.map { CustomFieldDraft(title = it.title, value = it.value, isProtected = it.isProtected) })
            assertEquals(first.entryUuid, reopened.entryUuid)
            assertEquals(note + "更新\n", reopened.notes)
            assertEquals(cardNumber, reopened.creditCardNumber)
            assertEquals("synthetic hint", reopened.customFields.single { it.title == "Recovery hint" }.value)
        }
    }

    @Test fun mdbxCanSaveContentWithoutLoginAndReopenNativeRecordAfterEditing() = runBlocking {
        scenario { passwords ->
            val target = mdbx()
            val storage = StorageTarget.Mdbx(target.databaseId, null)
            val id = save(passwords, entry("$prefix-content"), storage)
            suspend fun reopen(): JSONObject {
                Mdbx2NativeReadSessions.clear()
                val rows = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
                    .readStoredEntries(target.databaseId).filterNot { it.deleted }
                assertEquals(1, rows.size)
                assertEquals("login", rows.single().entryType)
                return JSONObject(rows.single().payloadJson)
            }
            val first = reopen()
            assertEquals("", first.getString("password_plain"))
            assertEquals(note, first.getString("notes"))
            assertEquals(cardNumber, first.getString("credit_card_number_plain"))
            assertEquals("123", first.getString("credit_card_cvv_plain"))
            assertEquals("alice@example.invalid", first.getString("email"))
            assertEquals("12 Example Street", first.getString("address_line"))
            assertEquals("synthetic hint", first.getJSONArray("custom_fields").getJSONObject(0).getString("value"))
            val original = requireNotNull(passwords.getPasswordEntryById(id))
            save(passwords, original.copy(notes = note + "更新\n"), storage)
            val updated = reopen()
            assertEquals(note + "更新\n", updated.getString("notes"))
            assertEquals(cardNumber, updated.getString("credit_card_number_plain"))
            assertEquals("123", updated.getString("credit_card_cvv_plain"))
            assertNativeExtras(first)
            assertNativeExtras(updated)

            val edited = requireNotNull(passwords.getPasswordEntryById(id))
            save(passwords, edited.copy(creditCardNumber = "", creditCardCVV = "", addressLine = ""), storage)
            val cleared = reopen()
            assertEquals("", cleared.getString("credit_card_number_plain"))
            assertEquals("", cleared.getString("credit_card_cvv_plain"))
            assertEquals("", cleared.getString("address_line"))
            assertEquals("alice@example.invalid", cleared.getString("email"))
        }
    }

    @Test fun nativeExportAndRepeatedImportPreserveContentWithoutRoomAndNeverCollapseDistinctCards() = runBlocking {
        scenario { passwords ->
            for (source in listOf(keepass(), mdbx())) {
                val storage = if (source.kind == ImportDestinationKind.KEEPASS)
                    StorageTarget.KeePass(source.databaseId, null) else StorageTarget.Mdbx(source.databaseId, null)
                val id = save(passwords, entry("$prefix-content-${source.kind}"), storage)
                // Removing only this synthetic cache row forces the exporter/duplicate lookup
                // to read the native file, as on another device.
                db.passwordEntryDao().deletePasswordEntryById(id)
                val archive = model.prepareZipBackup(backupEncryptionPassword = "synthetic archive password", source = source,
                    preferences = BackupPreferences(includeImages = false)).getOrThrow().first
                try {
                    val content = WebDavHelper(context).restoreFromBackupFile(archive, "synthetic archive password",
                        restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
                    val restored = content.passwords.single()
                    assertExtras(content.customFieldsMap[restored.id].orEmpty().map { CustomFieldDraft(title = it.title, value = it.value, isProtected = it.isProtected) })
                    assertEquals(note, restored.notes)
                    assertEquals(cardNumber, restored.creditCardNumber)
                    assertEquals("123", restored.creditCardCVV)
                    assertEquals("alice@example.invalid", restored.email)
                    assertEquals("12 Example Street", restored.addressLine)
                    assertEquals(0, importer.apply(content, source).imported)
                    val indexed = importedPasswords(source).single()
                    assertEquals(cardNumber, indexed.creditCardNumber)
                    assertEquals("12 Example Street", indexed.addressLine)
                    assertEquals(0, importer.apply(content, source).imported)

                    val variants = listOf(restored.copy(id = 90001, creditCardNumber = "5555555555554444"),
                        restored.copy(id = 90002, notes = note.trim()))
                    val fields = content.customFieldsMap[restored.id].orEmpty()
                    val differentContent = BackupContent(variants, emptyList(),
                        customFieldsMap = variants.associate { it.id to fields })
                    assertEquals("Different card/notes content must remain distinct", 2, importer.apply(differentContent, source).imported)
                    assertEquals(0, importer.apply(differentContent, source).imported)
                    assertEquals(3, importedPasswords(source).size)
                } finally { archive.delete() }
            }
        }
    }

    @Test fun mdbxSyncRebuildsADeletedProjectionWithReadableContactAndPaymentContent() = runBlocking {
        scenario { passwords ->
            val target = mdbx()
            val id = save(passwords, entry("$prefix-sync"), StorageTarget.Mdbx(target.databaseId, null))
            db.passwordEntryDao().deletePasswordEntryById(id)
            val manager = MdbxViewModel(application = context.applicationContext as android.app.Application,
                databaseDao = db.localMdbxDatabaseDao(), remoteSourceDao = db.mdbxRemoteSourceDao(),
                passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(),
                passkeyDao = db.passkeyDao(), attachmentDao = db.attachmentDao(), customFieldDao = db.customFieldDao(),
                securityManager = security)
            try {
                manager.syncVault(target.databaseId)
                val result = withTimeout(30_000) { manager.operationState.first {
                    it is MdbxViewModel.OperationState.Success || it is MdbxViewModel.OperationState.Error
                } }
                assertTrue(result.toString(), result is MdbxViewModel.OperationState.Success)
                val reopened = importedPasswords(target).single()
                assertEquals("", security.decryptData(reopened.password))
                assertEquals(note, reopened.notes)
                assertEquals(cardNumber, reopened.creditCardNumber)
                assertEquals("123", reopened.creditCardCVV)
                assertEquals("12 Example Street", reopened.addressLine)
                assertEquals("alice@example.invalid", reopened.email)
                assertEquals("synthetic hint", db.customFieldDao().getFieldsByEntryIdSync(reopened.id).single { it.title == "Recovery hint" }.value)
                assertExtras(db.customFieldDao().getFieldsByEntryIdSync(reopened.id).map { field ->
                    CustomFieldDraft(title = field.title, value = security.decryptDataIfMonicaCiphertext(field.value), isProtected = field.isProtected)
                })
                // The ordinary editor obtains raw rows; only the password is separately decoded.
                val editorEntry = requireNotNull(passwords.getRawPasswordEntryById(reopened.id))
                assertEquals(cardNumber, editorEntry.creditCardNumber)
                assertEquals("123", editorEntry.creditCardCVV)
                save(passwords, editorEntry.copy(password = "", title = "$prefix-edited-after-sync"),
                    StorageTarget.Mdbx(target.databaseId, null))
                Mdbx2NativeReadSessions.clear()
                val native = mdbx.readStoredEntries(target.databaseId).single { !it.deleted }
                assertEquals("$prefix-edited-after-sync", native.title)
                val payload = JSONObject(native.payloadJson)
                assertNativeExtras(payload)
                assertEquals(cardNumber, payload.getString("credit_card_number_plain"))
                assertEquals("123", payload.getString("credit_card_cvv_plain"))
            } finally { manager.viewModelScope.cancel() }
        }
    }
}
