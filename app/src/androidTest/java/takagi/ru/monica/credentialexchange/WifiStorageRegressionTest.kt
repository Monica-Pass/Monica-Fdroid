package takagi.ru.monica.credentialexchange

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.*

class WifiStorageRegressionTest {
    @Test fun mdbxPreservesCompleteWifiMetadataAcrossNativeReopen() = runBlocking {
        val fixture = TransferFixture()
        try {
            val target = fixture.mdbx()
            val raw = """{"ssid":"Lab;网络","security":"WPA3","hiddenNetwork":true,"eap":{"domain":"example.org"},"future":{"nested":[1,true]}}"""
            val id = fixture.passwords.insertPasswordEntry(PasswordEntry(title = fixture.prefix, username = "identity", website = "",
                password = fixture.security.encryptData("synthetic-wifi"), loginType = "WIFI", wifiMetadata = raw,
                mdbxDatabaseId = target.databaseId))
            takagi.ru.monica.repository.Mdbx2NativeReadSessions.clear()
            val payload = fixture.mdbx.readStoredEntries(target.databaseId).single { !it.deleted && it.entryType == "login" }
            assertEquals(raw, JSONObject(payload.payloadJson).optString("wifi_metadata"))
            val coldProjection = takagi.ru.monica.repository.MdbxPasswordContentFields.readInto(JSONObject(payload.payloadJson),
                PasswordEntry(title = "Fallback", username = "", password = "", website = "", loginType = "WIFI"))
            assertEquals(raw, coldProjection.wifiMetadata)
            assertEquals("Lab;网络", WifiEntryDetails.from(coldProjection).ssid)
            // Delete only this fixture's Room cache row, retaining the native vault record.
            fixture.db.passwordEntryDao().deletePasswordEntryById(id)
            val scopedDao = object : takagi.ru.monica.data.LocalMdbxDatabaseDao by fixture.db.localMdbxDatabaseDao() {
                override fun getAllDatabases() = fixture.db.localMdbxDatabaseDao().getAllDatabases().map { list -> list.filter { it.id == target.databaseId } }
                override suspend fun getAllDatabasesSnapshot() = fixture.db.localMdbxDatabaseDao().getAllDatabasesSnapshot().filter { it.id == target.databaseId }
            }
            val isolated = object : android.content.ContextWrapper(fixture.context) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${fixture.prefix}-$name", mode)
            }
            val app = object : android.app.Application() {
                init { attachBaseContext(isolated) }
                override fun getApplicationContext(): android.content.Context = this
            }
            val store = androidx.lifecycle.ViewModelStore()
            try {
                withContext(Dispatchers.Main) {
                    takagi.ru.monica.viewmodel.MdbxViewModel(app, scopedDao, fixture.db.mdbxRemoteSourceDao(),
                        fixture.db.passwordEntryDao(), fixture.db.secureItemDao(), fixture.db.passkeyDao(),
                        fixture.db.attachmentDao(), fixture.db.customFieldDao(), fixture.security).also {
                        store.put("wifi-regression", it); it.activateMdbxDatabase(target.databaseId)
                    }
                }
                val restored = withTimeout(30000) {
                    var rows = fixture.db.passwordEntryDao().getByMdbxDatabaseIdSync(target.databaseId)
                    while (rows.isEmpty()) { delay(50); rows = fixture.db.passwordEntryDao().getByMdbxDatabaseIdSync(target.databaseId) }
                    rows.single()
                }
                assertEquals(raw, restored.wifiMetadata)
                assertEquals("Lab;网络", WifiEntryDetails.from(restored).ssid)
            } finally { withContext(Dispatchers.Main) { store.clear() } }
        } finally { fixture.close() }
    }
}
