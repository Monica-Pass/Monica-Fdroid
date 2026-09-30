package takagi.ru.monica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.rememberUiSecurityManager
import takagi.ru.monica.ui.vaultv2.*

/** Use the frequent-card sheet in single-copy mode, returning the untouched source item. */
@Composable
fun EmbeddedWalletPicker(
    items: List<SecureItem>,
    title: String,
    onSelect: (SecureItem) -> Unit,
    onDismiss: () -> Unit,
    keepassDatabases: List<LocalKeePassDatabase> = emptyList(),
    mdbxDatabases: List<LocalMdbxDatabase> = emptyList(),
    bitwardenVaults: List<BitwardenVault> = emptyList(),
) {
    val security = rememberUiSecurityManager()
    val entries = remember(items) { embeddedWalletPickerEntries(items) }
    val localLabel = stringResource(R.string.vault_overview_local)
    // The caller supplies accessible cards/notes. Never fetch additional entries here.
    val sources = remember(entries, keepassDatabases, mdbxDatabases, bitwardenVaults, localLabel) {
        entries.map { it.overviewSource() }.distinct().map { key ->
            val id = key.substringAfter(':', "").toLongOrNull()
            when {
                key.startsWith("keepass:") -> VaultOverviewSource(key,
                    keepassDatabases.firstOrNull { it.id == id }?.name ?: "KeePass", "KeePass")
                key.startsWith("mdbx:") -> VaultOverviewSource(key,
                    mdbxDatabases.firstOrNull { it.id == id }?.name ?: "MDBX", "MDBX")
                key.startsWith("bitwarden:") -> {
                    val vault = bitwardenVaults.firstOrNull { it.id == id }
                    VaultOverviewSource(key, vault?.displayName?.takeIf(String::isNotBlank) ?: vault?.email ?: "Bitwarden",
                        "Bitwarden", locked = vault?.isLocked == true)
                }
                else -> VaultOverviewSource(key, localLabel, "Monica")
            }
        }
    }
    VaultOverviewPickerSheet(
        cards = entries.any { it.type in overviewCardTypes }, items = entries,
        currentFrequentItems = emptyList(), sources = sources, currentScope = "all",
        keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults,
        config = VaultOverviewConfig(), securityManager = security, onConfigChange = {}, onDismiss = onDismiss,
        copyAction = OverviewPickerCopyAction(title) { row -> row.secureItem?.let(onSelect) },
    )
}

internal fun embeddedWalletPickerEntries(items: List<SecureItem>): List<VaultV2Item> = items.mapNotNull { item ->
    if (item.isDeleted) return@mapNotNull null
    val type = when (item.itemType) {
        ItemType.BANK_CARD -> VaultV2ItemType.BANK_CARD
        ItemType.DOCUMENT -> VaultV2ItemType.DOCUMENT
        ItemType.BILLING_ADDRESS -> VaultV2ItemType.BILLING_ADDRESS
        ItemType.NOTE -> VaultV2ItemType.NOTE
        else -> return@mapNotNull null
    }
    VaultV2Item(key = "${item.itemType.name.lowercase(java.util.Locale.ROOT)}:${item.id}", type = type,
        title = item.title, subtitle = "", isFavorite = item.isFavorite, sortKey = item.title,
        searchableValues = emptyList(), secureItem = item)
}
