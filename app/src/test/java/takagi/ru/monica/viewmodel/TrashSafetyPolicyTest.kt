package takagi.ru.monica.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType

class TrashSafetyPolicyTest {

    @Test
    fun disabledTrashNeverRunsAutomaticCleanup() {
        assertFalse(TrashSettings(enabled = false, autoDeleteDays = 30).shouldAutoCleanup())
        assertFalse(TrashSettings(enabled = true, autoDeleteDays = 0).shouldAutoCleanup())
        assertTrue(TrashSettings(enabled = true, autoDeleteDays = 30).shouldAutoCleanup())
    }

    @Test
    fun allSecureItemTypesHaveTrashCategories() {
        assertTrue(TRASH_SECURE_ITEM_TYPES.contains(ItemType.BILLING_ADDRESS))
        assertTrue(TRASH_SECURE_ITEM_TYPES.contains(ItemType.PAYMENT_ACCOUNT))
        assertEqualsAllSecureTypes()
    }

    private fun assertEqualsAllSecureTypes() {
        assertTrue(
            TRASH_SECURE_ITEM_TYPES.toSet().containsAll(
                setOf(
                    ItemType.TOTP,
                    ItemType.BANK_CARD,
                    ItemType.DOCUMENT,
                    ItemType.NOTE,
                    ItemType.BILLING_ADDRESS,
                    ItemType.PAYMENT_ACCOUNT
                )
            )
        )
    }
}
