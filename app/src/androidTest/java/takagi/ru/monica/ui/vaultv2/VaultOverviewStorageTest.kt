package takagi.ru.monica.ui.vaultv2

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.utils.SettingsManager

@RunWith(AndroidJUnit4::class)
class VaultOverviewStorageTest {
    @Test fun overviewPreferencesSurvivePortableBackupAndRestore() = runBlocking {
        val manager = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = manager.exportPageAdjustmentSettings()
        try {
            val config = VaultOverviewConfig(order = VaultOverviewModule.defaultOrder.reversed(),
                hidden = setOf("DATABASES"), collapsed = setOf("FAVORITES"),
                pinnedCards = listOf("local/BANK_CARD/1:10"), pinnedItems = listOf("bitwarden:2/password/cipher"),
                recommendCards = false, scope = "bitwarden:2")
            manager.updateVaultOverviewEnabled(false)
            manager.updateVaultOverviewConfig { config }
            manager.settingsFlow.first { !it.vaultOverviewEnabled && it.vaultOverviewConfig == config }
            val backup = manager.exportPageAdjustmentSettings()
            manager.updateVaultOverviewEnabled(true)
            manager.updateVaultOverviewConfig { VaultOverviewConfig() }
            manager.importPageAdjustmentSettings(backup)
            val restored = manager.settingsFlow.first { !it.vaultOverviewEnabled && it.vaultOverviewConfig == config }
            assertFalse(restored.vaultOverviewEnabled)
            assertEquals(config, restored.vaultOverviewConfig)
            assertEquals(original.vaultV2LayoutMode, restored.vaultV2LayoutMode.name)
        } finally {
            manager.importPageAdjustmentSettings(original)
        }
    }

    @Test fun trashCountCombinesBothRoomSchemasWithoutReadingItemContents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val password = PasswordEntry(title = "", website = "", username = "", password = "unread-credential", isDeleted = true)
            val item = SecureItem(itemType = ItemType.BANK_CARD, title = "", itemData = "unread-card", isDeleted = true)
            database.passwordEntryDao().insertPasswordEntry(password)
            database.passwordEntryDao().insertPasswordEntry(password.copy(keepassDatabaseId = 3))
            database.secureItemDao().insertItem(item.copy(keepassDatabaseId = 3))
            database.secureItemDao().insertItem(item.copy(bitwardenVaultId = 5))
            database.secureItemDao().insertItem(item.copy(isDeleted = false))
            val counts = database.passwordEntryDao().observeVaultOverviewTrashCounts().first()
                .associate { vaultOverviewSourceKey(it.bitwardenVaultId, it.keepassDatabaseId, it.mdbxDatabaseId) to it.count }
            assertEquals(mapOf("local" to 1, "keepass:3" to 2, "bitwarden:5" to 1), counts)
        } finally { database.close() }
    }
}
