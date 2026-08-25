package takagi.ru.monica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.utils.decodeKeePassPathSegments
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

internal data class PasswordBatchPreserveCategoriesPrompt(
    val classifiedItemCount: Int,
    val proceed: (Boolean) -> Unit
)

internal fun shouldOfferPasswordBatchCategoryPreservation(
    entries: List<PasswordEntry>,
    target: UnifiedMoveCategoryTarget
): Boolean {
    if (entries.isEmpty() || target is UnifiedMoveCategoryTarget.MonicaCategory &&
        target.categoryId == takagi.ru.monica.ui.components.UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
    ) {
        return false
    }
    val targetKey = target.passwordBatchStorageKey() ?: return false
    return entries.any { entry ->
        entry.hasPasswordBatchSourceCategory() && entry.passwordBatchStorageKey() != targetKey
    }
}

internal fun PasswordEntry.hasPasswordBatchSourceCategory(): Boolean =
    categoryId != null ||
        !keepassGroupPath.isNullOrBlank() ||
        !bitwardenFolderId.isNullOrBlank() ||
        (!mdbxFolderId.isNullOrBlank() && mdbxFolderId != "root")

internal fun PasswordEntry.passwordBatchStorageKey(): String = when {
    mdbxDatabaseId != null -> "mdbx:$mdbxDatabaseId"
    keepassDatabaseId != null -> "keepass:$keepassDatabaseId"
    bitwardenVaultId != null -> "bitwarden:$bitwardenVaultId"
    else -> "monica"
}

internal fun UnifiedMoveCategoryTarget.passwordBatchStorageKey(): String? = when (this) {
    UnifiedMoveCategoryTarget.Uncategorized,
    is UnifiedMoveCategoryTarget.MonicaCategory -> "monica"
    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> "bitwarden:$vaultId"
    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> "bitwarden:$vaultId"
    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> "keepass:$databaseId"
    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> "keepass:$databaseId"
    is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> "mdbx:$databaseId"
    is UnifiedMoveCategoryTarget.MdbxFolderTarget -> "mdbx:$databaseId"
}

internal fun passwordBatchTargetForEntry(
    entry: PasswordEntry,
    selectedTarget: UnifiedMoveCategoryTarget,
    targetOverrides: Map<Long, UnifiedMoveCategoryTarget>
): UnifiedMoveCategoryTarget = targetOverrides[entry.id] ?: selectedTarget

internal fun groupPasswordBatchEntriesByTarget(
    entries: List<PasswordEntry>,
    selectedTarget: UnifiedMoveCategoryTarget,
    targetOverrides: Map<Long, UnifiedMoveCategoryTarget>
): List<Pair<UnifiedMoveCategoryTarget, List<PasswordEntry>>> =
    entries
        .groupBy { entry ->
            passwordBatchTargetForEntry(entry, selectedTarget, targetOverrides)
        }
        .map { (target, groupedEntries) -> target to groupedEntries }

internal suspend fun resolvePasswordBatchPreservedCategoryTargets(
    entries: List<PasswordEntry>,
    selectedTarget: UnifiedMoveCategoryTarget,
    categories: List<Category>,
    bitwardenRepository: BitwardenRepository,
    localKeePassViewModel: LocalKeePassViewModel,
    passwordViewModel: PasswordViewModel
): Map<Long, UnifiedMoveCategoryTarget> {
    if (entries.isEmpty()) return emptyMap()

    val categoryById = categories.associateBy(Category::id)
    val bitwardenFoldersByVault = mutableMapOf<Long, List<takagi.ru.monica.data.bitwarden.BitwardenFolder>>()
    val mdbxFoldersByDatabase = mutableMapOf<Long, List<MdbxStoredFolderEntry>>()
    val resolvedTargetCache = mutableMapOf<String, UnifiedMoveCategoryTarget>()

    suspend fun bitwardenFolders(vaultId: Long): List<takagi.ru.monica.data.bitwarden.BitwardenFolder> {
        bitwardenFoldersByVault[vaultId]?.let { return it }
        return bitwardenRepository.getFolders(vaultId).also { folders ->
            bitwardenFoldersByVault[vaultId] = folders
        }
    }

    suspend fun mdbxFolders(databaseId: Long): List<MdbxStoredFolderEntry> {
        mdbxFoldersByDatabase[databaseId]?.let { return it }
        return passwordViewModel.listMdbxFoldersAwait(databaseId).also { folders ->
            mdbxFoldersByDatabase[databaseId] = folders
        }
    }

    suspend fun sourceSegments(entry: PasswordEntry): List<String> = when {
        entry.mdbxDatabaseId != null -> {
            val folderId = entry.mdbxFolderId?.takeIf { it.isNotBlank() && it != "root" }
            if (folderId == null) {
                emptyList()
            } else {
                resolveMdbxFolderSegments(folderId, mdbxFolders(entry.mdbxDatabaseId))
            }
        }
        entry.keepassDatabaseId != null -> decodeKeePassPathSegments(entry.keepassGroupPath)
        entry.bitwardenVaultId != null -> {
            val folderId = entry.bitwardenFolderId?.takeIf(String::isNotBlank)
            if (folderId == null) {
                emptyList()
            } else {
                bitwardenFolders(entry.bitwardenVaultId)
                    .firstOrNull { it.bitwardenFolderId == folderId }
                    ?.name
                    ?.let(::decodeFlatTransferCategoryName)
                    .orEmpty()
            }
        }
        else -> entry.categoryId
            ?.let(categoryById::get)
            ?.name
            ?.let(::decodeFlatTransferCategoryName)
            .orEmpty()
    }.map(String::trim).filter(String::isNotBlank)

    suspend fun resolveTarget(segments: List<String>): UnifiedMoveCategoryTarget {
        if (segments.isEmpty()) return selectedTarget
        val normalizedKey = segments.joinToString("/") { it.lowercase() }
        resolvedTargetCache[normalizedKey]?.let { return it }
        val resolved = when (selectedTarget) {
            UnifiedMoveCategoryTarget.Uncategorized,
            is UnifiedMoveCategoryTarget.MonicaCategory -> {
                val baseName = (selectedTarget as? UnifiedMoveCategoryTarget.MonicaCategory)
                    ?.categoryId
                    ?.let(categoryById::get)
                    ?.name
                    ?.takeIf(String::isNotBlank)
                val name = (listOfNotNull(baseName) + segments).joinToString(" / ")
                UnifiedMoveCategoryTarget.MonicaCategory(
                    passwordViewModel.ensureLocalCategoryAwait(name)
                )
            }
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                val vaultId = when (selectedTarget) {
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> selectedTarget.vaultId
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> selectedTarget.vaultId
                    else -> error("Unexpected Bitwarden target")
                }
                val baseName = (selectedTarget as? UnifiedMoveCategoryTarget.BitwardenFolderTarget)
                    ?.folderId
                    ?.let { folderId ->
                        bitwardenFolders(vaultId)
                            .firstOrNull { it.bitwardenFolderId == folderId }
                            ?.name
                    }
                    ?.takeIf(String::isNotBlank)
                val name = (listOfNotNull(baseName) + segments).joinToString("/")
                val folder = bitwardenFolders(vaultId)
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: bitwardenRepository.createFolder(vaultId, name).getOrThrow().also { created ->
                        bitwardenFoldersByVault[vaultId] = bitwardenFolders(vaultId) + created
                    }
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
                    segments = segments
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
                    ?.folderId
                    ?: "root"
                val folderId = passwordViewModel.ensureMdbxFolderPathAwait(
                    databaseId = databaseId,
                    parentFolderId = parentFolderId,
                    segments = segments
                ).getOrThrow()
                UnifiedMoveCategoryTarget.MdbxFolderTarget(databaseId, folderId)
            }
        }
        resolvedTargetCache[normalizedKey] = resolved
        return resolved
    }

    return buildMap {
        entries.forEach { entry ->
            val segments = sourceSegments(entry)
            if (segments.isNotEmpty()) {
                put(entry.id, resolveTarget(segments))
            }
        }
    }
}

private fun decodeFlatTransferCategoryName(name: String): List<String> =
    name.split(Regex("\\s*/\\s*"))
        .map(String::trim)
        .filter(String::isNotBlank)

internal fun resolveMdbxFolderSegments(
    folderId: String,
    folders: List<MdbxStoredFolderEntry>
): List<String> {
    val foldersById = folders.associateBy(MdbxStoredFolderEntry::folderId)
    val seen = mutableSetOf<String>()
    val reversed = mutableListOf<String>()
    var currentId: String? = folderId
    while (!currentId.isNullOrBlank() && currentId != "root" && seen.add(currentId)) {
        val folder = foldersById[currentId] ?: break
        if (folder.name.isNotBlank()) reversed += folder.name.trim()
        currentId = folder.parentFolderId
    }
    return reversed.asReversed()
}

@Composable
internal fun PasswordBatchPreserveCategoriesDialog(
    prompt: PasswordBatchPreserveCategoriesPrompt,
    onDismiss: () -> Unit
) {
    var preserveCategories by remember(prompt) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
        title = { Text("保留原分类？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "选中的 ${prompt.classifiedItemCount} 个密码带有分类。目标中缺少的分类可以自动创建。",
                    style = MaterialTheme.typography.bodyMedium
                )
                PasswordBatchCategoryChoice(
                    title = "按原分类整理",
                    supportingText = "复用同名分类，并自动创建缺少的文件夹",
                    icon = Icons.Default.Folder,
                    selected = preserveCategories,
                    onClick = { preserveCategories = true }
                )
                PasswordBatchCategoryChoice(
                    title = "全部放入所选位置",
                    supportingText = "忽略来源分类，维持原有批量传输方式",
                    icon = Icons.Default.FolderOff,
                    selected = !preserveCategories,
                    onClick = { preserveCategories = false }
                )
                Text(
                    "没有分类的密码仍会放入所选位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.proceed(preserveCategories) }) {
                Text("继续")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PasswordBatchCategoryChoice(
    title: String,
    supportingText: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
