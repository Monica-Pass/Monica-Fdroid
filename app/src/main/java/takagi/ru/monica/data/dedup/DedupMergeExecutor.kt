package takagi.ru.monica.data.dedup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import kotlin.coroutines.coroutineContext

internal interface DedupMergeWriter {
    suspend fun writePassword(resolved: DedupResolvedPassword)
    suspend fun writeSecureItem(resolved: DedupResolvedSecureItem)
}

internal class RepositoryDedupMergeWriter(
    private val passwordRepository: PasswordRepository,
    private val secureItemRepository: SecureItemRepository,
    private val customFieldRepository: CustomFieldRepository
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
            }
        } catch (throwable: Exception) {
            val rollbackFailure = insertedId?.let { id ->
                runCatching {
                    withContext(NonCancellable) {
                        passwordRepository.deletePasswordEntryById(id)
                    }
                }.exceptionOrNull()
            }
            rollbackFailure?.let(throwable::addSuppressed)
            throw throwable
        }
    }

    override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) {
        secureItemRepository.insertItem(resolved.item)
    }
}

internal class DedupMergeExecutor(
    private val writer: DedupMergeWriter
) {
    suspend fun execute(
        passwords: List<DedupResolvedPassword>,
        secureItems: List<DedupResolvedSecureItem>,
        skippedExistingPasswords: Int,
        skippedExistingSecureItems: Int,
        skippedUnsupportedPasskeys: Int,
        targetLabel: String,
        onProgress: (DedupMergeExecutionProgress) -> Unit = {}
    ): DedupMergeExecutionResult {
        val totalItems = passwords.size + secureItems.size
        var completedItems = 0
        var insertedPasswords = 0
        var insertedSecureItems = 0
        val failures = mutableListOf<DedupMergeFailure>()

        passwords.forEach { resolved ->
            coroutineContext.ensureActive()
            val label = resolved.entry.title.ifBlank { resolved.entry.username.ifBlank { "未命名密码" } }
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
            val label = resolved.item.title.ifBlank { resolved.item.itemType.name }
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

        return DedupMergeExecutionResult(
            insertedPasswords = insertedPasswords,
            insertedSecureItems = insertedSecureItems,
            skippedExistingPasswords = skippedExistingPasswords,
            skippedExistingSecureItems = skippedExistingSecureItems,
            skippedUnsupportedPasskeys = skippedUnsupportedPasskeys,
            failedPasswords = failures.count { it.kind == DedupMergeItemKind.PASSWORD },
            failedSecureItems = failures.count { it.kind == DedupMergeItemKind.SECURE_ITEM },
            targetLabel = targetLabel,
            failures = failures
        )
    }

    private fun failureReason(throwable: Throwable): String {
        val primary = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName
        val rollback = throwable.suppressed.firstOrNull() ?: return primary
        val rollbackText = rollback.message?.takeIf { it.isNotBlank() } ?: rollback::class.java.simpleName
        return "$primary；回滚失败：$rollbackText"
    }
}
