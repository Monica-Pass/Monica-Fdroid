package takagi.ru.monica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.writeOperationAvailability
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.components.DatabaseFilterChipContent
import takagi.ru.monica.ui.components.DatabaseFilterChipItem
import takagi.ru.monica.viewmodel.CategoryFilter

internal data class PasswordDatabaseFiltersSectionParams(
    val currentFilter: CategoryFilter,
    val keepassDatabases: List<LocalKeePassDatabase>,
    val mdbxDatabases: List<LocalMdbxDatabase>,
    val bitwardenVaults: List<BitwardenVault>,
    val onSelectFilter: (CategoryFilter) -> Unit
)

@Composable
internal fun PasswordDatabaseFiltersSection(
    params: PasswordDatabaseFiltersSectionParams,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 160),
        label = "database_section_arrow"
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { expanded = !expanded }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.category_selection_menu_databases),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation }
            )
        }
        val allLabel = stringResource(R.string.category_all)
        val localLabel = stringResource(R.string.category_selection_menu_local_database)
        val items = remember(params.keepassDatabases, params.mdbxDatabases, params.bitwardenVaults, allLabel, localLabel) {
            buildList<DatabaseFilterChipItem<CategoryFilter>> {
                add(DatabaseFilterChipItem("all", allLabel, Icons.Default.List, CategoryFilter.All))
                add(DatabaseFilterChipItem("local", localLabel, Icons.Default.Smartphone, CategoryFilter.Local))
                params.keepassDatabases.forEach { database ->
                    add(DatabaseFilterChipItem(
                        "keepass:${database.id}", database.name, Icons.Default.Key,
                        CategoryFilter.KeePassDatabase(database.id),
                        if (database.writeOperationAvailability().canOperate) StorageHealthyGreen else null,
                    ))
                }
                params.mdbxDatabases.forEach { database ->
                    add(DatabaseFilterChipItem(
                        "mdbx:${database.id}", database.name, Icons.Default.Storage,
                        CategoryFilter.MdbxDatabase(database.id),
                    ))
                }
                params.bitwardenVaults.forEach { vault ->
                    add(DatabaseFilterChipItem(
                        "bitwarden:${vault.id}", vault.email.ifBlank { "Bitwarden" }, Icons.Default.CloudSync,
                        CategoryFilter.BitwardenVault(vault.id),
                        if (vault.hasHealthyConnection()) StorageHealthyGreen else null,
                    ))
                }
            }
        }
        DatabaseFilterChipContent(
            items = items,
            expanded = expanded,
            isSelected = { filter ->
                when (filter) {
                    CategoryFilter.All -> params.currentFilter is CategoryFilter.All
                    CategoryFilter.Local -> params.currentFilter.isMonicaDatabaseFilter()
                    is CategoryFilter.KeePassDatabase -> params.currentFilter.isKeePassDatabaseFilter(filter.databaseId)
                    is CategoryFilter.MdbxDatabase -> params.currentFilter.isMdbxDatabaseFilter(filter.databaseId)
                    is CategoryFilter.BitwardenVault -> params.currentFilter.isBitwardenVaultFilter(filter.vaultId)
                    else -> false
                }
            },
            onSelect = params.onSelectFilter,
        )
    }
}

private val StorageHealthyGreen = Color(0xFF22C55E)

private fun BitwardenVault.hasHealthyConnection(): Boolean {
    return isConnected && !encryptedRefreshToken.isNullOrBlank()
}
