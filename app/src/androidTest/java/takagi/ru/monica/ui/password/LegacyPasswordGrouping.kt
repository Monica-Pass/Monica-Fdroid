package takagi.ru.monica.ui

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.getPasswordInfoKey
import takagi.ru.monica.ui.password.getGroupKeyForMode

// Baseline retained only for behavioral/performance comparison in instrumented tests.
private const val MANUAL_STACK_GROUP_KEY_PREFIX = "manual_stack:"
private const val NO_STACK_GROUP_KEY_PREFIX = "no_stack:"

internal fun buildLegacyPasswordGroups(
    sourceEntries: List<PasswordEntry>,
    config: PasswordGroupingConfig
): Map<String, List<PasswordEntry>> {
    val mergedByInfo = if (config.effectiveStackCardMode == StackCardMode.ALWAYS_EXPANDED) {
        sourceEntries.sortedBy { it.sortOrder }.map { listOf(it) }
    } else {
        sourceEntries
            .groupBy { entry ->
                if (entry.id in config.effectiveNoStackEntryIds) {
                    "$NO_STACK_GROUP_KEY_PREFIX${entry.id}"
                } else {
                    config.effectiveManualStackGroupByEntryId[entry.id]
                        ?.let { groupId -> "$MANUAL_STACK_GROUP_KEY_PREFIX$groupId" }
                        ?: getPasswordInfoKey(entry)
                }
            }
            .map { (_, entries) -> entries.sortedBy { it.sortOrder } }
    }

    val groupedAndSorted = if (config.isLocalOnlyView) {
        sourceEntries
            .sortedBy { it.sortOrder }
            .associate { entry -> "entry_${entry.id}" to listOf(entry) }
    } else {
        when (config.effectiveGroupMode) {
            "title" -> mergedByInfo
                .groupBy { entries ->
                    val first = entries.first()
                    if (first.id in config.effectiveNoStackEntryIds) {
                        "$NO_STACK_GROUP_KEY_PREFIX${first.id}"
                    } else {
                        config.effectiveManualStackGroupByEntryId[first.id]
                            ?.let { groupId -> "$MANUAL_STACK_GROUP_KEY_PREFIX$groupId" }
                            ?: first.title.ifBlank { config.untitledLabel }
                    }
                }
                .mapValues { (_, groups) -> groups.flatten() }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<String, List<PasswordEntry>>> { (_, passwords) ->
                        val infoKeyGroups = passwords.groupBy { getPasswordInfoKey(it) }
                        val cardType = when {
                            infoKeyGroups.size > 1 -> 3
                            infoKeyGroups.size == 1 && passwords.size > 1 -> 2
                            else -> 1
                        }
                        val favoriteBonus = if (passwords.any { it.isFavorite }) 10 else 0
                        favoriteBonus.toDouble() + cardType.toDouble()
                    }.thenBy { (title, _) -> title }
                )
                .toMap()

            else -> mergedByInfo
                .groupBy { entries ->
                    val first = entries.first()
                    if (first.id in config.effectiveNoStackEntryIds) {
                        "$NO_STACK_GROUP_KEY_PREFIX${first.id}"
                    } else {
                        config.effectiveManualStackGroupByEntryId[first.id]
                            ?.let { groupId -> "$MANUAL_STACK_GROUP_KEY_PREFIX$groupId" }
                            ?: getGroupKeyForMode(
                                first,
                                config.effectiveGroupMode,
                                config.websiteStackMatchMode
                            )
                    }
                }
                .mapValues { (_, groups) -> groups.flatten() }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<String, List<PasswordEntry>>> { (_, passwords) ->
                        val infoKeyGroups = passwords.groupBy { getPasswordInfoKey(it) }
                        val cardType = when {
                            infoKeyGroups.size > 1 -> 3
                            infoKeyGroups.size == 1 && passwords.size > 1 -> 2
                            else -> 1
                        }
                        val favoriteBonus = if (passwords.any { it.isFavorite }) 10 else 0
                        favoriteBonus.toDouble() + cardType.toDouble()
                    }.thenBy { (_, passwords) ->
                        passwords.firstOrNull()?.sortOrder ?: Int.MAX_VALUE
                    }
                )
                .toMap()
        }
    }

    return if (config.effectiveStackCardMode == StackCardMode.ALWAYS_EXPANDED) {
        groupedAndSorted.values.flatten()
            .map { entry -> "entry_${entry.id}" to listOf(entry) }
            .toMap()
    } else {
        groupedAndSorted
    }
}
