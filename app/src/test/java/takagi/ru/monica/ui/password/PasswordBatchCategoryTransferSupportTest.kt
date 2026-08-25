package takagi.ru.monica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.components.UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget

class PasswordBatchCategoryTransferSupportTest {

    @Test
    fun crossDatabaseClassifiedEntryOffersPreservation() {
        val entry = password(
            keepassDatabaseId = 10,
            keepassGroupPath = "Work/Cloud"
        )

        assertTrue(
            shouldOfferPasswordBatchCategoryPreservation(
                entries = listOf(entry),
                target = UnifiedMoveCategoryTarget.MdbxDatabaseTarget(20)
            )
        )
    }

    @Test
    fun sameDatabaseFolderMoveKeepsExistingDirectSelectionBehavior() {
        val entry = password(
            mdbxDatabaseId = 20,
            mdbxFolderId = "source-folder"
        )

        assertFalse(
            shouldOfferPasswordBatchCategoryPreservation(
                entries = listOf(entry),
                target = UnifiedMoveCategoryTarget.MdbxFolderTarget(20, "target-folder")
            )
        )
    }

    @Test
    fun unclassifiedEntryDoesNotAddSecondDialog() {
        assertFalse(
            shouldOfferPasswordBatchCategoryPreservation(
                entries = listOf(password()),
                target = UnifiedMoveCategoryTarget.KeePassDatabaseTarget(30)
            )
        )
    }

    @Test
    fun archiveTargetNeverOffersCategoryPreservation() {
        val entry = password(categoryId = 9)

        assertFalse(
            shouldOfferPasswordBatchCategoryPreservation(
                entries = listOf(entry),
                target = UnifiedMoveCategoryTarget.MonicaCategory(
                    UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
                )
            )
        )
    }

    @Test
    fun mdbxFolderPathIsResolvedFromRootToLeaf() {
        val folders = listOf(
            folder(id = "work", parentId = "root", name = "工作"),
            folder(id = "cloud", parentId = "work", name = "云服务"),
            folder(id = "mail", parentId = "cloud", name = "邮箱")
        )

        assertTrue(
            resolveMdbxFolderSegments("mail", folders) == listOf("工作", "云服务", "邮箱")
        )
    }

    @Test
    fun mdbxFolderCyclesStopWithoutLooping() {
        val folders = listOf(
            folder(id = "one", parentId = "two", name = "一"),
            folder(id = "two", parentId = "one", name = "二")
        )

        assertTrue(resolveMdbxFolderSegments("one", folders).size == 2)
    }

    @Test
    fun entriesAreGroupedByResolvedTargetWhileUnclassifiedEntriesUseSelectedTarget() {
        val selectedTarget = UnifiedMoveCategoryTarget.MdbxDatabaseTarget(20)
        val preservedTarget = UnifiedMoveCategoryTarget.MdbxFolderTarget(20, "work")
        val classified = password().copy(id = 1)
        val unclassified = password().copy(id = 2)

        val groups = groupPasswordBatchEntriesByTarget(
            entries = listOf(classified, unclassified),
            selectedTarget = selectedTarget,
            targetOverrides = mapOf(classified.id to preservedTarget)
        ).toMap()

        assertTrue(groups[preservedTarget] == listOf(classified))
        assertTrue(groups[selectedTarget] == listOf(unclassified))
    }

    private fun password(
        categoryId: Long? = null,
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null
    ) = PasswordEntry(
        id = 1,
        title = "Example",
        website = "https://example.com",
        username = "user",
        password = "encrypted",
        categoryId = categoryId,
        keepassDatabaseId = keepassDatabaseId,
        keepassGroupPath = keepassGroupPath,
        mdbxDatabaseId = mdbxDatabaseId,
        mdbxFolderId = mdbxFolderId
    )

    private fun folder(id: String, parentId: String?, name: String) = MdbxStoredFolderEntry(
        folderId = id,
        parentFolderId = parentId,
        name = name,
        pathKey = id,
        objectClock = 0
    )
}
