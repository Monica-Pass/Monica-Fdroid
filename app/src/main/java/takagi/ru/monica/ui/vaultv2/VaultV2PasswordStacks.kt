package takagi.ru.monica.ui.vaultv2

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.data.PasswordPageAggregateStackEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.StackedPasswordGroup
import takagi.ru.monica.ui.password.buildPasswordAggregateManualStackGroups
import takagi.ru.monica.ui.password.getGroupKeyForMode

internal fun buildVaultV2NativeTokenItems(tokens: List<NativeApiTokenSummary>, typeLabel: String): List<VaultV2Item> =
    tokens.map { token ->
        buildVaultV2PasswordItems(listOf(token.asPasswordCard(typeLabel))).single().copy(
            nativeToken = token,
            searchableValues = listOf(token.title, token.collectionTitle, typeLabel),
        )
    }

/** Group the actual list model, so selection and the scrollbar describe the same rows. */
internal fun buildVaultV2StackedSections(
    items: List<VaultV2Item>,
    stackEntries: List<PasswordPageAggregateStackEntry>,
    legacyGroups: Map<Long, String>,
    noStackIds: Set<Long>,
    groupMode: String,
    websiteMatchMode: String,
    alwaysExpanded: Boolean,
): List<Pair<String, List<VaultV2Item>>> {
    if (alwaysExpanded) return buildVaultV2Sections(items)
    val aggregate = buildPasswordAggregateManualStackGroups(stackEntries, items.mapNotNull { it.passwordEntry }, emptyList())
    val manualGroups = aggregate.groups.flatMap { group ->
        group.cards.mapIndexed { index, card -> card.key to ("manual:${group.groupKey}" to index) }
    }.toMap()
    val groups = items.groupBy { item ->
        val entry = item.passwordEntry
        when {
            entry == null || entry.id in noStackIds -> item.key
            item.key in manualGroups -> manualGroups.getValue(item.key).first
            entry.id in legacyGroups -> "legacy:${legacyGroups.getValue(entry.id)}"
            groupMode != "none" -> "auto:${getGroupKeyForMode(entry, groupMode, websiteMatchMode)}"
            else -> item.key
        }
    }
    return buildVaultV2Sections(groups.map { (key, members) ->
        if (members.size == 1) members.single() else {
            val ordered = members.sortedBy { manualGroups[it.key]?.second ?: Int.MAX_VALUE }
            ordered.first().copy(key = "stack:$key", stackedItems = ordered)
        }
    })
}

@Composable
internal fun VaultV2PasswordStackCard(
    item: VaultV2Item,
    appSettings: AppSettings,
    securityManager: SecurityManager,
    selectedKeys: MutableList<String>,
    onOpenItem: (VaultV2Item) -> Unit,
    onRequestDeleteItems: (List<VaultV2Item>) -> Unit,
    onFavoriteItem: (VaultV2Item) -> Unit,
    onReorderStack: (List<VaultV2Item>) -> Unit,
) {
    val entries = remember(item.stackedItems) { item.stackedItems.mapNotNull { it.passwordEntry } }
    val byId = remember(item.stackedItems) { item.stackedItems.associateBy { it.passwordEntry?.id } }
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    fun toggle(keys: List<String>) {
        if (keys.all { it in selectedKeys }) selectedKeys.removeAll(keys.toSet())
        else selectedKeys.addAll(keys.filterNot { it in selectedKeys })
    }
    StackedPasswordGroup(
        passwords = entries,
        isExpanded = expanded,
        stackCardMode = StackCardMode.AUTO,
        onToggleExpand = { expanded = !expanded },
        onPasswordClick = { entry -> byId[entry.id]?.let { row ->
            if (selectedKeys.isEmpty()) onOpenItem(row) else toggle(listOf(row.key))
        } },
        onSwipeLeft = { entry -> byId[entry.id]?.let { onRequestDeleteItems(listOf(it)) } },
        onSwipeRight = { entry -> byId[entry.id]?.let { toggle(listOf(it.key)) } },
        onGroupSwipeRight = { group -> toggle(group.mapNotNull { byId[it.id]?.key }) },
        onToggleFavorite = { entry -> byId[entry.id]?.let(onFavoriteItem) },
        onToggleGroupCover = { entry ->
            onReorderStack(item.stackedItems.sortedBy { it.passwordEntry?.id != entry.id })
        },
        isSelectionMode = selectedKeys.isNotEmpty(),
        selectedPasswords = entries.filter { byId[it.id]?.key in selectedKeys }.mapTo(hashSetOf()) { it.id },
        onToggleSelection = { id -> byId[id]?.let { toggle(listOf(it.key)) } },
        onOpenMultiPasswordDialog = { expanded = true },
        onLongClick = { entry -> byId[entry.id]?.let { toggle(listOf(it.key)) } },
        iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
        unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
        passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
        passwordCardDisplayFields = appSettings.passwordCardDisplayFields,
        showAuthenticator = appSettings.passwordCardShowAuthenticator,
        hideOtherContentWhenAuthenticator = appSettings.passwordCardHideOtherContentWhenAuthenticator,
        totpTimeOffsetSeconds = appSettings.totpTimeOffset,
        smoothAuthenticatorProgress = appSettings.validatorSmoothProgress,
        decryptAuthenticatorKey = securityManager::decryptDataIfMonicaCiphertext,
        enableSharedBounds = false,
    )
}
