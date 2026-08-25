package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import java.util.Date

class MdbxSecureItemBatchSupportTest {

    @Test
    fun copyCreatesIndependentMdbxObjectAndClearsSourceBindings() {
        val now = Date(42L)
        val source = secureItem(
            id = 7L,
            replicaGroupId = "note:7",
            keepassDatabaseId = 3L,
            bitwardenVaultId = 5L,
            bitwardenCipherId = "cipher"
        )

        val copied = source.asMdbxBatchCopy(
            databaseId = 11L,
            folderId = "folder",
            storedItemData = "rewritten",
            now = now
        )

        assertEquals(0L, copied.id)
        assertEquals(0, copied.sortOrder)
        assertEquals("rewritten", copied.itemData)
        assertEquals(11L, copied.mdbxDatabaseId)
        assertEquals("folder", copied.mdbxFolderId)
        assertNull(copied.replicaGroupId)
        assertNull(copied.keepassDatabaseId)
        assertNull(copied.bitwardenVaultId)
        assertNull(copied.bitwardenCipherId)
        assertEquals("NONE", copied.syncStatus)
        assertEquals(now, copied.createdAt)
        assertEquals(now, copied.updatedAt)
    }

    @Test
    fun movePreservesObjectIdentityWhileClearingForeignStorageBindings() {
        val now = Date(84L)
        val source = secureItem(
            id = 8L,
            replicaGroupId = "note:8",
            keepassDatabaseId = 3L,
            bitwardenVaultId = null,
            bitwardenCipherId = null
        )

        val moved = source.asMdbxBatchMove(
            databaseId = 12L,
            folderId = null,
            now = now
        )

        assertEquals(8L, moved.id)
        assertEquals("note:8", moved.replicaGroupId)
        assertEquals(12L, moved.mdbxDatabaseId)
        assertNull(moved.mdbxFolderId)
        assertNull(moved.keepassDatabaseId)
        assertEquals(source.createdAt, moved.createdAt)
        assertEquals(now, moved.updatedAt)
    }

    @Test
    fun replicaConflictDetectionKeepsOnePlannedReplicaAndBlocksOccupiedTargets() {
        val first = secureItem(id = 1L, replicaGroupId = "note:shared")
        val second = secureItem(id = 2L, replicaGroupId = "note:shared")
        val occupied = secureItem(
            id = 3L,
            replicaGroupId = "note:occupied",
            mdbxDatabaseId = 9L,
            mdbxFolderId = "target"
        )
        val incoming = secureItem(id = 4L, replicaGroupId = "note:occupied")

        val conflicts = findMdbxReplicaTargetConflictIds(
            selectedItems = listOf(first, second, incoming),
            activeItems = listOf(first, second, occupied, incoming),
            databaseId = 9L,
            folderId = "target"
        )

        assertFalse(1L in conflicts)
        assertTrue(2L in conflicts)
        assertTrue(4L in conflicts)
    }

    private fun secureItem(
        id: Long,
        replicaGroupId: String?,
        keepassDatabaseId: Long? = null,
        bitwardenVaultId: Long? = null,
        bitwardenCipherId: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null
    ): SecureItem = SecureItem(
        id = id,
        itemType = ItemType.NOTE,
        title = "Title $id",
        notes = "Notes",
        sortOrder = 17,
        itemData = "{}",
        categoryId = 4L,
        keepassDatabaseId = keepassDatabaseId,
        keepassGroupPath = keepassDatabaseId?.let { "Group" },
        keepassEntryUuid = keepassDatabaseId?.let { "entry" },
        keepassGroupUuid = keepassDatabaseId?.let { "group" },
        mdbxDatabaseId = mdbxDatabaseId,
        mdbxFolderId = mdbxFolderId,
        replicaGroupId = replicaGroupId,
        bitwardenVaultId = bitwardenVaultId,
        bitwardenCipherId = bitwardenCipherId,
        bitwardenFolderId = bitwardenVaultId?.let { "folder" },
        bitwardenRevisionDate = bitwardenVaultId?.let { "revision" },
        bitwardenLocalModified = bitwardenVaultId != null,
        syncStatus = if (bitwardenVaultId != null) "SYNCED" else "NONE",
        createdAt = Date(1L),
        updatedAt = Date(2L)
    )
}
