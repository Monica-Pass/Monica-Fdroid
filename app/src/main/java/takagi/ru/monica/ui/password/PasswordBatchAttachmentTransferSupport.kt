package takagi.ru.monica.ui

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.syncForUserVisibleRequest
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.viewmodel.PasswordViewModel

internal data class PreparedPasswordBatchAttachments(
    val countsByPasswordId: Map<Long, Int>
) {
    val totalAttachmentCount: Int
        get() = countsByPasswordId.values.sum()

    fun countFor(passwordId: Long): Int = countsByPasswordId[passwordId] ?: 0
}

internal class PasswordBatchAttachmentTransferException(
    val failedPasswordCount: Int,
    cause: Throwable? = null
) : IllegalStateException("$failedPasswordCount password attachment transfers failed", cause)

internal suspend fun preparePasswordBatchAttachments(
    context: Context,
    entries: List<PasswordEntry>,
    bitwardenVaults: List<BitwardenVault>,
    viewModel: PasswordViewModel,
    skipPasswordIds: Set<Long> = emptySet()
): PreparedPasswordBatchAttachments {
    val facade = AttachmentContainer.facade(context)
    val vaultsById = bitwardenVaults.associateBy(BitwardenVault::id)
    val counts = buildMap {
        entries.forEach { entry ->
            if (entry.id in skipPasswordIds) return@forEach
            val bitwardenContext = entry.bitwardenVaultId
                ?.let(vaultsById::get)
                ?.let { vault ->
                    viewModel.getAttachmentBitwardenContext(vault, entry.bitwardenCipherId)
                }
            val keepassContext = if (
                entry.keepassDatabaseId != null &&
                !entry.keepassEntryUuid.isNullOrBlank()
            ) {
                AttachmentFacade.KeePassContext(
                    databaseId = entry.keepassDatabaseId,
                    entryUuid = entry.keepassEntryUuid
                )
            } else {
                null
            }
            put(
                entry.id,
                facade.ensureAttachmentsReadyForTransfer(
                    passwordId = entry.id,
                    bitwardenContext = bitwardenContext,
                    keepassContext = keepassContext
                )
            )
        }
    }
    return PreparedPasswordBatchAttachments(counts)
}

internal suspend fun completePasswordBatchBitwardenAttachments(
    context: Context,
    idPairs: List<Pair<Long, Long>>,
    sourceEntries: List<PasswordEntry>,
    targetVault: BitwardenVault,
    preparedAttachments: PreparedPasswordBatchAttachments,
    isMove: Boolean,
    viewModel: PasswordViewModel,
    bitwardenRepository: BitwardenRepository
): Int {
    val pairsWithAttachments = idPairs.filter { (sourceId, _) ->
        preparedAttachments.countFor(sourceId) > 0
    }
    val sourcesById = sourceEntries.associateBy(PasswordEntry::id)
    val pairsRequiringTargetCipher = idPairs.filter { (sourceId, targetId) ->
        val sourceEntry = sourcesById[sourceId]
        !isMove ||
            sourceId != targetId ||
            sourceEntry?.bitwardenVaultId != targetVault.id ||
            sourceEntry?.bitwardenCipherId.isNullOrBlank()
    }
    if (pairsWithAttachments.isEmpty() && pairsRequiringTargetCipher.isEmpty()) {
        return 0
    }

    val syncResult = bitwardenRepository.syncForUserVisibleRequest(
        vaultId = targetVault.id,
        requestIdPrefix = "password-batch-attachment-transfer"
    )
    when (syncResult) {
        is BitwardenRepository.SyncResult.Success -> {
            if (syncResult.uploadFailedCount > 0) {
                if (!isMove) {
                    idPairs.map { it.second }.distinct().forEach { targetId ->
                        runCatching { viewModel.rollbackPasswordTransferTargetAwait(targetId) }
                    }
                }
                throw PasswordBatchAttachmentTransferException(idPairs.size)
            }
        }
        is BitwardenRepository.SyncResult.Error -> {
            if (!isMove) {
                idPairs.map { it.second }.distinct().forEach { targetId ->
                    runCatching { viewModel.rollbackPasswordTransferTargetAwait(targetId) }
                }
            }
            throw PasswordBatchAttachmentTransferException(
                failedPasswordCount = idPairs.size,
                cause = IllegalStateException(syncResult.message)
            )
        }
        is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
            if (!isMove) {
                idPairs.map { it.second }.distinct().forEach { targetId ->
                    runCatching { viewModel.rollbackPasswordTransferTargetAwait(targetId) }
                }
            }
            throw PasswordBatchAttachmentTransferException(
                failedPasswordCount = idPairs.size,
                cause = IllegalStateException(syncResult.reason)
            )
        }
    }

    var failedPasswordCount = 0
    val successfulPairs = idPairs.toMutableList()
    val targetEntriesById = mutableMapOf<Long, PasswordEntry>()
    pairsRequiringTargetCipher.forEach { pair ->
        val targetEntry = awaitBitwardenTargetPassword(viewModel, pair.second)
        if (targetEntry == null) {
            if (!isMove) {
                runCatching { viewModel.rollbackPasswordTransferTargetAwait(pair.second) }
            }
            successfulPairs.remove(pair)
            failedPasswordCount += 1
        } else {
            targetEntriesById[pair.second] = targetEntry
        }
    }

    val facade = AttachmentContainer.facade(context)
    var copiedAttachmentCount = 0
    pairsWithAttachments.forEach { pair ->
        if (pair !in successfulPairs) return@forEach
        val (sourceId, targetId) = pair
        try {
            val sourceEntry = sourcesById[sourceId]
                ?: error("Attachment source password is missing")
            val targetEntry = targetEntriesById[targetId]
                ?: awaitBitwardenTargetPassword(viewModel, targetId)
                ?: error("Bitwarden target password is missing")
            targetEntriesById[targetId] = targetEntry
            val targetCipherId = targetEntry.bitwardenCipherId?.takeIf(String::isNotBlank)
                ?: error("Bitwarden target cipher is not ready")
            val alreadyOwnedByTargetCipher = isMove &&
                sourceId == targetId &&
                sourceEntry.bitwardenVaultId == targetVault.id &&
                sourceEntry.bitwardenCipherId == targetCipherId
            if (alreadyOwnedByTargetCipher) {
                copiedAttachmentCount += preparedAttachments.countFor(sourceId)
                return@forEach
            }
            val targetContext = viewModel.getAttachmentBitwardenContext(targetVault, targetCipherId)
                ?: error("Bitwarden attachment session is unavailable")
            val count = facade.copyAttachmentsToBitwardenEntry(
                sourcePasswordId = sourceId,
                targetPasswordId = targetId,
                targetContext = targetContext
            )
            check(count == preparedAttachments.countFor(sourceId)) {
                "Bitwarden attachment copy is incomplete"
            }
            copiedAttachmentCount += count

            if (isMove && sourceEntry.mdbxDatabaseId != null) {
                facade.removeAttachmentsFromMdbxSource(sourceEntry)
            }
        } catch (error: Throwable) {
            if (!isMove) runCatching { viewModel.rollbackPasswordTransferTargetAwait(targetId) }
            successfulPairs.remove(pair)
            failedPasswordCount += 1
        }
    }

    if (isMove) {
        val sourceVaultsNeedingDelete = linkedSetOf<Long>()
        successfulPairs.forEach { (sourceId, targetId) ->
            val sourceEntry = sourcesById[sourceId] ?: return@forEach
            val sourceVaultId = sourceEntry.bitwardenVaultId ?: return@forEach
            val sourceCipherId = sourceEntry.bitwardenCipherId?.takeIf(String::isNotBlank)
                ?: return@forEach
            val targetCipherId = targetEntriesById[targetId]?.bitwardenCipherId
                ?: awaitBitwardenTargetPassword(viewModel, targetId)?.bitwardenCipherId
            if (sourceVaultId == targetVault.id && sourceCipherId == targetCipherId) return@forEach
            val queued = bitwardenRepository.queueCipherDelete(
                vaultId = sourceVaultId,
                cipherId = sourceCipherId,
                entryId = sourceId
            )
            if (queued.isSuccess) {
                sourceVaultsNeedingDelete += sourceVaultId
            } else {
                failedPasswordCount += 1
            }
        }
        sourceVaultsNeedingDelete.forEach(bitwardenRepository::requestLocalMutationSync)
    }
    if (failedPasswordCount > 0) {
        throw PasswordBatchAttachmentTransferException(failedPasswordCount)
    }
    return copiedAttachmentCount
}

private suspend fun awaitBitwardenTargetPassword(
    viewModel: PasswordViewModel,
    targetId: Long
): PasswordEntry? = withTimeoutOrNull(15_000L) {
    var targetEntry: PasswordEntry? = null
    while (targetEntry == null) {
        targetEntry = viewModel.getPasswordEntryById(targetId)
            ?.takeIf { !it.bitwardenCipherId.isNullOrBlank() }
        if (targetEntry == null) delay(150L)
    }
    targetEntry
}

internal suspend fun completePasswordBatchLocalOrKeePassAttachmentCopies(
    context: Context,
    idPairs: List<Pair<Long, Long>>,
    target: UnifiedMoveCategoryTarget,
    preparedAttachments: PreparedPasswordBatchAttachments,
    viewModel: PasswordViewModel
): Int {
    if (idPairs.isEmpty()) return 0
    val facade = AttachmentContainer.facade(context)
    var copiedAttachmentCount = 0
    var failedPasswordCount = 0
    idPairs.forEach { (sourceId, targetId) ->
        val expectedCount = preparedAttachments.countFor(sourceId)
        if (expectedCount <= 0) return@forEach
        val copiedCount = try {
            val count = when (target) {
                UnifiedMoveCategoryTarget.Uncategorized,
                is UnifiedMoveCategoryTarget.MonicaCategory,
                is UnifiedMoveCategoryTarget.MdbxDatabaseTarget,
                is UnifiedMoveCategoryTarget.MdbxFolderTarget ->
                    facade.cloneAttachmentsToNewParent(sourceId, targetId)

                is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
                is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                    val targetEntry = viewModel.getPasswordEntryById(targetId)
                        ?: error("KeePass target password is missing")
                    val targetDatabaseId = targetEntry.keepassDatabaseId
                        ?: error("KeePass target database is missing")
                    val targetEntryUuid = targetEntry.keepassEntryUuid?.takeIf(String::isNotBlank)
                        ?: error("KeePass target entry is not ready")
                    facade.copyAttachmentsToKeePassEntry(
                        sourcePasswordId = sourceId,
                        targetPasswordId = targetId,
                        targetDatabaseId = targetDatabaseId,
                        targetEntryUuid = targetEntryUuid
                    )
                }

                is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
                is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> 0
            }
            check(count == expectedCount) {
                "Attachment copy is incomplete for password $sourceId"
            }
            count
        } catch (error: Throwable) {
            runCatching { viewModel.rollbackPasswordTransferTargetAwait(targetId) }
            failedPasswordCount += 1
            return@forEach
        }
        copiedAttachmentCount += copiedCount
    }
    if (failedPasswordCount > 0) {
        throw PasswordBatchAttachmentTransferException(failedPasswordCount)
    }
    return copiedAttachmentCount
}
