package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import uniffi.mdbx_ffi.*
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.*
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class MultiPasswordMdbxPortabilityTest {
    private val expected = listOf("clone-synthetic-first-315", "clone-synthetic-second-315")
    private val remoteVaultPath = "clone-315-20260929/vault.mdbx"
    private suspend fun scenario(block: suspend TransferFixture.(PasswordViewModel) -> Unit) {
        val f = TransferFixture()
        val preferences = f.context.getSharedPreferences("webdav_config", android.content.Context.MODE_PRIVATE)
        val hadHttpPreference = preferences.contains("allow_insecure_http")
        val previousHttpPreference = preferences.getBoolean("allow_insecure_http", false)
        // Test-only localhost transport; restore the user's policy even on failure.
        preferences.edit().putBoolean("allow_insecure_http", true).commit()
        val model = PasswordViewModel(f.passwords, f.security, context = f.context,
            customFieldRepository = CustomFieldRepository(f.db.customFieldDao()), strings = AppLocaleStringResolver(f.context))
        try { f.block(model) } finally {
            model.viewModelScope.cancel()
            if (hadHttpPreference) preferences.edit().putBoolean("allow_insecure_http", previousHttpPreference).commit()
            else preferences.edit().remove("allow_insecure_http").commit()
            f.close()
        }
    }
    private fun TransferFixture.transport() = WebDavMdbxRemoteTransport(
        "http://127.0.0.1:18794/", "", "", AppLocaleStringResolver(context))
    private fun TransferFixture.coordinator() = Mdbx2RemoteSyncCoordinator(File(root, "sync"),
        Mdbx2RepositorySyncSessionProvider(mdbx), MdbxSyncStateStore(db.mdbxSyncStateDao()))

    @Test fun nestedLocalCiphertextMustNotEscapeIntoMdbx() = runBlocking {
        scenario { model ->
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-nested", username = "alice",
                password = security.encryptData(security.encryptData(expected[0])), website = "https://example.invalid"))
            val target = mdbx()
            model.movePasswordsToMdbxDatabaseAwait(listOf(id), target.databaseId)
            val native = JSONObject(mdbx.readStoredEntries(target.databaseId).single { !it.deleted }.payloadJson)
            assertEquals("MDBX must not retain an inner installation-bound ciphertext", expected[0], native.getString("password_plain"))
        }
    }

    @Test fun editingMovedProjectKeepsDistinctNativeObjects() = runBlocking {
        scenario { model ->
            val ids = expected.map { password -> db.passwordEntryDao().insertPasswordEntry(
                PasswordEntry(title = "$prefix-edit", username = "alice", password = security.encryptData(password),
                    website = "https://example.invalid", passwordGroupId = "$prefix-group")) }
            val target = mdbx()
            model.movePasswordsToMdbxDatabaseAwait(ids, target.databaseId)
            val before = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.id in ids }
            val edited = expected.map { "$it-edited" }
            val done = CompletableDeferred<Long?>()
            model.savePasswordsAcrossTargets(ids, before.first(), edited,
                listOf(StorageTarget.Mdbx(target.databaseId)), onComplete = { done.complete(it) })
            assertNotNull(withTimeout(30_000) { done.await() })
            val native = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
            assertEquals(2, native.size)
            assertEquals(edited.toSet(), native.map { JSONObject(it.payloadJson).getString("password_plain") }.toSet())
            val after = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.id in ids }
            assertEquals(2, after.map { it.replicaGroupId }.distinct().size)
            assertEquals(2, takagi.ru.monica.ui.screens.resolvePasswordDetailGroupPasswords(after.first(), after).size)
            val expanded = edited + "third-synthetic-password"
            val expandedDone = CompletableDeferred<Long?>()
            model.savePasswordsAcrossTargets(ids, after.first(), expanded,
                listOf(StorageTarget.Mdbx(target.databaseId)), onComplete = { expandedDone.complete(it) })
            assertNotNull(withTimeout(30_000) { expandedDone.await() })
            val expandedNative = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
            assertEquals(3, expandedNative.size)
            assertEquals(expanded.toSet(), expandedNative.map { JSONObject(it.payloadJson).getString("password_plain") }.toSet())
        }
    }

    @Test fun unreadablePasswordAbortsMoveAndRetainsSource() = runBlocking {
        scenario { model ->
            val original = "C2|invalid-synthetic-ciphertext"
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-failed-move", username = "alice",
                password = original, website = "https://example.invalid"))
            val target = mdbx()
            assertTrue(runCatching { model.movePasswordsToMdbxDatabaseAwait(listOf(id), target.databaseId) }.isFailure)
            val retained = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            assertEquals(original, retained.password)
            assertNull(retained.mdbxDatabaseId)
            assertTrue(mdbx.readStoredEntries(target.databaseId).none { !it.deleted })
        }
    }

    @Test fun legacyRepairPreservesUnknownFieldsAndSkipsUnreadableValues() = runBlocking {
        scenario { model ->
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-repair", username = "alice",
                password = security.encryptData(expected[0]), website = "https://example.invalid"))
            val target = mdbx()
            model.movePasswordsToMdbxDatabaseAwait(listOf(id), target.databaseId)
            val stored = mdbx.readStoredEntries(target.databaseId).single { !it.deleted }
            val legacy = buildJsonObject {
                Json.parseToJsonElement(stored.payloadJson).jsonObject.forEach { (key, value) -> put(key, value) }
                put("password_plain", security.encryptData(expected[0]))
                put("future_fields", Json.parseToJsonElement("""{"decimal":1.2345678901234567890123456789,"nested":[null,false,"keep"]}"""))
            }
            Mdbx2NativeReadSessions.clear()
            val path = requireNotNull(db.localMdbxDatabaseDao().getDatabaseById(target.databaseId)).filePath
            openVault(path, "Synthetic transfer fixture password", "synthetic-repair").use { vault ->
                val physicalId = mdbx2PhysicalEntryId(vault.info().vaultId, stored.entryId)
                val record = requireNotNull(vault.revealObject(physicalId).`object`)
                vault.executeWriteOperation(UUID.randomUUID().toString(), "seed-old-ciphertext", listOf(
                    MdbxWriteCommand.UpdateEntry(physicalId, record.collectionId, record.objectTypeId, record.title, legacy.toString()),
                    MdbxWriteCommand.CreateEntry(UUID.randomUUID().toString(), record.collectionId, "login", "$prefix-unreadable",
                        """{"password_plain":"C2|invalid-synthetic-ciphertext","future":"untouched"}""")))
            }
            assertEquals(1, mdbx.repairReadablePasswordCiphertexts(target.databaseId))
            assertEquals(0, mdbx.repairReadablePasswordCiphertexts(target.databaseId))
            val repaired = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
            val payload = Json.parseToJsonElement(repaired.single { it.title == stored.title }.payloadJson).jsonObject
            assertEquals(expected[0], payload.getValue("password_plain").jsonPrimitive.content)
            assertEquals(legacy["future_fields"], payload["future_fields"])
            val unreadable = JSONObject(repaired.single { it.title == "$prefix-unreadable" }.payloadJson)
            assertEquals("C2|invalid-synthetic-ciphertext", unreadable.getString("password_plain"))
            assertEquals("untouched", unreadable.getString("future"))
        }
    }

    @Test fun publishExplicitPasswordsThroughWebDavFromFirstSandbox() = runBlocking {
        scenario { model ->
            val target = mdbx()
            val coordinator = coordinator()
            val transport = transport()
            coordinator.publishBootstrap(target.databaseId, remoteVaultPath, transport)
            val done = CompletableDeferred<Long?>()
            model.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = "$prefix-clone", username = "alice", password = "",
                website = "https://example.invalid"), expected, listOf(StorageTarget.MonicaLocal(null)), onComplete = { done.complete(it) })
            requireNotNull(withTimeout(30_000) { done.await() })
            val local = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.title == "$prefix-clone" }
            assertEquals(2, local.size)
            // Reproduce historical nested encryption, readable in the originating installation.
            db.passwordEntryDao().updatePasswordEntry(local.first().copy(password = security.encryptData(local.first().password)))
            model.movePasswordsToMdbxDatabaseAwait(local.map { it.id }, target.databaseId)
            coordinator.synchronize(target.databaseId, remoteVaultPath, transport)
            val probe = File(root, "probe.txt").apply { writeText(security.encryptData("source-sandbox-only")) }
            transport.writeFrom("clone-315-20260929/probe.txt", probe, MdbxRemoteWriteMode.REPLACE)
            val native = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }.map { JSONObject(it.payloadJson) }
            assertEquals(expected.toSet(), native.map { it.getString("password_plain") }.toSet())
            assertEquals(1, native.map { it.getString("password_group_id") }.distinct().size)
        }
    }

    @Test fun receiveExplicitPasswordsThroughWebDavInIndependentSandbox() = runBlocking {
        scenario { model ->
            val target = mdbx()
            val transport = transport()
            val probe = File(root, "probe.txt")
            transport.readTo("clone-315-20260929/probe.txt", probe)
            assertTrue("Receiver must have an independent Android key", runCatching { security.decryptData(probe.readText()) }.isFailure)
            val coordinator = coordinator()
            val file = File(requireNotNull(db.localMdbxDatabaseDao().getDatabaseById(target.databaseId)).filePath)
            Mdbx2NativeReadSessions.clear()
            coordinator.downloadBootstrapTo(remoteVaultPath, transport, file)
            coordinator.registerDownloadedBootstrap(target.databaseId, remoteVaultPath)
            coordinator.synchronize(target.databaseId, remoteVaultPath, transport)
            val manager = MdbxViewModel(context.applicationContext as android.app.Application,
                databaseDao = db.localMdbxDatabaseDao(), remoteSourceDao = db.mdbxRemoteSourceDao(),
                passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(), passkeyDao = db.passkeyDao(),
                attachmentDao = db.attachmentDao(), customFieldDao = db.customFieldDao(), securityManager = security)
            try {
                manager.syncVault(target.databaseId)
                val result = withTimeout(45_000) { manager.operationState.first {
                    it is MdbxViewModel.OperationState.Success || it is MdbxViewModel.OperationState.Error
                } }
                assertTrue(result.toString(), result is MdbxViewModel.OperationState.Success)
                val entries = db.passwordEntryDao().getAllPasswordEntriesSync().filter { it.mdbxDatabaseId == target.databaseId }
                assertEquals(2, entries.size)
                assertEquals(expected.toSet(), entries.map { security.decryptData(it.password) }.toSet())
                assertEquals(2, takagi.ru.monica.ui.screens.resolvePasswordDetailGroupPasswords(entries.first(), entries).size)
            } finally { manager.viewModelScope.cancel() }
        }
    }
}
