package takagi.ru.monica.credentialexchange

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.backup.PortableAttachmentBackup
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper

@RunWith(AndroidJUnit4::class)
class CredentialImportAttachmentsInstrumentedTest {
    private val passwordId = 700001L
    private val noteId = 700002L
    private val payload = ByteArray(4097) { (it * 31).toByte() }

    private suspend fun TransferFixture.bundle(): File {
        val key = CxfPasskeyMaterial.decode(pair.private.encoded)!!
        val passkey = JSONObject().put("credentialId", credentialId).put("rpId", rpId).put("rpName", "$prefix-zip")
            .put("userId", userHandle).put("userName", rawUsername).put("userDisplayName", "Alice")
            .put("publicKeyAlgorithm", -7).put("publicKey", key.cosePublicKey)
            .put("privateKeyAlias", Base64.getEncoder().encodeToString(pair.private.encoded))
            .put("boundPasswordId", passwordId).put("signCount", 0).put("passkeyMode", "BW_COMPAT")
        val password = JSONObject().put("id", passwordId).put("title", "$prefix-zip")
            .put("username", rawUsername).put("password", rawPassword).put("website", website)
        val (noteData, noteContent) = NoteContentCodec.encode("Synthetic note")
        val note = JSONObject().put("id", noteId).put("title", "$prefix-note")
            .put("itemData", noteData).put("notes", noteContent)
        val totp = JSONObject().put("id", 700003L).put("title", "$prefix-totp").put("itemData", JSONObject()
            .put("secret", "JBSWY3DPEHPK3PXP").put("issuer", "Fixture").put("accountName", "alice")
            .put("boundPasswordId", passwordId).toString())
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val entries = listOf(
            PortableAttachmentBackup.Entry(parentPasswordId = passwordId, fileName = "password.bin", mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(), sha256Hex = hash, payloadPath = "attachments_portable/password.bin", createdAt = 1, updatedAt = 1),
            PortableAttachmentBackup.Entry(parentSecureItemId = noteId, fileName = "note.bin", mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(), sha256Hex = hash, payloadPath = "attachments_portable/note.bin", createdAt = 1, updatedAt = 1),
        )
        val plain = File(root, "attachments.zip")
        ZipOutputStream(plain.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("passwords/password.json", password.toString().toByteArray())
            put("notes/note.json", note.toString().toByteArray())
            put("totp/totp.json", totp.toString().toByteArray())
            put("passkeys/key.json", passkey.toString().toByteArray())
            put(PortableAttachmentBackup.MANIFEST_ENTRY, PortableAttachmentBackup.encodeManifest(entries).toByteArray())
            entries.forEach { put(it.payloadPath, payload) }
        }
        return File(root, "attachments.enc.zip").also {
            EncryptionHelper.encryptFile(plain, it, "backup fixture", AppLocaleStringResolver(context)).getOrThrow()
        }
    }

    @Test fun zipRestoresPasskeyOtpBindingsAndAttachmentsIntoEachDestination() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val file = bundle()
            val facade = AttachmentContainer.facade(context)
            for (target in listOf(ImportDestination.Local, keepass(), mdbx(), bitwarden())) {
                assertEquals(target.kind.name, 4, model.importZipBackup(Uri.fromFile(file), "backup fixture", target).getOrThrow())
                assertEquals(0, model.lastImportSummary.value!!.failed)
                val password = importedPasswords(target).single()
                val key = importedKeys(target).single()
                assertOriginalKey(key)
                assertEquals(password.id, key.boundPasswordId)
                val items = db.secureItemDao().getAllItems().first().filter { target.contains(it) && it.title.startsWith(prefix) }
                val note = items.single { it.itemType == ItemType.NOTE }
                val otp = TotpDataResolver.parseStoredItemData(items.single { it.itemType == ItemType.TOTP }.itemData,
                    decryptIfNeeded = security::decryptDataIfMonicaCiphertext)!!
                assertEquals(password.id, otp.boundPasswordId)
                assertEquals("JBSWY3DPEHPK3PXP", otp.secret)
                val passwordAttachment = facade.list(AttachmentOwner.password(password.id)).single { it.fileName == "password.bin" }
                val noteAttachment = facade.list(AttachmentOwner.secureItem(note.id)).single { it.fileName == "note.bin" }
                val passwordContext = target.keepassId?.let { AttachmentFacade.KeePassContext(it, password.keepassEntryUuid!!) }
                val noteContext = target.keepassId?.let { AttachmentFacade.KeePassContext(it, note.keepassEntryUuid!!) }
                assertArrayEquals(payload, facade.readAttachmentBytes(passwordAttachment.id, 8192, keepassContext = passwordContext))
                assertArrayEquals(payload, facade.readAttachmentBytes(noteAttachment.id, 8192, keepassContext = noteContext))
                target.mdbxId?.let { assertEquals(2, mdbx.readStoredAttachments(it).size) }
                assertEquals("Retry must not duplicate bound OTPs or attachments in ${target.kind}",
                    0, model.importZipBackup(Uri.fromFile(file), "backup fixture", target).getOrThrow())
                assertEquals(1, facade.list(AttachmentOwner.secureItem(note.id)).count { it.fileName == "note.bin" })
            }
        } } finally { fixture.close() }
    }

    @Test fun bitwardenAttachmentFailureRetainsLocalBytesAndRetryUploadsExactEncryptedPayload() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val target = bitwarden()
            val file = bundle()
            assertEquals(4, model.importZipBackup(Uri.fromFile(file), "backup fixture", target).getOrThrow())
            val facade = AttachmentContainer.facade(context)
            val owner = AttachmentOwner.password(importedPasswords(target).single().id)
            val before = facade.list(owner).single()
            BitwardenVaultPremiumStore.setPremium(context, target.databaseId, true)
            val vault = db.bitwardenVaultDao().getVaultById(target.databaseId)!!
            val sync = BitwardenSyncService(context)
            val upload = sync.uploadLocalEntries(vault, accessToken, vaultKey) as UploadResult.Success
            assertEquals("All parent records must reach the server before testing attachments", 0, upload.failed)
            assertEquals(4, upload.uploaded)
            remote.rejectAttachments = true
            sync.uploadModifiedEntries(vault, accessToken, vaultKey)
            assertEquals(AttachmentSource.LOCAL, facade.list(owner).single().sourceEnum)
            assertArrayEquals(payload, facade.readAttachmentBytes(before.id, 8192))
            assertTrue(context.getSharedPreferences("credential_import_attachments_v1", 0)
                .getStringSet("vault:${target.databaseId}", emptySet())!!.isNotEmpty())
            remote.rejectAttachments = false
            sync.uploadModifiedEntries(vault, accessToken, vaultKey)
            val after = facade.list(owner).single()
            assertEquals(AttachmentSource.BITWARDEN, after.sourceEnum)
            val note = db.secureItemDao().getAllItems().first().single {
                target.contains(it) && it.title == "$prefix-note" && it.itemType == ItemType.NOTE
            }
            val noteAfter = facade.list(AttachmentOwner.secureItem(note.id)).single()
            assertEquals("The note attachment must also reach the server", AttachmentSource.BITWARDEN,
                noteAfter.sourceEnum)
            val remoteNote = remote.created.single { it.getString("id") == note.bitwardenCipherId }
            assertEquals(2, remoteNote.getInt("type"))
            assertEquals("Synthetic note", String(BitwardenCrypto.decrypt(remoteNote.getString("notes"), vaultKey), Charsets.UTF_8))
            assertTrue("All imported attachment acknowledgements must be durable",
                context.getSharedPreferences("credential_import_attachments_v1", 0)
                    .getStringSet("vault:${target.databaseId}", emptySet())!!.isEmpty())
            for (attachment in listOf(after, noteAfter)) {
                assertArrayEquals(payload, facade.readAttachmentBytes(attachment.id, 8192))
                assertNotNull(attachment.bitwardenAttachmentId)
                val remoteBytes = remote.attachmentUploads.getValue(attachment.bitwardenAttachmentId!!)
                val metadata = remote.attachmentCreates.single { it.getString("id") == attachment.bitwardenAttachmentId }
                val expectedParentId = if (attachment.id == after.id) importedPasswords(target).single().bitwardenCipherId else note.bitwardenCipherId
                assertEquals(expectedParentId, metadata.getString("cipherId"))
                val parent = remote.created.single { it.getString("id") == expectedParentId }
                val itemKeyBytes = parent.optString("key").takeIf { it.isNotBlank() && it != "null" }?.let { BitwardenCrypto.decrypt(it, vaultKey) }
                val wrappingKey = itemKeyBytes?.let { BitwardenCrypto.SymmetricCryptoKey(it.copyOfRange(0, 32), it.copyOfRange(32, 64)) } ?: vaultKey
                val keyBytes = BitwardenCrypto.decrypt(metadata.getString("key"), wrappingKey)
                try {
                    assertEquals(2, remoteBytes[0].toInt())
                    val iv = remoteBytes.copyOfRange(1, 17)
                    val ciphertext = remoteBytes.copyOfRange(49, remoteBytes.size)
                    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyBytes.copyOfRange(32, 64), "HmacSHA256")); update(iv) }.doFinal(ciphertext)
                    assertArrayEquals(mac, remoteBytes.copyOfRange(17, 49))
                    val decrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
                    }.doFinal(ciphertext)
                    assertArrayEquals(payload, decrypted)
                } finally { keyBytes.fill(0); if (itemKeyBytes != null) { wrappingKey.clear(); itemKeyBytes.fill(0) } }
            }
            val uploadedCount = remote.attachmentCreates.size
            sync.uploadModifiedEntries(vault, accessToken, vaultKey)
            assertEquals(uploadedCount, remote.attachmentCreates.size)
        } } finally { fixture.close() }
    }
}
