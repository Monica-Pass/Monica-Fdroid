package takagi.ru.monica.credentialexchange

import android.content.pm.PackageManager
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CxfImportCompatibilityInstrumentedTest {
    @Test fun disabledPaymentExtensionImportsOriginalSigningKeyAndRetainsSkipReasons() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val root = Json.parseToJsonElement(CxfCredentialCodec.encode(decoded().items, "Synthetic", setOf("basic-auth", "passkey"))).jsonObject
            val account = root["accounts"]!!.jsonArray.single().jsonObject
            val item = account["items"]!!.jsonArray.single().jsonObject
            val credentials = item["credentials"]!!.jsonArray.map { element ->
                val credential = element.jsonObject
                if (credential["type"]?.jsonPrimitive?.content == "passkey") JsonObject(credential +
                    ("fido2Extensions" to buildJsonObject { put("payments", false) })) else credential
            } + buildJsonObject { put("type", "totp") }
            val input = JsonObject(root + ("accounts" to JsonArray(listOf(JsonObject(account +
                ("items" to JsonArray(listOf(JsonObject(item + ("credentials" to JsonArray(credentials)))))))))))
            val source = CxfCredentialCodec.decode(input.toString())
            for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                val result = importer.importExchange(source, target)
                assertEquals(target.kind.name, 2, result.imported)
                assertEquals(target.kind.name, 0, result.failed)
                assertEquals(mapOf(CxfCredentialCodec.SkipReason.UNSUPPORTED_TOTP to 1), result.skippedDuringDecode)
                assertEquals(1, result.skipped)
                assertOriginalKey(importedKeys(target).single())
                if (target.kind == ImportDestinationKind.KEEPASS) assertEquals("KEEPASS_COMPAT", importedKeys(target).single().passkeyMode)
                val exported = CxfCredentialCodec.decode(CredentialExchangeExporter(context).prepare(target, setOf("passkey")).json)
                assertTrue(exported.items.flatMap { it.passkeys }.any { it.credentialId == credentialId })
            }
        } } finally { fixture.close() }
    }

    @Test fun signedAndroidScopesRoundTripWithoutTrustingAnUnverifiedCertificate() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val signature = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners[0]
            val hash = CxfCredentialCodec.base64Url(MessageDigest.getInstance("SHA-512").digest(signature.toByteArray()))
            val apps = Json.parseToJsonElement("""[{"bundleId":"${context.packageName}","name":"Synthetic app","certificate":{"hashAlg":"sha512","fingerprint":"$hash"}}]""").jsonArray
            val source = decoded().let { it.copy(items = it.items.map { item -> item.copy(androidApps = apps) }) }
            for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                assertEquals(2, importer.importExchange(source, target).imported)
                assertEquals(context.packageName, importedPasswords(target).single().appPackageName)
                val exported = CredentialExchangeExporter(context).prepare(target, setOf("basic-auth"))
                assertEquals(apps, CxfCredentialCodec.decode(exported.json).items.single { it.title.startsWith(prefix) }.androidApps)
            }
            val wrong = Json.parseToJsonElement(apps.toString().replace(hash, CxfCredentialCodec.base64Url(ByteArray(64)))).jsonArray
            val badSource = source.copy(items = source.items.map { it.copy(title = "$prefix-unverified", passkeys = emptyList(), androidApps = wrong) })
            assertEquals(1, importer.importExchange(badSource, ImportDestination.Local).imported)
            val stored = importedPasswords(ImportDestination.Local).single { it.title == "$prefix-unverified" }
            assertEquals("", stored.appPackageName)
            assertEquals(wrong.toString(), db.customFieldDao().getFieldsByEntryIdSync(stored.id)
                .single { it.title == CxfAndroidAppScope.FIELD_NAME }.value)
        } } finally { fixture.close() }
    }
}
