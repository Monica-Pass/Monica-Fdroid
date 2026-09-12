package takagi.ru.monica.data.dedup

import takagi.ru.monica.R
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.utils.StringResolver

internal fun ItemType.dedupLabel(strings: StringResolver): String = strings.get(
    when (this) {
        ItemType.PASSWORD -> R.string.item_type_password
        ItemType.TOTP -> R.string.item_type_authenticator
        ItemType.BANK_CARD -> R.string.item_type_bank_card
        ItemType.DOCUMENT -> R.string.item_type_document
        ItemType.BILLING_ADDRESS -> R.string.billing_address
        ItemType.PAYMENT_ACCOUNT -> R.string.payment_account
        ItemType.NOTE -> R.string.dedup_merge_type_note
    }
)
