package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.PasswordBatchAggregateSelection

internal data class VaultV2BatchMovePlan(
    val passwordEntries: List<PasswordEntry>,
    val aggregateSelection: PasswordBatchAggregateSelection,
) {
    val totalCount: Int
        get() = passwordEntries.size +
            aggregateSelection.bankCards.size +
            aggregateSelection.documents.size +
            aggregateSelection.billingAddresses.size +
            aggregateSelection.notes.size +
            aggregateSelection.totpItems.size +
            aggregateSelection.passkeys.size
}

internal fun buildVaultV2BatchMovePlan(
    selectedItems: List<VaultV2Item>,
): VaultV2BatchMovePlan = VaultV2BatchMovePlan(
    passwordEntries = selectedItems.mapNotNull { item ->
        item.passwordEntry.takeIf { item.type == VaultV2ItemType.PASSWORD }
    },
    aggregateSelection = PasswordBatchAggregateSelection(
        bankCards = selectedItems.mapNotNull { item ->
            item.secureItem.takeIf { item.type == VaultV2ItemType.BANK_CARD }
        },
        documents = selectedItems.mapNotNull { item ->
            item.secureItem.takeIf { item.type == VaultV2ItemType.DOCUMENT }
        },
        notes = selectedItems.mapNotNull { item ->
            item.secureItem.takeIf { item.type == VaultV2ItemType.NOTE }
        },
        totpItems = selectedItems.mapNotNull { item ->
            item.totpItem.takeIf { item.type == VaultV2ItemType.AUTHENTICATOR }
        },
        passkeys = selectedItems.mapNotNull { item ->
            item.passkeyEntry.takeIf { item.type == VaultV2ItemType.PASSKEY }
        },
    ),
)
