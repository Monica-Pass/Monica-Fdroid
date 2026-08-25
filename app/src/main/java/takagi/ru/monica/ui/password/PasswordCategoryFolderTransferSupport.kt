package takagi.ru.monica.ui

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.buildLocalCategoryPath
import takagi.ru.monica.utils.getLocalCategoryParentPath
import takagi.ru.monica.utils.isLocalCategoryDescendantPath
import takagi.ru.monica.utils.normalizeLocalCategoryPath
import takagi.ru.monica.utils.planLocalCategoryMove
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

internal data class PasswordCategoryFolderTransferNode(
    val category: Category,
    val relativeSegments: List<String>,
    val depth: Int,
)

internal data class PasswordCategoryFolderTransferContents(
    val node: PasswordCategoryFolderTransferNode,
    val passwords: List<PasswordEntry>,
    val aggregateSelection: PasswordBatchAggregateSelection,
    val auxiliaryItems: List<SecureItem>,
) {
    val itemCount: Int
        get() = passwords.size + aggregateSelection.secureItems.size +
            aggregateSelection.passkeys.size + auxiliaryItems.size
}

internal data class PasswordCategoryFolderTransferResult(
    val successCount: Int,
    val failedCount: Int,
    val folderCount: Int,
    val sourceFoldersRemoved: Boolean,
    val failureMessages: List<String> = emptyList(),
) {
    val isCompleteSuccess: Boolean
        get() = failedCount == 0
}

internal class PasswordCategoryFolderTransferBlockedException(message: String) :
    IllegalStateException(message)

internal fun buildPasswordCategoryFolderTransferNodes(
    categories: List<Category>,
    sourceCategory: Category,
): List<PasswordCategoryFolderTransferNode> {
    val sourcePath = normalizeLocalCategoryPath(sourceCategory.name)
    require(sourcePath.isNotBlank()) { "Invalid source category path" }
    val sourceSegments = sourcePath.split('/').filter(String::isNotBlank)
    val sourceLeaf = sourceSegments.last()

    return categories
        .asSequence()
        .filter { category ->
            isLocalCategoryDescendantPath(
                parentPath = sourcePath,
                candidatePath = normalizeLocalCategoryPath(category.name),
            )
        }
        .mapNotNull { category ->
            val categoryPath = normalizeLocalCategoryPath(category.name)
            val categorySegments = categoryPath.split('/').filter(String::isNotBlank)
            if (categorySegments.size < sourceSegments.size) return@mapNotNull null
            PasswordCategoryFolderTransferNode(
                category = category,
                relativeSegments = listOf(sourceLeaf) + categorySegments.drop(sourceSegments.size),
                depth = categorySegments.size - sourceSegments.size,
            )
        }
        .sortedWith(compareBy<PasswordCategoryFolderTransferNode> { it.depth }
            .thenBy { normalizeLocalCategoryPath(it.category.name).lowercase() })
        .toList()
}

internal fun buildPasswordCategoryFolderTransferContents(
    nodes: List<PasswordCategoryFolderTransferNode>,
    passwords: List<PasswordEntry>,
    secureItems: List<SecureItem>,
    passkeys: List<PasskeyEntry>,
    securityManager: SecurityManager,
): List<PasswordCategoryFolderTransferContents> {
    val localPasswordsByCategory = passwords
        .asSequence()
        .filter { entry ->
            !entry.isDeleted && !entry.isArchived && entry.isLocalOnlyEntry() && entry.categoryId != null
        }
        .groupBy { it.categoryId!! }

    val activeLocalSecureItems = secureItems.filter { item ->
        !item.isDeleted &&
            item.keepassDatabaseId == null &&
            item.bitwardenVaultId == null &&
            item.mdbxDatabaseId == null
    }
    val ordinarySecureItemsByCategory = activeLocalSecureItems
        .asSequence()
        .filter {
            it.itemType != ItemType.TOTP &&
                it.itemType != ItemType.PAYMENT_ACCOUNT &&
                it.itemType != ItemType.BILLING_ADDRESS
        }
        .filter { it.categoryId != null }
        .groupBy { it.categoryId!! }
    val auxiliaryItemsByCategory = activeLocalSecureItems
        .asSequence()
        .filter {
            (it.itemType == ItemType.PAYMENT_ACCOUNT || it.itemType == ItemType.BILLING_ADDRESS) &&
                it.categoryId != null
        }
        .groupBy { it.categoryId!! }

    val standaloneTotpsByCategory = activeLocalSecureItems
        .asSequence()
        .filter { it.itemType == ItemType.TOTP }
        .mapNotNull { item ->
            val data = TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext,
            )
            if (data?.boundPasswordId != null) return@mapNotNull null
            val categoryId = data?.categoryId ?: item.categoryId ?: return@mapNotNull null
            categoryId to item
        }
        .groupBy({ it.first }, { it.second })

    val standalonePasskeysByCategory = passkeys
        .asSequence()
        .filter { passkey ->
            passkey.boundPasswordId == null &&
                passkey.syncStatus != "REFERENCE" &&
                passkey.keepassDatabaseId == null &&
                passkey.bitwardenVaultId == null &&
                passkey.mdbxDatabaseId == null &&
                passkey.categoryId != null
        }
        .groupBy { it.categoryId!! }

    return nodes.map { node ->
        val categoryId = node.category.id
        val ordinaryItems = ordinarySecureItemsByCategory[categoryId].orEmpty()
        PasswordCategoryFolderTransferContents(
            node = node,
            passwords = localPasswordsByCategory[categoryId].orEmpty(),
            aggregateSelection = PasswordBatchAggregateSelection(
                bankCards = ordinaryItems.filter { it.itemType == ItemType.BANK_CARD },
                documents = ordinaryItems.filter { it.itemType == ItemType.DOCUMENT },
                notes = ordinaryItems.filter { it.itemType == ItemType.NOTE },
                totpItems = standaloneTotpsByCategory[categoryId].orEmpty(),
                passkeys = standalonePasskeysByCategory[categoryId].orEmpty(),
            ),
            auxiliaryItems = auxiliaryItemsByCategory[categoryId].orEmpty(),
        )
    }
}

internal fun validatePasswordCategoryFolderLocalDestination(
    categories: List<Category>,
    sourceCategory: Category,
    target: UnifiedMoveCategoryTarget,
    action: UnifiedMoveAction,
    invalidDestinationMessage: String = "A folder cannot be moved or copied into itself or one of its subfolders",
    noChangeMessage: String = "The folder is already at the selected location",
) {
    val sourcePath = normalizeLocalCategoryPath(sourceCategory.name)
    val targetParentPath = (target as? UnifiedMoveCategoryTarget.MonicaCategory)
        ?.categoryId
        ?.let { categoryId -> categories.firstOrNull { it.id == categoryId } }
        ?.name
        ?.let(::normalizeLocalCategoryPath)

    if (!targetParentPath.isNullOrBlank() &&
        isLocalCategoryDescendantPath(sourcePath, targetParentPath)
    ) {
        throw PasswordCategoryFolderTransferBlockedException(
            invalidDestinationMessage,
        )
    }

    val destinationPath = buildLocalCategoryPath(
        parentPath = targetParentPath,
        name = normalizeLocalCategoryPath(sourceCategory.name).substringAfterLast('/'),
    )
    if (destinationPath.equals(sourcePath, ignoreCase = true)) {
        throw PasswordCategoryFolderTransferBlockedException(
            noChangeMessage,
        )
    }
}

internal suspend fun executePasswordCategoryFolderTransfer(
    context: Context,
    action: UnifiedMoveAction,
    sourceCategory: Category,
    selectedTarget: UnifiedMoveCategoryTarget,
    categories: List<Category>,
    passwords: List<PasswordEntry>,
    secureItems: List<SecureItem>,
    passkeys: List<PasskeyEntry>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    localKeePassViewModel: LocalKeePassViewModel,
    securityManager: SecurityManager,
    passwordViewModel: PasswordViewModel,
    aggregateViewModels: PasswordBatchMoveViewModels,
    bitwardenRepository: BitwardenRepository,
    onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
): PasswordCategoryFolderTransferResult {
    val nodes = buildPasswordCategoryFolderTransferNodes(categories, sourceCategory)
    if (nodes.isEmpty()) {
        throw PasswordCategoryFolderTransferBlockedException(
            context.getString(R.string.category_folder_transfer_source_missing),
        )
    }
    val contents = buildPasswordCategoryFolderTransferContents(
        nodes = nodes,
        passwords = passwords,
        secureItems = secureItems,
        passkeys = passkeys,
        securityManager = securityManager,
    )
    val totalItemCount = contents.sumOf(PasswordCategoryFolderTransferContents::itemCount)
    val totalWork = nodes.size + totalItemCount
    onProgress(0, totalWork)

    val isLocalTarget = selectedTarget == UnifiedMoveCategoryTarget.Uncategorized ||
        selectedTarget is UnifiedMoveCategoryTarget.MonicaCategory
    if (isLocalTarget) {
        validatePasswordCategoryFolderLocalDestination(
            categories = categories,
            sourceCategory = sourceCategory,
            target = selectedTarget,
            action = action,
            invalidDestinationMessage = context.getString(
                R.string.category_folder_transfer_invalid_destination,
            ),
            noChangeMessage = context.getString(R.string.category_folder_transfer_no_change),
        )
        if (action == UnifiedMoveAction.MOVE) {
            val targetParent = (selectedTarget as? UnifiedMoveCategoryTarget.MonicaCategory)
                ?.categoryId
                ?.let { categoryId -> categories.firstOrNull { it.id == categoryId } }
            val plan = planLocalCategoryMove(categories, sourceCategory, targetParent)
            if (plan.updatedCategories.isEmpty()) {
                throw PasswordCategoryFolderTransferBlockedException(
                    context.getString(R.string.category_folder_transfer_no_change),
                )
            }
            passwordViewModel.updateCategoriesAwait(plan.updatedCategories)
            onProgress(totalWork, totalWork)
            return PasswordCategoryFolderTransferResult(
                successCount = totalItemCount,
                failedCount = 0,
                folderCount = nodes.size,
                sourceFoldersRemoved = true,
            )
        }
    }

    if (action == UnifiedMoveAction.MOVE && !isLocalTarget) {
        val inactiveReferenceCount = passwordViewModel.getInactiveCategoryReferencesAwait(
            nodes.mapTo(mutableSetOf()) { it.category.id },
        )
        if (inactiveReferenceCount > 0) {
            throw PasswordCategoryFolderTransferBlockedException(
                context.getString(
                    R.string.category_folder_transfer_inactive_items_blocked,
                    inactiveReferenceCount,
                ),
            )
        }
    }

    val hasStandalonePasskeys = contents.any { it.aggregateSelection.passkeys.isNotEmpty() }
    if (action == UnifiedMoveAction.COPY && hasStandalonePasskeys) {
        throw PasswordCategoryFolderTransferBlockedException(
            context.getString(R.string.category_folder_transfer_passkey_copy_blocked),
        )
    }
    val hasUnsupportedIdentityItems = contents.any {
        it.auxiliaryItems.isNotEmpty()
    }
    val targetSupportsIdentityItems = isLocalTarget ||
        selectedTarget is UnifiedMoveCategoryTarget.MdbxDatabaseTarget ||
        selectedTarget is UnifiedMoveCategoryTarget.MdbxFolderTarget
    if (hasUnsupportedIdentityItems && !targetSupportsIdentityItems) {
        throw PasswordCategoryFolderTransferBlockedException(
            context.getString(R.string.category_folder_transfer_identity_items_unsupported),
        )
    }

    val allSelectedPasswords = contents.flatMap(PasswordCategoryFolderTransferContents::passwords)
    val targetBitwardenVaultId = when (selectedTarget) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> selectedTarget.vaultId
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> selectedTarget.vaultId
        else -> null
    }
    val targetBitwardenVault = targetBitwardenVaultId?.let { vaultId ->
        bitwardenVaults.firstOrNull { it.id == vaultId }
            ?: throw PasswordCategoryFolderTransferBlockedException(
                context.getString(R.string.category_folder_transfer_target_missing),
            )
    }
    val targetBitwardenIsPremium = targetBitwardenVaultId?.let { vaultId ->
        BitwardenVaultPremiumStore.isPremium(context, vaultId)
    } ?: false
    val preparedAttachments = preparePasswordBatchAttachments(
        context = context,
        entries = allSelectedPasswords,
        bitwardenVaults = bitwardenVaults,
        viewModel = passwordViewModel,
    )
    if (targetBitwardenVaultId != null && !targetBitwardenIsPremium &&
        preparedAttachments.totalAttachmentCount > 0
    ) {
        throw PasswordCategoryFolderTransferBlockedException(
            context.getString(R.string.category_folder_transfer_bitwarden_attachments_blocked),
        )
    }

    var handledFolders = 0
    val bitwardenFolderCache = mutableMapOf<Long, MutableList<BitwardenFolder>>()
    val localCategoryIdByPath = categories
        .associate { normalizeLocalCategoryPath(it.name).lowercase() to it.id }
        .toMutableMap()

    suspend fun resolveTarget(node: PasswordCategoryFolderTransferNode): UnifiedMoveCategoryTarget {
        return when (selectedTarget) {
            UnifiedMoveCategoryTarget.Uncategorized,
            is UnifiedMoveCategoryTarget.MonicaCategory -> {
                val targetParentPath = (selectedTarget as? UnifiedMoveCategoryTarget.MonicaCategory)
                    ?.categoryId
                    ?.let { categoryId -> categories.firstOrNull { it.id == categoryId } }
                    ?.name
                    ?.let(::normalizeLocalCategoryPath)
                val path = buildLocalCategoryPath(targetParentPath, node.relativeSegments.joinToString("/"))
                val normalizedKey = path.lowercase()
                val categoryId = localCategoryIdByPath[normalizedKey]
                    ?: passwordViewModel.ensureLocalCategoryAwait(path).also { createdId ->
                        localCategoryIdByPath[normalizedKey] = createdId
                    }
                UnifiedMoveCategoryTarget.MonicaCategory(categoryId)
            }

            is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                val vaultId = when (selectedTarget) {
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> selectedTarget.vaultId
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> selectedTarget.vaultId
                    else -> error("Unexpected Bitwarden target")
                }
                val folders = bitwardenFolderCache.getOrPut(vaultId) {
                    bitwardenRepository.getFolders(vaultId).toMutableList()
                }
                val baseName = (selectedTarget as? UnifiedMoveCategoryTarget.BitwardenFolderTarget)
                    ?.folderId
                    ?.let { folderId -> folders.firstOrNull { it.bitwardenFolderId == folderId }?.name }
                    ?.takeIf(String::isNotBlank)
                val folderName = (listOfNotNull(baseName) + node.relativeSegments).joinToString("/")
                val folder = folders.firstOrNull { it.name.equals(folderName, ignoreCase = true) }
                    ?: bitwardenRepository.createFolder(vaultId, folderName).getOrThrow().also(folders::add)
                UnifiedMoveCategoryTarget.BitwardenFolderTarget(vaultId, folder.bitwardenFolderId)
            }

            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                val databaseId = when (selectedTarget) {
                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> selectedTarget.databaseId
                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> selectedTarget.databaseId
                    else -> error("Unexpected KeePass target")
                }
                val parentPath = (selectedTarget as? UnifiedMoveCategoryTarget.KeePassGroupTarget)?.groupPath
                val path = localKeePassViewModel.ensureGroupPathAwait(
                    databaseId = databaseId,
                    parentPath = parentPath,
                    segments = node.relativeSegments,
                ).getOrThrow()
                UnifiedMoveCategoryTarget.KeePassGroupTarget(databaseId, path)
            }

            is UnifiedMoveCategoryTarget.MdbxDatabaseTarget,
            is UnifiedMoveCategoryTarget.MdbxFolderTarget -> {
                val databaseId = when (selectedTarget) {
                    is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> selectedTarget.databaseId
                    is UnifiedMoveCategoryTarget.MdbxFolderTarget -> selectedTarget.databaseId
                    else -> error("Unexpected MDBX target")
                }
                val parentFolderId = (selectedTarget as? UnifiedMoveCategoryTarget.MdbxFolderTarget)
                    ?.folderId ?: "root"
                val folderId = passwordViewModel.ensureMdbxFolderPathAwait(
                    databaseId = databaseId,
                    parentFolderId = parentFolderId,
                    segments = node.relativeSegments,
                ).getOrThrow()
                UnifiedMoveCategoryTarget.MdbxFolderTarget(databaseId, folderId)
            }
        }
    }

    // Resolve the full destination tree before moving any item. A folder-creation failure therefore
    // cannot leave the source half moved.
    val targetByCategoryId = linkedMapOf<Long, UnifiedMoveCategoryTarget>()
    nodes.forEach { node ->
        targetByCategoryId[node.category.id] = resolveTarget(node)
        handledFolders += 1
        onProgress(handledFolders, totalWork)
    }

    var successCount = 0
    var failedCount = 0
    var processedItems = 0
    val failureMessages = mutableListOf<String>()

    for (content in contents) {
        val target = targetByCategoryId.getValue(content.node.category.id)
        try {
            val result = executeMixedPasswordBatchMove(
                context = context,
                action = action,
                target = target,
                selectedEntries = content.passwords,
                aggregateSelection = content.aggregateSelection,
                categories = categories,
                keepassDatabases = keepassDatabases,
                localKeePassViewModel = localKeePassViewModel,
                securityManager = securityManager,
                viewModel = passwordViewModel,
                aggregateViewModels = aggregateViewModels,
                bitwardenRepository = bitwardenRepository,
                onProgress = { processed, _ ->
                    onProgress(
                        handledFolders + processedItems + processed,
                        totalWork,
                    )
                },
            )
            var nodeSuccess = result.successCount
            var nodeFailed = result.failedCount
            failureMessages += result.keepassFailureMessages

            if (content.auxiliaryItems.isNotEmpty()) {
                val paymentResult = when (target) {
                    UnifiedMoveCategoryTarget.Uncategorized ->
                        passwordViewModel.copyLocalOnlySecureItemsToMonicaCategoryBatch(
                            content.auxiliaryItems,
                            categoryId = null,
                        )

                    is UnifiedMoveCategoryTarget.MonicaCategory ->
                        passwordViewModel.copyLocalOnlySecureItemsToMonicaCategoryBatch(
                            content.auxiliaryItems,
                            categoryId = target.categoryId,
                        )

                    is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> if (action == UnifiedMoveAction.COPY) {
                        passwordViewModel.copySecureItemsToMdbxBatch(
                            content.auxiliaryItems,
                            target.databaseId,
                            folderId = null,
                        )
                    } else {
                        passwordViewModel.moveSecureItemsToMdbxBatch(
                            content.auxiliaryItems,
                            target.databaseId,
                            folderId = null,
                        )
                    }

                    is UnifiedMoveCategoryTarget.MdbxFolderTarget -> if (action == UnifiedMoveAction.COPY) {
                        passwordViewModel.copySecureItemsToMdbxBatch(
                            content.auxiliaryItems,
                            target.databaseId,
                            target.folderId,
                        )
                    } else {
                        passwordViewModel.moveSecureItemsToMdbxBatch(
                            content.auxiliaryItems,
                            target.databaseId,
                            target.folderId,
                        )
                    }

                    else -> error("Unsupported payment-account target")
                }
                nodeSuccess += paymentResult.successCount
                nodeFailed += paymentResult.failedCount
            }

            if (content.passwords.isNotEmpty()) {
                val attachmentFailureCount = try {
                    when {
                        action == UnifiedMoveAction.COPY && targetBitwardenVault != null -> {
                            completePasswordBatchBitwardenAttachments(
                                context = context,
                                idPairs = result.copiedPasswordIdPairs,
                                sourceEntries = content.passwords,
                                targetVault = targetBitwardenVault,
                                preparedAttachments = preparedAttachments,
                                isMove = false,
                                viewModel = passwordViewModel,
                                bitwardenRepository = bitwardenRepository,
                            )
                            0
                        }

                        action == UnifiedMoveAction.COPY -> {
                            completePasswordBatchLocalOrKeePassAttachmentCopies(
                                context = context,
                                idPairs = result.copiedPasswordIdPairs,
                                target = target,
                                preparedAttachments = preparedAttachments,
                                viewModel = passwordViewModel,
                            )
                            0
                        }

                        action == UnifiedMoveAction.MOVE && targetBitwardenVault != null -> {
                            completePasswordBatchBitwardenAttachments(
                                context = context,
                                idPairs = content.passwords.map { it.id to it.id },
                                sourceEntries = content.passwords,
                                targetVault = targetBitwardenVault,
                                preparedAttachments = preparedAttachments,
                                isMove = true,
                                viewModel = passwordViewModel,
                                bitwardenRepository = bitwardenRepository,
                            )
                            0
                        }

                        else -> 0
                    }
                } catch (error: PasswordBatchAttachmentTransferException) {
                    error.failedPasswordCount
                }
                if (attachmentFailureCount > 0) {
                    nodeSuccess = (nodeSuccess - attachmentFailureCount).coerceAtLeast(0)
                    nodeFailed += attachmentFailureCount
                    failureMessages += context.getString(
                        R.string.category_folder_transfer_attachment_failed,
                        attachmentFailureCount,
                    )
                }
            }

            successCount += nodeSuccess
            failedCount += nodeFailed
        } catch (error: Exception) {
            failedCount += content.itemCount
            failureMessages += (error.message ?: error::class.java.simpleName)
        }
        processedItems += content.itemCount
        onProgress(handledFolders + processedItems, totalWork)
    }

    val allItemsSucceeded = failedCount == 0
    if (action == UnifiedMoveAction.MOVE && allItemsSucceeded) {
        passwordViewModel.deleteCategoriesAwait(
            nodes.sortedByDescending(PasswordCategoryFolderTransferNode::depth).map { it.category },
        )
    }
    onProgress(totalWork, totalWork)
    return PasswordCategoryFolderTransferResult(
        successCount = successCount,
        failedCount = failedCount,
        folderCount = nodes.size,
        sourceFoldersRemoved = action == UnifiedMoveAction.MOVE && allItemsSucceeded,
        failureMessages = failureMessages.filter(String::isNotBlank).distinct(),
    )
}
