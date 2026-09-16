package takagi.ru.monica.credentialexchange

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.ItemCounts
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.RestoreReport
import takagi.ru.monica.data.encodeLinkedAppPackageNames
import takagi.ru.monica.data.encodeLinkedAppNames
import takagi.ru.monica.utils.CustomFieldBackupEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.BackupContent
import takagi.ru.monica.utils.BackupRestoreApplier
import takagi.ru.monica.utils.RestoreResult
import java.util.Date
import takagi.ru.monica.transfer.*

data class ImportResultSummary(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val queuedToBitwarden: Boolean,
)

class TargetedImportCoordinator(
    private val context: Context,
    private val passwords: PasswordRepository,
    private val secureItems: SecureItemRepository,
    private val defaultProgress: TransferProgressReporter = TransferProgressReporter.None,
) {
    suspend fun apply(
        content: BackupContent,
        destination: ImportDestination,
        skippedBeforeWrite: Int = 0,
        parseFailures: Int = 0,
        progress: TransferProgressReporter = defaultProgress,
    ): ImportResultSummary = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val writer = ImportDestinationWriter(context, destination, passwords, secureItems, progress)
            val counts = ItemCounts(passwords = content.passwords.size, passkeys = content.passkeys.size)
            val stats = BackupRestoreApplier.applyRestoreResult(
                context = context,
                restoreResult = RestoreResult(content, RestoreReport(true, counts, counts, emptyList(), emptyList())),
                passwordRepository = passwords,
                secureItemRepository = secureItems,
                localOnlyDedup = destination.kind == ImportDestinationKind.LOCAL,
                logTag = "TargetedImport",
                destinationWriter = writer,
                progress = progress,
            )
            ImportResultSummary(
                imported = stats.totalImported(),
                skipped = skippedBeforeWrite + stats.passwordSkipped + stats.secureItemSkipped + stats.passkeySkipped + stats.nativeTokenSkipped,
                failed = parseFailures + stats.passwordFailed + stats.secureItemFailed + stats.passkeyFailed + stats.steamAccountFailed + stats.nativeTokenFailed + writer.supplementalFailures,
                queuedToBitwarden = writer.isBitwarden && stats.totalImported() > 0,
            )
        }
    }

    suspend fun importExchange(decoded: CxfCredentialCodec.Decoded, destination: ImportDestination,
        progress: TransferProgressReporter = defaultProgress): ImportResultSummary {
        val security = takagi.ru.monica.security.SecurityManager(context)
        val content = exchangeContent(decoded, context)
        // CXF passwords are plaintext, even when they happen to start with a Monica cipher prefix.
        // Wrap them before entering the legacy backup reader, which also accepts encrypted values.
        return apply(content.copy(passwords = content.passwords.map {
            it.copy(password = security.encryptData(it.password))
        }), destination, decoded.skippedCount, progress = progress)
    }

    companion object {
        private val importMutex = Mutex()

        internal fun exchangeContent(decoded: CxfCredentialCodec.Decoded, context: Context? = null): BackupContent {
            val passwords = mutableListOf<PasswordEntry>()
            val passkeys = mutableListOf<PasskeyEntry>()
            val fields = mutableMapOf<Long, List<CustomFieldBackupEntry>>()
            var nextId = 1L
            for (item in decoded.items) {
                var boundPasswordId: Long? = null
                val apps = context?.let { CxfAndroidAppScope.bindings(it, item.androidApps) }.orEmpty()
                for (login in item.logins) {
                    val id = nextId++
                    if (boundPasswordId == null) boundPasswordId = id
                    passwords += PasswordEntry(
                        id = id, title = item.title,
                        website = takagi.ru.monica.utils.PasswordWebsiteCodec.encode(item.urls),
                        username = login.username, password = login.password, notes = item.notes,
                        createdAt = Date(item.createdAt), updatedAt = Date(item.modifiedAt),
                        isFavorite = item.favorite,
                        appPackageName = encodeLinkedAppPackageNames(apps), appName = encodeLinkedAppNames(apps),
                    )
                    if (item.androidApps.isNotEmpty()) fields[id] = listOf(CustomFieldBackupEntry(
                        title = CxfAndroidAppScope.FIELD_NAME, value = item.androidApps.toString(), isProtected = false))
                }
                for (key in item.passkeys) {
                    passkeys += PasskeyEntry(
                        credentialId = key.credentialId, rpId = key.rpId,
                        rpName = item.title.ifBlank { key.rpId },
                        userId = key.userHandle, userName = key.username, userDisplayName = key.userDisplayName,
                        publicKeyAlgorithm = key.algorithm, publicKey = key.publicKey,
                        privateKeyAlias = key.key, createdAt = item.createdAt, lastUsedAt = 0,
                        // CXF explicitly specifies zero counters; existing credentials are never rewritten.
                        signCount = 0, isBackedUp = true, notes = item.notes,
                        boundPasswordId = boundPasswordId, passkeyMode = PasskeyEntry.MODE_BW_COMPAT,
                    )
                }
            }
            return BackupContent(passwords, emptyList(), passkeys, customFieldsMap = fields)
        }
    }
}
