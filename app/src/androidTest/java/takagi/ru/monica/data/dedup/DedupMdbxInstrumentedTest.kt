package takagi.ru.monica.data.dedup

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.credentialexchange.CxfPasskeyMaterial
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Read the real Rust MDBX file, rather than treating a Room mirror as proof of persistence. */
@RunWith(AndroidJUnit4::class)
class DedupMdbxInstrumentedTest {
    @Test fun mergedPasswordsCustomFieldsAndNotesSurviveReopeningMdbx() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.keepass()
            val target = fixture.mdbx()
            val sourceIds = (1..2).map {
                fixture.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                    title = "${fixture.prefix}-login", website = "https://example.invalid", username = "alice",
                    password = fixture.security.encryptData("synthetic password"), keepassDatabaseId = source.databaseId))
            }
            fixture.db.customFieldDao().insertAll(sourceIds.map {
                CustomField(entryId = it, title = "Recovery", value = "synthetic recovery", isProtected = true)
            })
            fixture.db.secureItemDao().insertItem(SecureItem(title = "${fixture.prefix}-note", itemType = ItemType.NOTE,
                itemData = Json.encodeToString(NoteData(content = "Preserve this note")), keepassDatabaseId = source.databaseId))
            val material = checkNotNull(CxfPasskeyMaterial.decode(fixture.pair.private.encoded))
            fixture.passkeys.savePasskey(PasskeyEntry(credentialId = fixture.credentialId, rpId = fixture.rpId,
                rpName = "${fixture.prefix}-passkey", userId = fixture.userHandle, userName = "alice", userDisplayName = "Alice",
                publicKey = material.cosePublicKey, privateKeyAlias = java.util.Base64.getEncoder().encodeToString(fixture.pair.private.encoded),
                keepassDatabaseId = source.databaseId, passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT))
            val service = DedupMergeService(fixture.passwords, fixture.secureItems, fixture.passkeys,
                CustomFieldRepository(fixture.db.customFieldDao()), fixture.db.localKeePassDatabaseDao(),
                fixture.db.localMdbxDatabaseDao(), fixture.db.bitwardenVaultDao(), fixture.security, AppLocaleStringResolver(fixture.context), fixture.db)
            val plan = service.buildPlan(setOf("keepass:${source.databaseId}"),
                DedupMergeTarget.MdbxDatabase(target.databaseId!!, "Target"))
            assertEquals(3, plan.writableItems)
            val result = service.executePlan(plan)
            assertEquals(result.failures.toString(), 3, result.insertedItems)
            Mdbx2NativeReadSessions.clear()
            val stored = fixture.mdbx.readStoredEntries(target.databaseId!!).filterNot { it.deleted }
            assertEquals(3, stored.size)
            val login = JSONObject(stored.single { it.entryType == "login" }.payloadJson)
            assertEquals("synthetic password", login.getString("password_plain"))
            val fields = login.getJSONArray("custom_fields")
            assertEquals("The actual MDBX payload must include custom fields", 1, fields.length())
            assertEquals("synthetic recovery", fields.getJSONObject(0).getString("value"))
            assertTrue(stored.any { it.payloadJson.contains("Preserve this note") })
            val passkey = JSONObject(stored.single { it.entryType == "passkey" }.payloadJson)
            assertEquals(fixture.credentialId, passkey.getString("credential_id"))
            assertEquals(0L, passkey.getLong("sign_count"))
            val privateKey = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(passkey.getString("private_key_alias"))).privateKey
            val challenge = "synthetic MDBX round trip".toByteArray()
            val signature = PasskeyPrivateKeySupport.createSignature(privateKey, -7).apply { update(challenge) }.sign()
            assertTrue(java.security.Signature.getInstance("SHA256withECDSA").apply {
                initVerify(fixture.pair.public); update(challenge)
            }.verify(signature))
            assertEquals(0, service.executePlan(plan).insertedItems)
            assertEquals(2, fixture.db.passwordEntryDao().getAllPasswordEntriesSync().count { it.id in sourceIds })
            assertEquals(1, fixture.passkeys.getAllPasskeysSync().count { it.keepassDatabaseId == source.databaseId })
        } finally { fixture.close() }
    }
}
