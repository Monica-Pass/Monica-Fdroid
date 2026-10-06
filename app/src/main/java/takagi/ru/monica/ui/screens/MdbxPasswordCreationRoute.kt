package takagi.ru.monica.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.isUnsupportedGlitter
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.MdbxViewModel

/** One target resolver for routing and the ordinary editor: explicit local beats the current filter. */
internal fun passwordCreationTargets(
    filter: CategoryFilter, inherited: List<StorageTarget>?, explicit: Boolean,
    categoryId: Long?, keepassId: Long?, keepassGroup: String?,
    mdbxId: Long?, mdbxFolder: String?, bitwardenId: Long?, bitwardenFolder: String?,
): List<StorageTarget> {
    if (!inherited.isNullOrEmpty()) return inherited.distinct()
    if (explicit || categoryId != null || keepassId != null || keepassGroup != null ||
        mdbxId != null || mdbxFolder != null || bitwardenId != null || bitwardenFolder != null) {
        return listOf(buildMultiStorageTarget(categoryId = categoryId, keepassDatabaseId = keepassId,
            keepassGroupPath = keepassGroup, mdbxDatabaseId = mdbxId, mdbxFolderId = mdbxFolder,
            bitwardenVaultId = bitwardenId, bitwardenFolderId = bitwardenFolder))
    }
    return listOf(when (filter) {
        is CategoryFilter.Custom -> StorageTarget.MonicaLocal(filter.categoryId)
        is CategoryFilter.KeePassDatabase -> StorageTarget.KeePass(filter.databaseId, null)
        is CategoryFilter.KeePassGroupFilter -> StorageTarget.KeePass(filter.databaseId, filter.groupPath)
        is CategoryFilter.KeePassDatabaseStarred -> StorageTarget.KeePass(filter.databaseId, null)
        is CategoryFilter.KeePassDatabaseUncategorized -> StorageTarget.KeePass(filter.databaseId, null)
        is CategoryFilter.MdbxDatabase -> StorageTarget.Mdbx(filter.databaseId)
        is CategoryFilter.MdbxFolderFilter -> StorageTarget.Mdbx(filter.databaseId, filter.folderId)
        is CategoryFilter.BitwardenVault -> StorageTarget.Bitwarden(filter.vaultId, null)
        is CategoryFilter.BitwardenFolderFilter -> StorageTarget.Bitwarden(filter.vaultId, filter.folderId)
        is CategoryFilter.BitwardenVaultStarred -> StorageTarget.Bitwarden(filter.vaultId, null)
        is CategoryFilter.BitwardenVaultUncategorized -> StorageTarget.Bitwarden(filter.vaultId, null)
        else -> StorageTarget.MonicaLocal(null)
    })
}

/** Old unsupported targets receive an explanation instead of a separate editor. */
@Composable
internal fun MdbxPasswordCreationRoute(
    targets: List<StorageTarget>, suppliedModel: MdbxViewModel?,
    onBack: () -> Unit,
): Boolean {
    val mdbxTargets = targets.filterIsInstance<StorageTarget.Mdbx>()
    if (mdbxTargets.isEmpty()) return false
    val context = LocalContext.current
    val security = takagi.ru.monica.ui.rememberUiSecurityManager()
    val model = suppliedModel ?: androidx.lifecycle.viewmodel.compose.viewModel<MdbxViewModel>(key = "password-native-mdbx") {
        val room = PasswordDatabase.getDatabase(context)
        MdbxViewModel(context.applicationContext as Application, room.localMdbxDatabaseDao(), room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
    }
    val databases by model.allDatabases.collectAsState()
    val loaded by model.allDatabasesLoaded.collectAsState()
    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return true
    }
    val records = mdbxTargets.map { target -> databases.firstOrNull { it.id == target.databaseId } }
    if (records.all { it != null && !it.isUnsupportedGlitter }) return false
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("mdbx_creation_unavailable"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (records.any { it == null }) R.string.mdbx_native_failed else R.string.mdbx_client_mode_unsupported))
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
    return true
}
