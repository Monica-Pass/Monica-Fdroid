package takagi.ru.monica.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.navigation.Screen
import takagi.ru.monica.viewmodel.CategoryFilter

internal const val TEMPLATE_TARGETS_KEY = "template_storage_targets"
internal val LocalTemplateTargets = staticCompositionLocalOf<List<StorageTarget>?> { null }
internal val LocalTemplateNavigation = staticCompositionLocalOf<((EntryTypeChipOption, List<StorageTarget>) -> Unit)?> { null }

internal fun decodeTemplateTargets(keys: List<String>?): List<StorageTarget>? = keys?.map { key ->
    val parts = key.split(':', limit = 3)
    when (parts[0]) {
        "local" -> StorageTarget.MonicaLocal(parts[1].takeUnless { it == "root" }?.toLong())
        "mdbx" -> StorageTarget.Mdbx(parts[1].toLong(), parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        "keepass" -> StorageTarget.KeePass(parts[1].toLong(), parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        "bitwarden" -> StorageTarget.Bitwarden(parts[1].toLong(), parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        else -> error("Unknown template storage target")
    }
}?.takeIf { it.isNotEmpty() }

internal fun templateDefaultTarget(filter: CategoryFilter): StorageTarget = when (filter) {
    is CategoryFilter.Custom -> StorageTarget.MonicaLocal(filter.categoryId)
    is CategoryFilter.MdbxDatabase -> StorageTarget.Mdbx(filter.databaseId)
    is CategoryFilter.MdbxFolderFilter -> StorageTarget.Mdbx(filter.databaseId, filter.folderId)
    is CategoryFilter.KeePassDatabase -> StorageTarget.KeePass(filter.databaseId, null)
    is CategoryFilter.KeePassGroupFilter -> StorageTarget.KeePass(filter.databaseId, filter.groupPath)
    is CategoryFilter.KeePassDatabaseStarred -> StorageTarget.KeePass(filter.databaseId, null)
    is CategoryFilter.KeePassDatabaseUncategorized -> StorageTarget.KeePass(filter.databaseId, null)
    is CategoryFilter.BitwardenVault -> StorageTarget.Bitwarden(filter.vaultId, null)
    is CategoryFilter.BitwardenFolderFilter -> StorageTarget.Bitwarden(filter.vaultId, filter.folderId)
    is CategoryFilter.BitwardenVaultStarred -> StorageTarget.Bitwarden(filter.vaultId, null)
    is CategoryFilter.BitwardenVaultUncategorized -> StorageTarget.Bitwarden(filter.vaultId, null)
    else -> StorageTarget.MonicaLocal(null)
}

internal fun NavHostController.navigateEntryTemplate(type: EntryTypeChipOption, targets: List<StorageTarget>) {
    val native = targets.filterIsInstance<StorageTarget.Mdbx>().firstOrNull()
    val route = when (type) {
        EntryTypeChipOption.PASSWORD -> Screen.AddEditPassword.createRoute()
        EntryTypeChipOption.API_KEY -> Screen.AddEditPassword.createRoute(initialType = "API_KEY")
        EntryTypeChipOption.GPG_KEY -> Screen.AddEditPassword.createRoute(initialType = "GPG_KEY")
        EntryTypeChipOption.BARCODE -> Screen.AddEditPassword.createRoute(initialType = "barcode")
        EntryTypeChipOption.WIFI -> Screen.AddEditWifi.createRoute()
        EntryTypeChipOption.SSH_KEY -> Screen.AddEditSshKey.createRoute()
        EntryTypeChipOption.API_TOKEN -> Screen.AddEditApiToken.createRoute(native?.databaseId, folderId = native?.folderId)
    }
    val previous = currentDestination?.route
    navigate(route) {
        // The main-screen two-pane editor does not own a navigation destination.
        if (previous in setOf(Screen.AddEditPassword.route, Screen.AddEditWifi.route, Screen.AddEditSshKey.route, Screen.AddEditApiToken.route)) {
            previous?.let { popUpTo(it) { inclusive = true } }
        }
        launchSingleTop = false
    }
    currentBackStackEntry?.savedStateHandle?.set(TEMPLATE_TARGETS_KEY, ArrayList(targets.map { it.stableKey }))
}
