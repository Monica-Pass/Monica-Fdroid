package takagi.ru.monica.ime

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.ImeCustomFieldRow
import takagi.ru.monica.rustcore.RustPasswordListCore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator

class ImeVaultLoadInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun batchSortKeysPreserveEachUnicodeTitleAndItsBoundaries() {
        val examples = listOf("中文标题", "Café", "ΔΣ", "Москва", "東京の口座", "서울", "مرحبا", "नमस्ते",
            "银行A", "A银行", "123账户", "银行\n账户", "中文\u0000标题", "\u0301Café", "", "   ", "🔑 工作邮箱")
        val titles = List(160) { examples[it % examples.size] + if (it % 3 == 0) " $it" else "" }
        assertEquals(titles.map(::normalizedImeSortKey), normalizedImeSortKeys(titles))
    }

    @Test fun projectionKeepsFillFieldsWithoutReadingLargeEditorPayloadsOrKeyCredentials() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val entry = password("Wide record").copy(notes = "n".repeat(3 * 1024 * 1024),
                appName = "Fixture app", authenticatorKey = "fixture-otp", bitwardenVaultId = 42)
            val ids = room.passwordEntryDao().insertPasswordEntries(listOf(entry,
                entry.copy(loginType = "API_KEY"), entry.copy(loginType = "GPG_KEY"),
                entry.copy(isDeleted = true), entry.copy(isArchived = true)))
            val rows = room.passwordEntryDao().getImePasswordRows()
            assertEquals(listOf(ids.first()), rows.map { it.id })
            assertEquals(entry.username, rows.single().username)
            assertEquals(entry.password, rows.single().password)
            assertEquals(entry.authenticatorKey, rows.single().authenticatorKey)
            assertEquals(42L, rows.single().bitwardenVaultId)
        } finally { room.close() }
    }

    @Test fun nativeBatchAndFallbackAgreeOnUnicodeAndCrossFieldSearch() {
        val entries = List(600) { i -> MonicaImePasswordEntry(i.toLong(),
            if (i % 25 == 0) "Bitwarden ΔΣ 中文 $i" else "Mail $i", "Alice$i@example.com",
            "https://example.com", "com.example.app", "fixture", false, "Bitwarden · all rows",
            appName = "Fixture App") }
        val rows = entries.map { entry -> RustPasswordListCore.SearchRow(entry.title.lowercase(Locale.ROOT),
            entry.username.lowercase(Locale.ROOT), entry.website.lowercase(Locale.ROOT),
            entry.appName.lowercase(Locale.ROOT), entry.packageName.lowercase(Locale.ROOT)) }
        val native = RustPasswordListCore.prepareSearch(rows)
        assertNotNull("The x86_64 build must exercise the production Rust library",
            RustPasswordListCore.filterIndices(native, "bitwarden"))
        val index = ImePasswordIndex(entries)
        listOf("bitwarden", "ALICE25 bitwarden", "中文", "ΔΣ", "example fixture", "missing", " ").forEach { query ->
            assertEquals(query, entries.filter { imePasswordEntryMatchesQuery(it, query) }.map { it.id }.toSet(),
                index.query(query).map { it.id }.toSet())
        }
    }

    @Test fun passwordOtpUsesTheLatestKeyWithoutLoadingWideNotes() = runBlocking {
        val harness = Harness()
        try {
            val original = password("OTP fixture").copy(authenticatorKey = "JBSWY3DPEHPK3PXP", notes = "n".repeat(3 * 1024 * 1024))
            val id = harness.room.passwordEntryDao().insertPasswordEntry(original)
            harness.refresh()
            val cachedEntry = harness.state.value.entries.single()
            assertTrue(cachedEntry.hasTotp)
            assertEquals("", cachedEntry.totpCode)
            val newSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
            harness.room.passwordEntryDao().updatePasswordEntry(original.copy(id = id, authenticatorKey = newSecret))
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            val data = checkNotNull(TotpDataResolver.fromAuthenticatorKey(newSecret, "", ""))
            val before = TotpGenerator.generateOtp(data)
            withContext(Dispatchers.Main) { harness.fillOtp(cachedEntry) }
            waitUntil { editor.text.isNotEmpty() }
            val after = TotpGenerator.generateOtp(data)
            assertTrue(editor.text.toString() in setOf(before, after))
        } finally { harness.close() }
    }

    @Test fun otpReadFailureShowsAnErrorWithoutCommittingText() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("OTP fixture").copy(authenticatorKey = "JBSWY3DPEHPK3PXP"))
            harness.refresh()
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            gate.set { error("Synthetic database read failure") }
            withContext(Dispatchers.Main) { harness.fillOtp(harness.state.value.entries.single()) }
            waitUntil { harness.state.value.errorMessage != null }
            assertEquals("", editor.text.toString())
            assertTrue(harness.state.value.unlocked)
        } finally { harness.close() }
    }

    @Test fun otpReadCannotFillAnEditorThatChangedDuringTheRead() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("OTP fixture").copy(authenticatorKey = "JBSWY3DPEHPK3PXP"))
            harness.refresh()
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { harness.fillOtp(harness.state.value.entries.single()) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { harness.set("inputGeneration", harness.number("inputGeneration") + 1) }
            release.countDown()
            val scope = harness.field("serviceScope") as CoroutineScope
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals("", editor.text.toString())
        } finally { release.countDown(); harness.close() }
    }

    @Test fun serviceReusesTheSnapshotAndInvalidatesItAfterARealDatabaseWrite() = runBlocking {
        val harness = Harness()
        try {
            val ids = harness.room.passwordEntryDao().insertPasswordEntries(listOf(password("Bitwarden"), password("Mail")))
            withContext(Dispatchers.Main) { harness.call("observeDatabaseSources") }
            waitUntil { harness.number("vaultGeneration") > 0 }
            harness.refresh()
            val first = harness.field("vaultSourceCache")
            assertNotNull(first)
            withContext(Dispatchers.Main) { harness.state.value = harness.state.value.copy(query = "mail") }
            harness.refresh()
            assertSame(first, harness.field("vaultSourceCache"))
            assertEquals(listOf("Mail"), harness.state.value.entries.map { it.title })
            harness.room.passwordEntryDao().insertPasswordEntry(password("Mail two"))
            waitUntil { harness.field("vaultSourceCache") == null }
            harness.refresh()
            assertEquals(listOf("Mail", "Mail two"), harness.state.value.entries.map { it.title })
            val mail = harness.room.passwordEntryDao().getPasswordEntryById(ids[1])!!
            harness.room.passwordEntryDao().updatePasswordEntry(mail.copy(title = "Renamed"))
            waitUntil { harness.field("vaultSourceCache") == null }
            harness.refresh()
            assertEquals(listOf("Mail two"), harness.state.value.entries.map { it.title })
        } finally { harness.close() }
    }

    @Test fun simultaneousWindowAndEditorCallbacksShareTheSameLoad() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("Fixture"))
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            val request = MonicaInputMethodService::class.java.declaredMethods.single { it.name == "requestRefreshVaultEntries" }
                .apply { isAccessible = true }
            withContext(Dispatchers.Main) { request.invoke(harness.service, false, 0L) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            val job = harness.field("refreshJob")
            withContext(Dispatchers.Main) { request.invoke(harness.service, false, 0L) }
            assertSame("Repeated lifecycle callbacks must not cancel and start the same disk read again", job, harness.field("refreshJob"))
            release.countDown()
            waitUntil { harness.state.value.entries.isNotEmpty() }
        } finally { release.countDown(); harness.close() }
    }

    @Test fun oldReadCannotPublishAfterTheSearchQueryChanges() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntries(listOf(password("Bitwarden"), password("Mail")))
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            val old = async { harness.refresh() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { harness.state.value = harness.state.value.copy(query = "mail") }
            release.countDown()
            old.await()
            assertEquals("mail", harness.state.value.query)
            assertTrue(harness.state.value.entries.isEmpty())
            assertNull(harness.field("vaultSourceCache"))
            harness.refresh()
            assertEquals(listOf("Mail"), harness.state.value.entries.map { it.title })
        } finally { release.countDown(); harness.close() }
    }

    @Test fun lockingDuringAReadClearsTheKeyboardAndCannotResurrectItsCache() = runBlocking {
        val gate = AtomicReference<(() -> Unit)?>(null)
        val harness = Harness(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            harness.room.passwordEntryDao().insertPasswordEntry(password("Private fixture"))
            withContext(Dispatchers.Main) { harness.call("observeVaultLock") }
            gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            val old = async { harness.refresh() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            SessionManager.markLocked()
            waitUntil { !harness.state.value.unlocked }
            release.countDown()
            old.await()
            assertFalse(harness.state.value.unlocked)
            assertTrue(harness.state.value.entries.isEmpty())
            assertNull(harness.field("vaultSourceCache"))
        } finally { release.countDown(); harness.close() }
    }

    @Test fun customOnlyEntriesAndLargeFieldsLoadWithoutReadingSecretsIntoTheList() = runBlocking {
        val harness = Harness()
        try {
            val id = harness.room.passwordEntryDao().insertPasswordEntry(password("Custom only").copy(
                username = "", password = "", notes = "n".repeat(3 * 1024 * 1024)))
            val metadataId = harness.room.passwordEntryDao().insertPasswordEntry(password("Metadata only").copy(username = "", password = ""))
            val fields = harness.room.customFieldDao().insertAll(listOf(
                CustomField(entryId = id, title = "Encryption key", value = "k".repeat(3 * 1024 * 1024), isProtected = true, sortOrder = 2),
                CustomField(entryId = id, title = "Email", value = "fixture@example.invalid", sortOrder = 1),
                CustomField(entryId = id, title = "monica.content.block.test", value = "internal"),
                CustomField(entryId = id, title = "_etm_plugin", value = "internal"),
                CustomField(entryId = id, title = "MonicaPasskeyData", value = "internal"),
                CustomField(entryId = metadataId, title = "monica.content.order", value = "internal")
            ))
            harness.refresh()
            assertEquals(listOf(id), harness.state.value.entries.map { it.id })
            assertTrue(harness.state.value.entries.single().hasCustomFields)
            assertEquals(listOf(ImeCustomFieldRow(fields[1], "Email", false),
                ImeCustomFieldRow(fields[0], "Encryption key", true)), harness.fields(id).first())
        } finally { harness.close() }
    }

    @Test fun customFillPreservesBase64UnicodeAndLatestEncryptedValueWithoutWriting() = runBlocking {
        val harness = Harness()
        try {
            val id = harness.room.passwordEntryDao().insertPasswordEntry(password("Custom fixture"))
            val fieldId = harness.room.customFieldDao().insert(CustomField(entryId = id, title = "密保答案", value = "old"))
            harness.refresh()
            val entry = harness.state.value.entries.single()
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            val manager = harness.field("securityManager") as SecurityManager
            val cases = listOf("aGVsbG93b3JsZA==".repeat(8), "  密保答案 🔑\n第二行  ", "protected 最新 ΔΣ")
            cases.forEachIndexed { index, expected ->
                val stored = CustomField(id = fieldId, entryId = id, title = "密保答案",
                    value = if (index == 2) manager.encryptData(expected) else expected, isProtected = index == 2)
                harness.room.customFieldDao().update(stored)
                withContext(Dispatchers.Main) { editor.setText(""); harness.fillField(entry, fieldId) }
                (harness.field("customFieldFillJob") as Job).join()
                withContext(Dispatchers.Main) { assertEquals(expected, editor.text.toString()) }
                assertEquals(stored, harness.room.customFieldDao().getFieldById(fieldId))
            }
        } finally { harness.close() }
    }

    @Test fun customFillRejectsMissingWrongOwnerMovedDeletedAndUnreadableFields() = runBlocking {
        val harness = Harness()
        try {
            val original = password("Custom fixture")
            val id = harness.room.passwordEntryDao().insertPasswordEntry(original)
            val other = harness.room.passwordEntryDao().insertPasswordEntry(password("Other"))
            val fieldId = harness.room.customFieldDao().insert(CustomField(entryId = id, title = "Answer", value = "fixture"))
            val otherField = harness.room.customFieldDao().insert(CustomField(entryId = other, title = "Answer", value = "wrong-owner"))
            harness.refresh()
            val entry = harness.state.value.entries.single { it.id == id }
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            suspend fun reject(targetId: Long) {
                withContext(Dispatchers.Main) {
                    harness.state.value = harness.state.value.copy(errorMessage = null)
                    harness.fillField(entry, targetId)
                }
                (harness.field("customFieldFillJob") as Job).join()
                assertNotNull(harness.state.value.errorMessage)
                withContext(Dispatchers.Main) { assertEquals("", editor.text.toString()) }
            }
            reject(otherField)
            harness.room.customFieldDao().update(CustomField(id = fieldId, entryId = id, title = "Answer", value = "V2|broken"))
            reject(fieldId)
            harness.room.customFieldDao().update(CustomField(id = fieldId, entryId = id, title = "Answer", value = "fixture"))
            harness.room.passwordEntryDao().updatePasswordEntry(original.copy(id = id, mdbxDatabaseId = 99L))
            reject(fieldId)
            harness.room.passwordEntryDao().updatePasswordEntry(original.copy(id = id, isDeleted = true))
            reject(fieldId)
            harness.room.passwordEntryDao().updatePasswordEntry(original.copy(id = id))
            harness.room.customFieldDao().deleteById(fieldId)
            reject(fieldId)
        } finally { harness.close() }
    }

    @Test fun customFillCannotCrossInputChangesOrVaultLock() = runBlocking {
        for (lock in listOf(false, true)) {
            val gate = AtomicReference<(() -> Unit)?>(null)
            val harness = Harness(gate)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val id = harness.room.passwordEntryDao().insertPasswordEntry(password("Custom fixture"))
                val fieldId = harness.room.customFieldDao().insert(CustomField(entryId = id, title = "Answer", value = "private"))
                harness.refresh()
                val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
                gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                withContext(Dispatchers.Main) { harness.fillField(harness.state.value.entries.single(), fieldId) }
                withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
                val newEditor = withContext(Dispatchers.Main) {
                    if (lock) { SessionManager.markLocked(); editor }
                    else {
                        harness.set("inputGeneration", harness.number("inputGeneration") + 1)
                        harness.bindEditor()
                    }
                }
                release.countDown()
                (harness.field("customFieldFillJob") as Job).join()
                withContext(Dispatchers.Main) {
                    assertEquals("", editor.text.toString()); assertEquals("", newEditor.text.toString())
                }
            } finally { release.countDown(); harness.close() }
        }
    }

    @Test fun addingCustomFieldsInvalidatesAnEmptyCredentialSnapshot() = runBlocking {
        val harness = Harness()
        try {
            val id = harness.room.passwordEntryDao().insertPasswordEntry(password("Custom only").copy(username = "", password = ""))
            withContext(Dispatchers.Main) { harness.call("observeDatabaseSources") }
            waitUntil { harness.number("vaultGeneration") > 0 }
            harness.refresh()
            assertTrue(harness.state.value.entries.isEmpty())
            harness.room.customFieldDao().insert(CustomField(entryId = id, title = "Group number", value = "123"))
            waitUntil { harness.field("vaultSourceCache") == null }
            harness.refresh()
            assertEquals(listOf(id), harness.state.value.entries.map { it.id })
        } finally { harness.close() }
    }

    private fun password(title: String) = PasswordEntry(title = title, username = "fixture-user", password = "fixture", website = "")
    @Test fun passwordPanelDoesNotReadLargeWalletPayloads() = runBlocking {
        val harness = Harness()
        try {
            val id = harness.room.passwordEntryDao().insertPasswordEntry(password("Ordinary login"))
            harness.room.customFieldDao().insert(CustomField(entryId = id, title = "monica.content.wallet.bank_card", value = "x".repeat(3 * 1024 * 1024)))
            harness.refresh()
            assertEquals(listOf(id), harness.state.value.entries.map { it.id })
            assertNull(harness.field("cardWalletSourceCache"))
            assertTrue(harness.state.value.cardWalletEntries.isEmpty())
        } finally { harness.close() }
    }

    @Test fun embeddedWalletAndStandaloneAddressesFillLatestValuesWithoutExposingSecretsInState() = runBlocking {
        val harness = Harness()
        try {
            val manager = harness.field("securityManager") as SecurityManager
            val owner = harness.room.passwordEntryDao().insertPasswordEntry(password("Shopping"))
            fun address(street: String) = takagi.ru.monica.data.SecureItem(itemType = takagi.ru.monica.data.ItemType.BILLING_ADDRESS,
                title = "Office", itemData = kotlinx.serialization.json.Json.encodeToString(
                    takagi.ru.monica.data.model.BillingAddressData.serializer(),
                    takagi.ru.monica.data.model.BillingAddressData(streetAddress = street, city = "Shanghai", postalCode = "200000")))
            val snapshot = takagi.ru.monica.data.model.EmbeddedWalletContent.create(address("Old Street"), "ime-wallet")
            val fieldId = harness.room.customFieldDao().insert(CustomField(entryId = owner,
                title = "monica.content.wallet.address", value = manager.encryptData(snapshot.encode()), isProtected = true))
            harness.room.secureItemDao().insertItem(address("Standalone Street"))
            harness.state.value = harness.state.value.copy(activePanel = MonicaImePanel.DOCUMENTS)
            harness.refresh()
            assertEquals(2, harness.state.value.cardWalletEntries.size)
            val entry = harness.state.value.cardWalletEntries.single { it.id < 0 }
            assertEquals("Shopping · Office", entry.title)
            assertFalse(entry.supportsQuickFill)
            assertTrue(entry.fields.all { it.value.isEmpty() })
            val newSnapshot = takagi.ru.monica.data.model.EmbeddedWalletContent.create(address("Latest Street"), "ime-wallet")
            harness.room.customFieldDao().update(harness.room.customFieldDao().getFieldById(fieldId)!!.copy(value = manager.encryptData(newSnapshot.encode())))
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            withContext(Dispatchers.Main) { harness.fillWallet(entry, entry.fields.first().label) }
            (harness.field("customFieldFillJob") as Job).join()
            withContext(Dispatchers.Main) { assertEquals("Latest Street", editor.text.toString()) }
            assertEquals(1, harness.room.secureItemDao().getActiveItemsByTypeSync(takagi.ru.monica.data.ItemType.BILLING_ADDRESS).size)
        } finally { harness.close() }
    }

    @Test fun walletFillRejectsADeletedOrMovedParent() = runBlocking {
        val harness = Harness()
        try {
            val original = password("Legacy bank").copy(creditCardNumber = "4242424242424242")
            val owner = harness.room.passwordEntryDao().insertPasswordEntry(original)
            harness.state.value = harness.state.value.copy(activePanel = MonicaImePanel.DOCUMENTS)
            harness.refresh()
            val entry = harness.state.value.cardWalletEntries.single()
            val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
            for (changed in listOf(original.copy(id = owner, isDeleted = true), original.copy(id = owner, keepassDatabaseId = 99))) {
                harness.room.passwordEntryDao().updatePasswordEntry(changed)
                withContext(Dispatchers.Main) { harness.fillWallet(entry, entry.fields.first().label) }
                (harness.field("customFieldFillJob") as Job).join()
                withContext(Dispatchers.Main) { assertEquals("", editor.text.toString()) }
                assertNotNull(harness.state.value.errorMessage)
            }
        } finally { harness.close() }
    }

    @Test fun walletFillCannotCrossInputChangeOrVaultLockWhileReading() = runBlocking {
        for (lock in listOf(false, true)) {
            val gate = AtomicReference<(() -> Unit)?>(null)
            val harness = Harness(gate)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                harness.room.passwordEntryDao().insertPasswordEntry(password("Bank").copy(creditCardNumber = "4242424242424242"))
                harness.state.value = harness.state.value.copy(activePanel = MonicaImePanel.DOCUMENTS)
                harness.refresh()
                val entry = harness.state.value.cardWalletEntries.single()
                val editor = withContext(Dispatchers.Main) { harness.bindEditor() }
                gate.set { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                withContext(Dispatchers.Main) { harness.fillWallet(entry, entry.fields.first().label) }
                withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
                withContext(Dispatchers.Main) {
                    if (lock) SessionManager.markLocked()
                    else { harness.set("inputGeneration", harness.number("inputGeneration") + 1); harness.bindEditor() }
                }
                release.countDown()
                (harness.field("customFieldFillJob") as Job).join()
                withContext(Dispatchers.Main) { assertEquals("", editor.text.toString()) }
            } finally { release.countDown(); harness.close() }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(10)
    }

    private inner class Harness(gate: AtomicReference<(() -> Unit)?> = AtomicReference(null)) {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java)
            .openHelperFactory(CursorGateFactory(gate)).build()
        val service = ConnectedService()
        private val wasUnlocked = SessionManager.isUnlocked.value
        @Suppress("UNCHECKED_CAST")
        val state get() = field("uiState") as MutableStateFlow<MonicaImeUiState>
        init {
            SessionManager.attachAppContext(context)
            SessionManager.markUnlocked()
            MonicaInputMethodService::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            set("database", room); set("securityManager", SecurityManager(context))
            set("settingsManager", SettingsManager(context)); set("autofillPreferences", AutofillPreferences(context))
            state.value = MonicaImeUiState(unlocked = true, activePanel = MonicaImePanel.PASSWORDS, isAutofillPanelVisible = true)
        }
        fun field(name: String): Any? = MonicaInputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun number(name: String) = field(name) as Long
        fun set(name: String, value: Any) { MonicaInputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
        fun bindEditor(): EditText {
            val editor = EditText(context)
            service.connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            set("inputViewVisible", true)
            return editor
        }
        fun fillWallet(entry: MonicaImeCardWalletEntry, label: String) {
            MonicaInputMethodService::class.java.getDeclaredMethod("insertCurrentWalletField", MonicaImeCardWalletEntry::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(service, entry, label)
        }
        fun fillOtp(entry: MonicaImePasswordEntry) {
            MonicaInputMethodService::class.java.getDeclaredMethod("insertCurrentPasswordTotp", MonicaImePasswordEntry::class.java)
                .apply { isAccessible = true }.invoke(service, entry)
        }
        @Suppress("UNCHECKED_CAST")
        fun fields(entryId: Long): Flow<List<ImeCustomFieldRow>> =
            MonicaInputMethodService::class.java.getDeclaredMethod("observeImeCustomFields", java.lang.Long.TYPE)
                .apply { isAccessible = true }.invoke(service, entryId) as Flow<List<ImeCustomFieldRow>>
        fun fillField(entry: MonicaImePasswordEntry, fieldId: Long) {
            MonicaInputMethodService::class.java.getDeclaredMethod("insertCurrentCustomField", MonicaImePasswordEntry::class.java, java.lang.Long.TYPE)
                .apply { isAccessible = true }.invoke(service, entry, fieldId)
        }
        fun call(name: String) { MonicaInputMethodService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service) }
        suspend fun refresh() = withContext(Dispatchers.Main) {
            val method = MonicaInputMethodService::class.java.declaredMethods.single { it.name == "refreshVaultEntries" }.apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Any?> { method.invoke(service, false, it) }
        }
        suspend fun close() {
            withContext(Dispatchers.Main) { (field("serviceScope") as CoroutineScope).cancel() }
            room.close()
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    private class ConnectedService : MonicaInputMethodService() {
        var connection: InputConnection? = null
        override fun getCurrentInputConnection(): InputConnection? = connection
    }

    private class CursorGateFactory(private val gate: AtomicReference<(() -> Unit)?>) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
        private fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase = object : SupportSQLiteDatabase by database {
            override fun query(query: SupportSQLiteQuery): Cursor = wrap(query, database.query(query))
            override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                wrap(query, database.query(query, cancellationSignal))
        }
        private fun wrap(query: SupportSQLiteQuery, cursor: Cursor): Cursor {
            if (!query.sql.trimStart().startsWith("SELECT id, title, username") &&
                !query.sql.trimStart().startsWith("SELECT id, title, isFavorite")) return cursor
            return object : CursorWrapper(cursor) {
                override fun moveToFirst(): Boolean = super.moveToFirst().also { if (it) gate.getAndSet(null)?.invoke() }
                override fun moveToNext(): Boolean = super.moveToNext().also { if (it) gate.getAndSet(null)?.invoke() }
            }
        }
    }
}
