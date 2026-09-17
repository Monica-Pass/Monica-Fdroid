package takagi.ru.monica.data.dedup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.attachments.LegacyImageAttachmentSupport
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.StringResolver
import kotlin.coroutines.coroutineContext

internal interface DedupMergeWriter {
    suspend fun writePassword(resolved: DedupResolvedPassword)
    suspend fun writeSecureItem(resolved: DedupResolvedSecureItem)
    suspend fun writePasskey(resolved: DedupResolvedPasskey)
}

internal class RepositoryDedupMergeWriter(
    private val passwordRepository: PasswordRepository,
    private val secureItemRepository: SecureItemRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val passkeyRepository: PasskeyRepository,
    private val attachmentSupport: DedupAttachmentSupport? = null
) : DedupMergeWriter {
    override suspend fun writePassword(resolved: DedupResolvedPassword) {
        var insertedId: Long? = null
        try {
            val newId = passwordRepository.insertPasswordEntry(resolved.entry)
            insertedId = newId
            val fields = resolved.customFields.map { field ->
                field.copy(id = 0, entryId = newId)
            }
            if (fields.isNotEmpty()) {
                customFieldRepository.insertFields(fields)
                if (resolved.entry.mdbxDatabaseId != null) {
                    // Insertion mirrors the entry before its new field IDs exist. Flush the
                    // complete entry so reopening the MDBX file retains those fields too.
                    val persisted = checkNotNull(passwordRepository.getPasswordEntryById(newId))
                    passwordRepository.updatePasswordEntry(persisted)
                }
            }
            attachmentSupport?.copy(resolved.attachments, AttachmentOwner.password(newId))
        } catch (throwable: Exception) {
            val rollbackFailure = insertedId?.let { id ->
                runCatching {
                    withContext(NonCancellable) {
                        attachmentSupport?.rollback(AttachmentOwner.password(id))
                        passwordRepository.deletePasswordEntryById(id)
                    }
                }.exceptionOrNull()
            }
            rollbackFailure?.let(throwable::addSuppressed)
            throw throwable
        }
    }

    override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) {
        var insertedId: Long? = null
        var images: LegacyImageAttachmentSupport.Copy? = null
        try {
            images = attachmentSupport?.images?.copy(resolved.item.imagePaths)
            val item = images?.let { resolved.item.copy(imagePaths = it.imagePaths) } ?: resolved.item
            val id = secureItemRepository.insertItem(item)
            insertedId = id
            attachmentSupport?.copy(resolved.attachments, AttachmentOwner.secureItem(id))
            attachmentSupport?.persistImages(item.copy(id = id))
        } catch (error: Exception) {
            val failure = runCatching {
                withContext(NonCancellable) {
                    insertedId?.let { id ->
                        attachmentSupport?.rollback(AttachmentOwner.secureItem(id))
                        secureItemRepository.deleteItemById(id)
                    }
                    images?.let { attachmentSupport?.images?.rollback(it) }
                }
            }.exceptionOrNull()
            failure?.let(error::addSuppressed)
            throw error
        }
    }

    override suspend fun writePasskey(resolved: DedupResolvedPasskey) {
        require(resolved.writable && resolved.entry.id == 0L)
        passkeyRepository.savePasskey(resolved.entry)
    }
}

internal class DedupMergeExecutor(
    private val writer: DedupMergeWriter,
    private val strings: StringResolver
) {
    suspend fun execute(
        passwords: List<DedupResolvedPassword>,
        secureItems: List<DedupResolvedSecureItem>,
        skippedExistingPasswords: Int,
        skippedExistingSecureItems: Int,
        skippedUnsupportedPasskeys: Int,
        targetLabel: String,
        passkeys: List<DedupResolvedPasskey> = emptyList(),
        skippedExistingPasskeys: Int = 0,
        onProgress: (DedupMergeExecutionProgress) -> Unit = {}
    ): DedupMergeExecutionResult {
        val totalItems = passwords.size + secureItems.size + passkeys.size
        var completedItems = 0
        var insertedPasswords = 0
        var insertedSecureItems = 0
        var insertedPasskeys = 0
        val failures = mutableListOf<DedupMergeFailure>()

        passwords.forEach { resolved ->
            coroutineContext.ensureActive()
            val label = resolved.entry.title.ifBlank { resolved.entry.username.ifBlank { strings.get(R.string.dedup_merge_untitled_password) } }
            try {
                writer.writePassword(resolved)
                insertedPasswords++
            } catch (throwable: Exception) {
                if (throwable is CancellationException) throw throwable
                failures += DedupMergeFailure(
                    kind = DedupMergeItemKind.PASSWORD,
                    label = label,
                    reason = failureReason(throwable)
                )
            } finally {
                completedItems++
                onProgress(DedupMergeExecutionProgress(completedItems, totalItems, label))
            }
        }

        secureItems.forEach { resolved ->
            coroutineContext.ensureActive()
            val label = resolved.item.title.ifBlank { resolved.item.itemType.dedupLabel(strings) }
            try {
                writer.writeSecureItem(resolved)
                insertedSecureItems++
            } catch (throwable: Exception) {
                if (throwable is CancellationException) throw throwable
                failures += DedupMergeFailure(
                    kind = DedupMergeItemKind.SECURE_ITEM,
                    label = label,
                    reason = failureReason(throwable)
                )
            } finally {
                completedItems++
                onProgress(DedupMergeExecutionProgress(completedItems, totalItems, label))
            }
        }

        passkeys.forEach { resolved ->
            coroutineContext.ensureActive()
            val label = resolved.entry.displayTitle()
            try {
                writer.writePasskey(resolved)
                insertedPasskeys++
            } catch (throwable: Exception) {
                if (throwable is CancellationException) throw throwable
                failures += DedupMergeFailure(DedupMergeItemKind.PASSKEY, label, failureReason(throwable))
            } finally {
                completedItems++
                onProgress(DedupMergeExecutionProgress(completedItems, totalItems, label))
            }
        }

        return DedupMergeExecutionResult(
            insertedPasswords = insertedPasswords,
            insertedSecureItems = insertedSecureItems,
            insertedPasskeys = insertedPasskeys,
            skippedExistingPasswords = skippedExistingPasswords,
            skippedExistingSecureItems = skippedExistingSecureItems,
            skippedExistingPasskeys = skippedExistingPasskeys,
            skippedUnsupportedPasskeys = skippedUnsupportedPasskeys,
            failedPasswords = failures.count { it.kind == DedupMergeItemKind.PASSWORD },
            failedSecureItems = failures.count { it.kind == DedupMergeItemKind.SECURE_ITEM },
            failedPasskeys = failures.count { it.kind == DedupMergeItemKind.PASSKEY },
            targetLabel = targetLabel,
            failures = failures
        )
    }

    private fun failureReason(throwable: Throwable): String {
        val primary = readableReason(throwable)
        val rollback = throwable.suppressed.firstOrNull() ?: return primary
        val rollbackText = readableReason(rollback)
        return strings.get(R.string.dedup_merge_rollback_failed, primary, rollbackText)
    }

    private fun readableReason(error: Throwable): String = when (error) {
        AttachmentError.CryptoError -> strings.get(R.string.attachment_error_crypto)
        AttachmentError.IoError -> strings.get(R.string.attachment_error_io)
        AttachmentError.Offline -> strings.get(R.string.attachment_error_offline)
        AttachmentError.BitwardenLocked -> strings.get(R.string.attachment_error_bitwarden_locked)
        AttachmentError.KdbxLocked -> strings.get(R.string.attachment_error_kdbx_locked)
        AttachmentError.InvalidRemoteData -> strings.get(R.string.attachment_error_remote_data)
        AttachmentError.KdbxCapacityExceeded -> strings.get(R.string.attachment_error_kdbx_capacity)
        is AttachmentError.NetworkError -> strings.get(R.string.attachment_error_network, error.httpStatus?.toString() ?: "?")
        is AttachmentError.TooLarge -> strings.get(R.string.attachment_error_too_large, "${error.limitBytes / (1024 * 1024)} MiB")
        else -> error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    }
}
