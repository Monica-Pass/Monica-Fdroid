package takagi.ru.monica.ui.screens

import androidx.compose.runtime.Composable
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.credentialexchange.ImportDestinationKind
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.password.KeepassNativeManagerTopActionsMenuItem

internal fun UnifiedCategoryFilterSelection.managerDatabase(): ImportDestination? = when (this) {
    is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> ImportDestination(ImportDestinationKind.MDBX, databaseId)
    is UnifiedCategoryFilterSelection.MdbxFolderFilter -> ImportDestination(ImportDestinationKind.MDBX, databaseId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> ImportDestination(ImportDestinationKind.KEEPASS, databaseId)
    is UnifiedCategoryFilterSelection.KeePassGroupFilter -> ImportDestination(ImportDestinationKind.KEEPASS, databaseId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> ImportDestination(ImportDestinationKind.KEEPASS, databaseId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> ImportDestination(ImportDestinationKind.KEEPASS, databaseId)
    else -> null
}

@Composable
internal fun DatabaseManagerMenuItem(database: ImportDestination?, onDismiss: () -> Unit) {
    if (database != null) KeepassNativeManagerTopActionsMenuItem(onClick = {
        onDismiss()
        DatabaseManagerNavigation.open(database)
    })
}
