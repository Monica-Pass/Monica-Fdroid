package takagi.ru.monica.notes.domain

import java.util.Date
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.ui.KeePassGroupFilterIdentity

class NoteScopeIndexTest {
    private fun note(id: Int) = SecureItem(
        id = id.toLong(), itemType = ItemType.NOTE, title = "Note $id", itemData = "",
        createdAt = Date(0), updatedAt = Date(0),
    )

    private fun fixture(): List<SecureItem> {
        val owners = listOf(
            note(0),
            note(1).copy(keepassDatabaseId = 7),
            note(2).copy(bitwardenVaultId = 7, bitwardenCipherId = "cipher"),
            note(3).copy(mdbxDatabaseId = 7),
            note(4).copy(bitwardenCipherId = "detached"),
            note(5).copy(bitwardenVaultId = 0, bitwardenCipherId = "zero"),
            note(6).copy(keepassDatabaseId = 0),
            note(7).copy(mdbxDatabaseId = 0),
            note(8).copy(keepassDatabaseId = 7, bitwardenVaultId = 7),
            note(9).copy(keepassDatabaseId = 7, bitwardenVaultId = 7, bitwardenCipherId = "conflict", keepassEntryUuid = "entry"),
            note(10).copy(mdbxDatabaseId = 7, bitwardenVaultId = 7),
            note(11).copy(bitwardenVaultId = 7),
        )
        val rows = mutableListOf<SecureItem>()
        for (owner in owners) for (category in listOf(null, 0L, 5L)) for (favorite in listOf(false, true)) {
            for ((path, uuid) in listOf(null to null, "" to "", "  " to null, "notes" to null,
                "notes" to " {AB-CD} ", "renamed" to "abcd", "notes" to "different")) {
                for (folder in listOf(null, "", "notes")) rows += owner.copy(
                    id = rows.size.toLong(), categoryId = category, isFavorite = favorite,
                    keepassGroupPath = path, keepassGroupUuid = uuid, bitwardenFolderId = folder,
                )
            }
        }
        return rows
    }

    private val scopes = listOf(
        NoteCategoryFilter.All, NoteCategoryFilter.Local, NoteCategoryFilter.Starred,
        NoteCategoryFilter.Uncategorized, NoteCategoryFilter.LocalStarred, NoteCategoryFilter.LocalUncategorized,
        NoteCategoryFilter.Custom(0), NoteCategoryFilter.Custom(5), NoteCategoryFilter.Custom(99),
        NoteCategoryFilter.BitwardenVault(7), NoteCategoryFilter.BitwardenVault(0),
        NoteCategoryFilter.BitwardenFolderFilter("notes", 7), NoteCategoryFilter.BitwardenFolderFilter("", 7),
        NoteCategoryFilter.BitwardenFolderFilter("missing", 7),
        NoteCategoryFilter.BitwardenVaultStarred(7), NoteCategoryFilter.BitwardenVaultUncategorized(7),
        NoteCategoryFilter.KeePassDatabase(7), NoteCategoryFilter.KeePassDatabase(0),
        NoteCategoryFilter.KeePassGroupFilter(7, "notes"), NoteCategoryFilter.KeePassGroupFilter(7, "notes", "AB-CD"),
        NoteCategoryFilter.KeePassGroupFilter(7, "notes", "unknown"), NoteCategoryFilter.KeePassGroupFilter(7, "", "{}"),
        NoteCategoryFilter.KeePassGroupFilter(7, "renamed", "{abcd}"),
        NoteCategoryFilter.KeePassDatabaseStarred(7), NoteCategoryFilter.KeePassDatabaseUncategorized(7),
        NoteCategoryFilter.MdbxDatabase(7), NoteCategoryFilter.MdbxDatabase(0), NoteCategoryFilter.MdbxDatabase(99),
    )

    @Test fun cachedScopesPreserveThePreviousFilterSemanticsIncludingConflictingOwnership() {
        val rows = fixture()
        val index = NoteScopeIndex(rows)
        for (scope in scopes) {
            val expected = rows.indices.filter { legacyMatches(rows[it], scope) }.toIntArray()
            assertArrayEquals(scope.toString(), expected, index.select(scope))
        }
    }

    @Test fun filteringKeepsTheOriginalOrderAndHandlesEmptyScopes() {
        val rows = List(300) { note(it).copy(isFavorite = it % 3 == 0) }
        val index = NoteScopeIndex(rows)
        val expected = (0 until 300 step 3).toList().toIntArray()
        assertArrayEquals(expected, index.select(NoteCategoryFilter.Starred))
        assertArrayEquals(intArrayOf(), index.select(NoteCategoryFilter.Custom(99)))
        assertArrayEquals(intArrayOf(), NoteScopeIndex(emptyList()).select(NoteCategoryFilter.All))
        assertEquals(300, index.select(NoteCategoryFilter.All).size)
    }

    // Reference the pre-optimization behavior, independently of the cached ownership snapshot.
    private fun legacyMatches(item: SecureItem, scope: NoteCategoryFilter): Boolean = when (scope) {
        NoteCategoryFilter.All -> true
        NoteCategoryFilter.Local -> item.isLocalOnlyItem()
        NoteCategoryFilter.Starred -> item.isFavorite
        NoteCategoryFilter.Uncategorized -> item.categoryId == null
        NoteCategoryFilter.LocalStarred -> item.isLocalOnlyItem() && item.isFavorite
        NoteCategoryFilter.LocalUncategorized -> item.isLocalOnlyItem() && item.categoryId == null
        is NoteCategoryFilter.Custom -> item.isLocalOnlyItem() && item.categoryId == scope.categoryId
        is NoteCategoryFilter.BitwardenVault -> (item.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == scope.vaultId
        is NoteCategoryFilter.BitwardenFolderFilter -> (item.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == scope.vaultId && item.bitwardenFolderId == scope.folderId
        is NoteCategoryFilter.BitwardenVaultStarred -> (item.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == scope.vaultId && item.isFavorite
        is NoteCategoryFilter.BitwardenVaultUncategorized -> (item.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == scope.vaultId && item.bitwardenFolderId == null
        is NoteCategoryFilter.KeePassDatabase -> (item.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == scope.databaseId
        is NoteCategoryFilter.KeePassGroupFilter -> KeePassGroupFilterIdentity(scope.databaseId, scope.groupPath, scope.groupUuid)
            .matches((item.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId, item.keepassGroupPath, item.keepassGroupUuid)
        is NoteCategoryFilter.KeePassDatabaseStarred -> (item.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == scope.databaseId && item.isFavorite
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> (item.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == scope.databaseId && item.keepassGroupPath.isNullOrBlank()
        is NoteCategoryFilter.MdbxDatabase -> (item.resolveOwnership() as? SecureItemOwnership.Mdbx)?.databaseId == scope.databaseId
    }
}
