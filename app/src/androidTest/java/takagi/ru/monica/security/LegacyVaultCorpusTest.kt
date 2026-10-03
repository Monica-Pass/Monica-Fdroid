package takagi.ru.monica.security

import android.database.Cursor
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.utils.GpgKeyGenerator
import takagi.ru.monica.utils.SshKeyGenerator
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/** Deliberately uses pre-recovery APIs: run seed on the OLD APK, then verify on either APK.
 * Requires a newly created Android test user, never the owner's public test application data.
 */
class LegacyVaultCorpusTest {
    @Test fun seedOldApk() = runBlocking { LegacyVaultCorpus().seed() }
    @Test fun verifyAfterProcessRestart() = runBlocking { LegacyVaultCorpus().verify() }
    @Test fun completeOldApkExtras() = runBlocking { LegacyVaultCorpus().completeExtras() }
}

internal class LegacyVaultCorpus {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val db = PasswordDatabase.getDatabase(context)
    val security = SecurityManager(context)
    val root = File(context.filesDir, "recovery-upgrade-corpus")
    private val json = Json { encodeDefaults = true }
    val marker = File(root, "expected.json")
    val password = "Synthetic-upgrade-corpus-316!"
    val prefix = "Recovery corpus "
    val attachments get() = AttachmentContainer.facade(context)
    val mdbx get() = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security,
        passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(), customFieldDao = db.customFieldDao())
    val binary = ByteArray(196_613) { ((it * 31 + 7) % 256).toByte() }
    val stamp = Date(1_700_000_000_123L)

    fun authorizeFixture() {
        check(InstrumentationRegistry.getArguments().getString("isolatedRecoveryUser") == "yes")
        check(android.os.Process.myUid() / 100_000 >= 11) { "Use a dedicated Android test user" }
        SessionManager.markUnlocked()
        Mdbx2NativeReadSessions.updateForeground(true)
    }

    suspend fun seed() {
        authorizeFixture()
        check(!marker.exists()) { "Corpus already exists: never overwrite upgrade evidence" }
        check(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
        check(!security.isMasterPasswordSet()) { "Do not replace an existing user's master password" }
        root.mkdirs()
        // Old APK returns Unit; new APK returns Boolean. Reflection keeps the seeder binary-compatible.
        SecurityManager::class.java.getMethod("setMasterPassword", String::class.java).invoke(security, password)
        assertTrue(security.unlockVaultWithPassword(password))
        val secureFields = listOf(SecureCustomField("Hidden recovery", "  secret;恢复\n🔑  ", SecureCustomFieldType.HIDDEN),
            SecureCustomField("Boolean", "true", SecureCustomFieldType.BOOLEAN))
        val faceName = CardFaceAttachment.fileNameFor("recovery316fixture00000000001")
        val face = CardFaceConfig(faceName)
        val bank = BankCardData("4111111111111111", "Synthetic Alice", "09", "2037", cvv = "123", bankName = "Test bank",
            pin = "9753", iban = "GB82WEST12345698765432", swiftBic = "TESTGB22", routingNumber = "110000000",
            accountNumber = "1234567890", branchCode = "007", currency = "CNY", validFromMonth = "01", validFromYear = "2025",
            customerServicePhone = "5550100", customFields = secureFields, cardFace = face)
        val address = BillingAddressData("Synthetic Alice", "Example", "No. 1 测试路", "B / 12", "Shanghai", "Shanghai",
            "200000", "CN", "+860000", "fixture@example.invalid", true, secureFields, face)
        val note = NoteData("# Markdown\n\n  中文 / Zażółć / 🔑  \n![image]($faceName)", listOf("tag", "中文"), true, secureFields)
        val wallet = mutableListOf<SecureItem>()
        suspend fun item(kind: ItemType, suffix: String, data: String, encrypted: Boolean = true): SecureItem {
            val value = SecureItem(itemType = kind, title = prefix + suffix, notes = "  notes\n🔑  ",
                itemData = if (encrypted) security.encryptDataLegacyCompat(data) else data,
                createdAt = stamp, updatedAt = stamp, isFavorite = true, sortOrder = wallet.size)
            val saved = value.copy(id = db.secureItemDao().insertItem(value))
            wallet += saved
            return saved
        }
        OtpType.entries.forEach { type ->
            val data = TotpData(secret = if (type == OtpType.MOTP) "1234567890abcdef" else "JBSWY3DPEHPK3PXP",
                issuer = "Synthetic $type", accountName = "fixture@example.invalid", otpType = type,
                digits = if (type == OtpType.STEAM) 5 else 6, counter = 17, pin = "1234", period = 30,
                link = "https://example.invalid/otp", associatedApp = "org.example.fixture")
            item(ItemType.TOTP, "OTP $type", json.encodeToString(data))
        }
        item(ItemType.BANK_CARD, "Bank", json.encodeToString(bank))
        item(ItemType.BILLING_ADDRESS, "Address", json.encodeToString(address))
        item(ItemType.NOTE, "Note", json.encodeToString(note), encrypted = false)
        DocumentType.entries.forEach { type -> item(ItemType.DOCUMENT, "Document $type", json.encodeToString(
            DocumentData(type, "DOC-0001", "Synthetic Alice", issuedDate = "2020-01-02", expiryDate = "2030-09-10",
                issuedBy = "Test agency", nationality = "CN", firstName = "Alice", middleName = "B", lastName = "Test",
                address1 = "Line 1", address2 = "Line 2", address3 = "Line 3", city = "Test city", stateProvince = "State",
                postalCode = "123456", country = "CN", company = "Example", email = "fixture@example.invalid",
                phone = "5550100", ssn = "SYNTHETIC", username = "alice", passportNumber = "P00001",
                licenseNumber = "L00001", customFields = secureFields, cardFace = face))) }
        PaymentAccountType.entries.forEach { type -> item(ItemType.PAYMENT_ACCOUNT, "Payment $type", json.encodeToString(
            PaymentAccountData(paymentType = type, provider = "Test provider", accountName = "alice", accountId = "fixture-account",
                email = "fixture@example.invalid", iban = "GB82WEST12345698765432", customFields = secureFields)), encrypted = false) }

        val ssh = SshKeyGenerator.generate(SshKeyGenerator.Request.Ed25519, "fixture@example.invalid")
        val gpg = GpgKeyGenerator.generate("Synthetic Alice", "fixture@example.invalid")
        val entries = mutableListOf<PasswordEntry>()
        suspend fun entry(suffix: String, type: String = "PASSWORD", secret: String = "  secret-$suffix;\"\n🔑  ",
            group: String? = null): PasswordEntry {
            val value = PasswordEntry(title = prefix + suffix, username = "  alice+测试  ", website = "https://example.invalid/path?a=b",
                password = security.encryptData(secret), notes = "  rich notes\nLine 2 🔑  ", createdAt = stamp, updatedAt = stamp,
                loginType = type, passwordGroupId = group, isFavorite = true, sortOrder = entries.size,
                authenticatorKey = security.encryptDataLegacyCompat("otpauth://totp/Test:alice?secret=JBSWY3DPEHPK3PXP&issuer=Test"),
                creditCardNumber = security.encryptDataLegacyCompat(bank.cardNumber), creditCardCVV = security.encryptDataLegacyCompat(bank.cvv),
                creditCardHolder = bank.cardholderName, creditCardExpiry = "09/37", email = address.email, phone = address.phone,
                addressLine = address.streetAddress, city = address.city, country = address.country,
                wifiMetadata = if (type == "WIFI") WifiData(ssid = "Network;测试", hiddenNetwork = true).toJson() else "",
                sshKeyData = if (type == "SSH_KEY") security.encryptDataLegacyCompat(SshKeyDataCodec.encode(ssh)) else "",
                customIconType = "SIMPLE_ICON", customIconValue = "github")
            val saved = value.copy(id = db.passwordEntryDao().insert(value)); entries += saved; return saved
        }
        val main = entry("Everything", group = "recovery-upgrade-multi")
        entry("Everything second password", group = "recovery-upgrade-multi")
        entry("WiFi", "WIFI")
        val api = entry("API Key", "API_KEY")
        val gpgEntry = entry("GPG", "GPG_KEY", gpg.privateKey)
        entry("SSH", "SSH_KEY")
        entry("Legacy SSO", "SSO")
        val trash = entry("Trash")
        db.passwordEntryDao().update(trash.copy(isDeleted = true, deletedAt = stamp))
        val archived = entry("Archive")
        db.passwordEntryDao().update(archived.copy(isArchived = true, archivedAt = stamp))
        var fields = listOf(CustomFieldDraft(title = "Secret extra", value = "  extra\n中文\u0000  ", isProtected = true))
        val bodies = mapOf(
            PasswordContentBlocks.Kind.API_KEY to mapOf("key" to "synthetic-api-key", "url" to "https://example.invalid/api"),
            PasswordContentBlocks.Kind.API_TOKEN to mapOf("provider" to "github", "api_base" to "https://api.github.com/", "token" to "synthetic-token-1234567890"),
            PasswordContentBlocks.Kind.SSH_KEY to mapOf("algorithm" to ssh.algorithm, "publicKeyOpenSsh" to ssh.publicKeyOpenSsh, "privateKeyOpenSsh" to ssh.privateKeyOpenSsh),
            PasswordContentBlocks.Kind.GPG_KEY to mapOf("publicKey" to gpg.publicKey, "privateKey" to gpg.privateKey, "fingerprint" to gpg.fingerprint),
            PasswordContentBlocks.Kind.QR_CODE to mapOf("content" to "https://example.invalid/二维码?q=1234567890"))
        bodies.forEach { (kind, body) -> fields = PasswordContentBlocks.put(fields,
            PasswordContentBlocks.create(kind).edited("Embedded $kind", body + ("notes" to "  embedded\nnotes  "))) }
        wallet.filter { it.itemType in setOf(ItemType.BANK_CARD, ItemType.BILLING_ADDRESS, ItemType.NOTE) ||
            it.title == prefix + "Document PASSPORT" }.forEach { original ->
            val plain = original.copy(itemData = security.decryptData(original.itemData))
            fields = EmbeddedWalletContent.put(fields, EmbeddedWalletContent.create(plain))
        }
        fields = EntryContentFields.withOrder(fields, listOf("NOTE", "PAYMENT", "ADDRESS", "AUTHENTICATOR") +
            PasswordContentBlocks.read(fields).map { it.token }.reversed())
        fields.forEachIndexed { i, field -> db.customFieldDao().insert(field.toCustomField(main.id, i)) }
        ApiKeyEntryFields.encode("https://example.invalid/api").forEach { db.customFieldDao().insert(it.toCustomField(api.id, 0)) }
        GpgEntryFields.encode(gpg).forEachIndexed { i, field -> db.customFieldDao().insert(field.toCustomField(gpgEntry.id, i)) }
        db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = main.id, password = security.encryptDataLegacyCompat("historical secret"), lastUsedAt = stamp))
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = PasskeyEntry(credentialId = "cmVjb3ZlcnktZml4dHVyZQ", rpId = "example.invalid", rpName = prefix + "Passkey",
            userId = "YWxpY2U", userName = "alice", userDisplayName = "Synthetic Alice",
            publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
            privateKeyAlias = PasskeyPrivateKeyStore.protectForStorage(context, "cmVjb3ZlcnktZml4dHVyZQ", "example.invalid", "YWxpY2U",
                Base64.getEncoder().encodeToString(pair.private.encoded)), boundPasswordId = main.id, notes = "Synthetic passkey")
        db.passkeyDao().insert(key)
        File(root, "public-key.der").writeBytes(pair.public.encoded)
        val faceBytes = java.io.ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(240, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff673ab7.toInt()); compress(Bitmap.CompressFormat.JPEG, 90, out); recycle() }
        }.toByteArray()
        val owners = listOf(AttachmentOwner.password(main.id)) + wallet.filter { it.itemType in setOf(ItemType.BANK_CARD, ItemType.DOCUMENT, ItemType.BILLING_ADDRESS, ItemType.NOTE) }.map { AttachmentOwner.secureItem(it.id) }
        owners.forEach { owner ->
            attachments.addInlineAttachment(AttachmentFacade.InlineUploadRequest(owner, AttachmentSource.LOCAL, "fixture.bin", "application/octet-stream", binary, true))
            attachments.addInlineAttachment(AttachmentFacade.InlineUploadRequest(owner, AttachmentSource.LOCAL, faceName, "image/jpeg", faceBytes, true))
        }
        File(root, "face.jpg").writeBytes(faceBytes)
        val file = mdbx.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val databaseId = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = prefix + "Native token vault", filePath = file.absolutePath,
            storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptDataLegacyCompat(password),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
        val payload = ApiTokenPayload.update(ApiTokenPayload.empty().toString(), "token", "synthetic-native-token-123456")
        val token = mdbx.saveNativeApiToken(databaseId, null, "upgrade-native-token", payload,
            uploads = listOf(NativeApiTokenUpload("fixture.bin", "application/octet-stream", binary.size.toLong(), NativeApiTokenAssets.digest(binary)) { binary.inputStream() }))
        File(root, "native.json").writeText(JSONObject().put("databaseId", databaseId).put("entryId", token.entryId).put("payload", payload).toString())
        marker.writeText(snapshot().toString(), Charsets.UTF_8)
        verify()
    }

    suspend fun verify() {
        authorizeFixture()
        check(marker.exists())
        assertTrue("Original master password must unlock", security.unlockVaultWithPassword(password))
        assertEquals("Every database column must survive", marker.readText(), snapshot().toString())
        verifyPayloads()
    }

    /** Reusable after restore has legitimately remapped local row IDs. */
    suspend fun verifyPayloads() {
        authorizeFixture()
        assertTrue(security.unlockVaultWithPassword(password))
        val passkey = db.passkeyDao().getAllPasskeysSync().single { it.rpName == prefix + "Passkey" }
        val privateKey = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(PasskeyPrivateKeyStore.resolve(context, passkey.privateKeyAlias)))
        val challenge = "sign after actual APK upgrade".toByteArray()
        val signature = PasskeyPrivateKeySupport.createSignature(privateKey.privateKey, privateKey.publicKeyAlgorithm).apply { update(challenge) }.sign()
        val publicKey = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(File(root, "public-key.der").readBytes()))
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(challenge) }.verify(signature))
        val passwords = db.passwordEntryDao().getAllPasswordEntriesSync()
        val items = db.secureItemDao().getAllItems().first()
        val owners = passwords.map { AttachmentOwner.password(it.id) } + items.map { AttachmentOwner.secureItem(it.id) }
        var count = 0
        owners.forEach { owner -> attachments.list(owner).forEach { attachment ->
            assertArrayEquals(if (attachment.mimeType == "application/octet-stream") binary else File(root, "face.jpg").readBytes(),
                attachments.readAttachmentBytes(attachment.id, 1_000_000)); count++
        } }
        assertEquals("Every card face and file must decrypt", if (File(root, "extras-ready").exists()) 26 else 18, count)
        val native = JSONObject(File(root, "native.json").readText())
        val token = mdbx.readNativeApiToken(native.getLong("databaseId"), native.getString("entryId"))
        assertEquals(Json.parseToJsonElement(native.getString("payload")), Json.parseToJsonElement(token.payload))
        assertArrayEquals(binary, mdbx.readNativeApiTokenAttachment(token, token.attachments.single().id))
        val main = passwords.single { it.title == prefix + "Everything" }
        val fields = db.customFieldDao().getFieldsByEntryId(main.id).first().map { CustomFieldDraft(title = it.title, value = it.value, isProtected = it.isProtected) }
        assertEquals(PasswordContentBlocks.Kind.entries.toSet(), PasswordContentBlocks.read(fields).map { checkNotNull(it.block).kind }.toSet())
        assertEquals(4, fields.count { EmbeddedWalletContent.isMetadata(it.title) && EmbeddedWalletContent.read(it.value) is EmbeddedWalletContent.ReadResult.Available })
        if (File(root, "extras-ready").exists()) {
            assertEquals(File(root, "history.json").readText(), PasswordHistoryManager(context).exportHistoryJson())
            assertEquals("fixture@example.invalid", CommonAccountPreferences(context).defaultEmail.first())
            assertArrayEquals(binary, takagi.ru.monica.utils.KeePassKeyFileStore(context).readInternal(File(root, "keyfile-path").readText()))
            val steam = takagi.ru.monica.steam.data.SteamDatabase.getDatabase(context).steamAccountDao().getAccounts().single()
            assertEquals("c3ludGhldGljLXN0ZWFtLXNlY3JldA==", security.decryptData(steam.sharedSecret))
            fields.filter { EmbeddedWalletContent.isMetadata(it.title) }.forEach { field ->
                val snapshot = (EmbeddedWalletContent.read(field.value) as EmbeddedWalletContent.ReadResult.Available).snapshot
                assertEquals(2, snapshot.assets.size)
                snapshot.assets.forEach { asset ->
                    val attachment = attachments.list(AttachmentOwner.password(main.id)).single { it.fileName == asset.name }
                    val bytes = attachments.readAttachmentBytes(attachment.id, 1_000_000)
                    assertEquals(asset.sha256, NativeApiTokenAssets.digest(bytes))
                }
            }
        }
    }

    suspend fun completeExtras() {
        authorizeFixture()
        check(runCatching { Class.forName("takagi.ru.monica.security.LocalVaultRecovery") }.isFailure) { "Extras must be created on the old APK" }
        check(!File(root, "extras-ready").exists())
        verify()
        PasswordHistoryManager(context).addHistory("  generated history\n🔑  ", domain = "example.invalid", username = "alice")
        File(root, "history.json").writeText(PasswordHistoryManager(context).exportHistoryJson())
        CommonAccountPreferences(context).setDefaultEmail("fixture@example.invalid")
        val keyfile = takagi.ru.monica.utils.KeePassKeyFileStore(context).copyBytes(binary, "fixture.keyx")
        File(root, "keyfile-path").writeText(keyfile.relativePath)
        val steamDb = takagi.ru.monica.steam.data.SteamDatabase.getDatabase(context)
        fun encrypted(s: String) = security.encryptDataLegacyCompat(s)
        steamDb.steamAccountDao().insert(takagi.ru.monica.steam.data.SteamAccountEntity(
            steamId = encrypted("76561190000000001"), accountName = encrypted("synthetic-steam"), displayName = encrypted("Fixture"),
            deviceId = encrypted("android:fixture"), sharedSecret = encrypted("c3ludGhldGljLXN0ZWFtLXNlY3JldA=="),
            identitySecret = encrypted("c3ludGhldGljLWlkZW50aXR5"), revocationCode = encrypted("R00000"),
            tokenGid = encrypted("gid"), accessToken = encrypted("synthetic-access"), refreshToken = encrypted("synthetic-refresh"),
            steamLoginSecure = encrypted("synthetic-login"), rawSteamGuardJson = encrypted("{\"account_name\":\"synthetic-steam\",\"shared_secret\":\"c3ludGhldGljLXN0ZWFtLXNlY3JldA==\"}")))
        val main = db.passwordEntryDao().getAllPasswordEntriesSync().single { it.title == prefix + "Everything" }
        val fields = db.customFieldDao().getFieldsByEntryId(main.id).first()
        val items = db.secureItemDao().getAllItems().first()
        val copier = takagi.ru.monica.attachments.EmbeddedWalletCopyService(context)
        fields.filter { EmbeddedWalletContent.isMetadata(it.title) }.forEach { field ->
            val previous = (EmbeddedWalletContent.read(field.value) as EmbeddedWalletContent.ReadResult.Available).snapshot
            val source = items.single { it.title == previous.title }
            copier.prepare(source.copy(itemData = security.decryptData(source.itemData))).use { copy ->
                copy.snapshot.assets.forEach { asset ->
                    attachments.addStreamAttachment(AttachmentFacade.StreamUploadRequest(
                        AttachmentOwner.password(main.id), AttachmentSource.LOCAL, asset.name, asset.mimeType, asset.size,
                        { copy.assets.open(asset.name) }, true))
                }
                db.customFieldDao().insert(field.copy(value = copy.snapshot.encode()))
            }
        }
        File(root, "extras-ready").writeText("created on the pre-recovery APK")
        marker.writeText(snapshot().toString())
        verify()
    }

    fun snapshot(): JSONObject {
        val result = JSONObject()
        listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments", "local_mdbx_databases").forEach { table ->
            val rows = JSONArray()
            db.openHelper.writableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    c.columnNames.sorted().forEach { name ->
                        val i = c.getColumnIndexOrThrow(name)
                        val value: Any = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(c.getBlob(i))
                            else -> c.getString(i).let { if (it.startsWith("MDK|") || it.startsWith("C2|") || it.startsWith("V2|")) security.decryptData(it) else it }
                        }
                        row.put(name, value)
                    }
                    rows.put(row)
                }
            }
            result.put(table, rows)
        }
        return result
    }
}
