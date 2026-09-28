package takagi.ru.monica.credentialexchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.ui.screens.buildPasswordScreenAuthenticatorPayload
import takagi.ru.monica.util.OtpParametersDraft
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.utils.BackupContent
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.WebDavHelper
import app.keemobile.kotpass.models.EntryValue
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.CipherSyncProcessor
import takagi.ru.monica.bitwarden.api.CipherApiResponse

@RunWith(AndroidJUnit4::class)
class PasswordOtpStorageInstrumentedTest {
    @Test fun keepassPasswordEditPreservesExistingSteamMetadata() = runBlocking {
        val fixture = TransferFixture()
        try {
            with(fixture) {
                val target = keepass()
                val payload = buildPasswordScreenAuthenticatorPayload("JBSWY3DPEHPK3PXP", OtpType.STEAM,
                    "Steam", "alice")
                importer.apply(BackupContent(listOf(PasswordEntry(title = "$prefix-steam", username = "alice",
                    password = "fixture", website = "", authenticatorKey = payload)), emptyList()), target)
                val row = importedPasswords(target).single()
                val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                val data = requireNotNull(TotpDataResolver.fromAuthenticatorKey(payload)).copy(steamDeviceId = "fixture-device",
                    steamRevocationCode = "fixture-revocation", steamRawJson = "{\"fixture\":true}")
                service.addOrUpdateSecureItems(target.databaseId, listOf(takagi.ru.monica.data.SecureItem(
                    itemType = takagi.ru.monica.data.ItemType.TOTP, title = row.title,
                    itemData = Json.encodeToString(takagi.ru.monica.data.model.TotpData.serializer(), data),
                    keepassDatabaseId = target.databaseId, keepassEntryUuid = row.keepassEntryUuid)), forceSyncWrite = true).getOrThrow()
                service.addOrUpdatePasswordEntries(target.databaseId, listOf(row.copy(notes = "edited login")),
                    resolvePassword = { security.decryptDataIfMonicaCiphertext(it.password) }, forceSyncWrite = true).getOrThrow()
                KeePassKdbxService.invalidateProcessCache(target.databaseId)
                val native = service.loadWorkspace(target.databaseId).getOrThrow()
                assertEquals("edited login", native.passwords.single().notes)
                val restored = requireNotNull(TotpDataResolver.parseStoredItemData(native.secureItems.single().item.itemData))
                assertEquals(data.steamDeviceId, restored.steamDeviceId)
                assertEquals(data.steamRevocationCode, restored.steamRevocationCode)
                assertEquals(data.steamRawJson, restored.steamRawJson)
            }
        } finally { fixture.close() }
    }

    @Test fun allPasswordOtpTypesSurviveMdbxNativeReopen() = verify(ImportDestinationKind.MDBX)
    @Test fun allPasswordOtpTypesSurviveKeePassNativeReopen() = verify(ImportDestinationKind.KEEPASS)
    @Test fun allPasswordOtpTypesSurviveBitwardenFixtureAndBackup() = verify(ImportDestinationKind.BITWARDEN)

    private fun verify(kind: ImportDestinationKind) = runBlocking {
        val fixture = TransferFixture()
        try {
            with(fixture) {
                val target = when (kind) {
                    ImportDestinationKind.MDBX -> mdbx()
                    ImportDestinationKind.KEEPASS -> keepass()
                    else -> bitwarden()
                }
                val originals = OtpType.entries.associateWith { type ->
                    val parameters = OtpParametersDraft(period="45", digits="8", algorithm="SHA256", counter="42", pin="0421").selectType(type)
                    val secret = if (type == OtpType.MOTP) "a1b2c3d4e5f60708" else "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                    buildPasswordScreenAuthenticatorPayload(secret, type, "Example", "alice@example.invalid", parameters)
                }
                val rows = originals.entries.mapIndexed { index, (type, payload) ->
                    PasswordEntry(id = 1000L + index, title = "$prefix-${type.name}", username = "alice-$index",
                        password = "synthetic-password", website = "https://example.invalid/$index", authenticatorKey = payload)
                }
                assertEquals(5, importer.apply(BackupContent(rows, emptyList()), target).imported)
                val imported = importedPasswords(target)
                assertEquals(5, imported.size)
                imported.forEach { row ->
                    val type = OtpType.valueOf(row.title.removePrefix("$prefix-"))
                    assertOtp(originals.getValue(type), security.decryptDataIfMonicaCiphertext(row.authenticatorKey))
                }
                if (kind == ImportDestinationKind.MDBX) {
                    Mdbx2NativeReadSessions.clear()
                    val native = mdbx.readStoredEntries(target.databaseId).filterNot { it.deleted }
                    assertEquals(5, native.size)
                    native.forEach { row ->
                        val json = JSONObject(row.payloadJson)
                        val type = OtpType.valueOf(row.title.removePrefix("$prefix-"))
                        assertOtp(originals.getValue(type), json.getString("authenticator_key"))
                    }
                }
                if (kind == ImportDestinationKind.KEEPASS) {
                    KeePassKdbxService.invalidateProcessCache(target.databaseId)
                    val native = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                        .loadWorkspace(target.databaseId).getOrThrow().passwords
                    assertEquals(5, native.size)
                    native.forEach { row ->
                        val type = OtpType.valueOf(row.title.removePrefix("$prefix-"))
                        assertOtp(originals.getValue(type), security.decryptDataIfMonicaCiphertext(row.authenticatorKey))
                    }
                    keepassEntries(target.databaseId).forEach { row ->
                        assertTrue(row.fields.getValue("otp") is EntryValue.Encrypted)
                    }
                    // Export must reconstruct OTP from the native file, not a stale Room value.
                    imported.forEach { db.passwordEntryDao().update(it.copy(authenticatorKey = "")) }
                }
                if (kind == ImportDestinationKind.BITWARDEN) {
                    val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(target.databaseId))
                    BitwardenSyncService(context).uploadLocalEntries(vault, accessToken, vaultKey)
                    assertEquals(5, remote.created.size)
                    imported.forEach { db.passwordEntryDao().deletePasswordEntryById(it.id) }
                    val processor = CipherSyncProcessor(context)
                    remote.created.forEach { encrypted ->
                        originals.values.forEach { assertFalse(encrypted.toString().contains(it)) }
                        val cipher = Json { ignoreUnknownKeys = true }.decodeFromString<CipherApiResponse>(encrypted.toString())
                        processor.syncCipherFromServer(vault, cipher, vaultKey)
                    }
                    val downloaded = importedPasswords(target)
                    assertEquals(5, downloaded.size)
                    downloaded.forEach { row ->
                        val type = OtpType.valueOf(row.title.removePrefix("$prefix-"))
                        assertOtp(originals.getValue(type), security.decryptDataIfMonicaCiphertext(row.authenticatorKey))
                    }
                }
                val archive = model.prepareZipBackup(backupEncryptionPassword = "synthetic-otp-archive", source = target,
                    preferences = BackupPreferences(includeImages = false)).getOrThrow().first
                try {
                    val restored = WebDavHelper(context).restoreFromBackupFile(archive, "synthetic-otp-archive",
                        restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
                    assertEquals(5, restored.passwords.size)
                    restored.passwords.forEach { row ->
                        val type = OtpType.valueOf(row.title.removePrefix("$prefix-"))
                        assertOtp(originals.getValue(type), security.decryptDataIfMonicaCiphertext(row.authenticatorKey))
                    }
                } finally { archive.delete() }
            }
        } finally { fixture.close() }
    }

    private fun assertOtp(expected: String, actual: String) {
        val left = requireNotNull(TotpDataResolver.fromAuthenticatorKey(expected))
        val right = requireNotNull(TotpDataResolver.fromAuthenticatorKey(actual))
        assertEquals(left.otpType, right.otpType)
        assertEquals(left.secret, right.secret)
        assertEquals(left.period, right.period)
        assertEquals(left.digits, right.digits)
        assertEquals(left.algorithm, right.algorithm)
        if (left.otpType == OtpType.HOTP) assertEquals(left.counter, right.counter)
        if (left.otpType in listOf(OtpType.MOTP, OtpType.YANDEX)) assertEquals(left.pin, right.pin)
        assertEquals(TotpGenerator.generateOtp(left, currentSeconds=1_700_000_000),
            TotpGenerator.generateOtp(right, currentSeconds=1_700_000_000))
    }
}
