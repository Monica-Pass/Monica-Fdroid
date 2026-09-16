package takagi.ru.monica.transfer

import android.content.Context
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.R
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.KeePassDatabaseCredentialEditor
import takagi.ru.monica.keepass.KeePassNativeManagement
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.data.SteamExternalMaFileContract
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.utils.*
import java.io.File
import java.util.UUID

/** Uses Monica's regular KDBX encoders, including its interoperable Passkey fields. */
internal class DatabaseKdbxExporter(context: Context) {
    private val context = context.applicationContext
    private val strings = AppLocaleStringResolver(context)
    private val security = SecurityManager(this.context)

    suspend fun prepare(source: ImportDestination, password: String, progress: TransferProgressReporter): Pair<File, Int> {
        require(password.isNotEmpty())
        progress.report(TransferProgress(TransferPhase.READING))
        val db = PasswordDatabase.getDatabase(context)
        val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        var count = 0
        val nativeId = source.keepassId
        val database = if (nativeId != null) {
            val session = KeePassWorkspaceRepository(service).openNativeSession(nativeId).getOrThrow()
            count = session.entryNodes.size
            // Re-encode an immutable copy; live credentials, history and attachments are untouched.
            KeePassDatabaseCredentialEditor.replace(session.database, credentials)
        } else {
            val snapshot = DatabaseExportSnapshotLoader(context).load(source, BackupPreferences())
            val entries = mutableListOf<Pair<String?, Entry>>()
            val owners = mutableMapOf<AttachmentOwner, UUID>()
            val passwords = snapshot.passwords.filterNot { it.isDeleted }
            val items = snapshot.secureItems.filterNot { it.isDeleted }
            val total = (passwords.size + items.size + snapshot.passkeys.size +
                snapshot.steamAccounts.size + snapshot.nativeTokens.size).toLong()
            fun added() { progress.report(TransferProgress(TransferPhase.PREPARING, (++count).toLong(), total)) }
            for (entry in passwords) {
                currentCoroutineContext().ensureActive()
                val fields = snapshot.fields[entry.id].orEmpty().mapIndexed { index, field ->
                    KeePassCustomFieldData(field.title, field.value, field.isProtected, index)
                }.toMutableList()
                if (entry.authenticatorKey.isNotBlank()) fields += KeePassCustomFieldData("TOTP Seed",
                    security.decryptDataIfMonicaCiphertext(entry.authenticatorKey), true)
                val exported = service.buildEntry(entry, security.decryptDataIfMonicaCiphertext(entry.password), fields)
                entries += snapshot.categories[entry.categoryId] to exported
                owners[AttachmentOwner.password(entry.id)] = exported.uuid
                added()
            }
            for (item in items) {
                currentCoroutineContext().ensureActive()
                val exported = service.buildSecureItemEntry(item)
                entries += snapshot.categories[item.categoryId] to exported
                owners[AttachmentOwner.secureItem(item.id)] = exported.uuid
                added()
            }
            for (key in snapshot.passkeys) {
                check(PasskeyPrivateKeyStore.hasUsablePrivateKey(context, key.privateKeyAlias)) {
                    strings.get(R.string.passkey_backup_private_key_missing)
                }
                entries += snapshot.categories[key.categoryId] to service.buildPasskeyEntry(key)
                added()
            }
            for (token in snapshot.nativeTokens) {
                val payload = ApiTokenPayload.decode(token.payload)
                check(payload != null) { strings.get(R.string.transfer_unsupported_token) }
                entries += null to Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                    "Title" to EntryValue.Plain(token.title),
                    "UserName" to EntryValue.Plain(ApiTokenPayload.text(payload, "provider")),
                    "URL" to EntryValue.Plain(ApiTokenPayload.text(payload, "api_base")),
                    "Password" to EntryValue.Encrypted(EncryptedValue.fromString(ApiTokenPayload.text(payload, "token"))),
                    "Monica.ApiToken" to EntryValue.Encrypted(EncryptedValue.fromString(token.payload)),
                    "Monica.ApiTokenMetadata" to EntryValue.Encrypted(EncryptedValue.fromString(token.metadata)),
                ))
                added()
            }
            val steamAttachments = mutableListOf<Triple<UUID, String, ByteArray>>()
            for (account in snapshot.steamAccounts) {
                val uuid = UUID.randomUUID()
                entries += null to Entry(uuid = uuid, fields = EntryFields.of(
                    "Title" to EntryValue.Plain(account.displayName.ifBlank { account.accountName }),
                    "UserName" to EntryValue.Plain(account.accountName),
                    "URL" to EntryValue.Plain("https://steamcommunity.com"),
                    SteamExternalMaFileContract.MARKER_FIELD to EntryValue.Plain(SteamExternalMaFileContract.MARKER_VALUE),
                ))
                steamAttachments += Triple(uuid, SteamExternalMaFileContract.attachmentFileName(account),
                    SteamMaFileBackupCodec.encode(account).toByteArray(Charsets.UTF_8))
                added()
            }
            var output: KeePassDatabase = KeePassDatabase.Ver4x.create("Monica",
                Meta(generator = "Monica", name = "Monica Export"), credentials).modifyParentGroup {
                copy(entries = entries.filter { it.first == null }.map { it.second },
                    groups = entries.filter { it.first != null }.groupBy { it.first!! }.map { (name, values) ->
                        Group(uuid = UUID.randomUUID(), name = name, entries = values.map { it.second })
                    })
            }
            for ((index, attachment) in snapshot.attachments.withIndex()) {
                val uuid = owners[attachment.owner] ?: continue
                progress.report(TransferProgress(TransferPhase.ATTACHMENTS, index.toLong(), snapshot.attachments.size.toLong()))
                val bytes = attachment.read()
                try { output = KeePassNativeManagement.addAttachment(output, uuid, attachment.fileName, bytes) }
                finally { bytes.fill(0) }
            }
            for ((uuid, name, bytes) in steamAttachments) {
                try { output = KeePassNativeManagement.addAttachment(output, uuid, name, bytes) }
                finally { bytes.fill(0) }
            }
            output
        }
        currentCoroutineContext().ensureActive()
        progress.report(TransferProgress(TransferPhase.ENCRYPTING))
        val file = File.createTempFile("monica-database-", ".kdbx", context.cacheDir)
        try {
            file.outputStream().buffered().use { database.encode(it, cipherProviders = KeePassCodecSupport.cipherProviders) }
            return file to count
        } catch (error: Throwable) { file.delete(); throw error }
    }
}
