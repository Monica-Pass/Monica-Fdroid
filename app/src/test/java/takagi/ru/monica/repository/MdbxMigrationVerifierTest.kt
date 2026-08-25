package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType

class MdbxMigrationVerifierTest {
    @Test
    fun semanticallyEquivalentEntryAndFolderPassVerification() {
        val sourceEntry = MdbxStoredVaultEntry(
            "password:1",
            "login",
            "Example",
            """{"username":"user","mdbx_folder_id":"source"}""",
            false
        )
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = listOf(MdbxStoredFolderEntry("source", null, "Work", "work", 1L)),
            entries = listOf(sourceEntry),
            attachments = emptyList()
        )
        val mapping = mapOf("source" to "target")
        val rewritten = MdbxMigrationEntryMapper.rewrite(plan.entries.single(), "target")
        val reordered = rewritten.copy(
            payloadJson = """{"mdbx_folder_id":"target","username":"user","mdbx_migration_source_folder_id":"source","monica_entry_id":"password:1"}"""
        )

        assertTrue(MdbxMigrationVerifier.entryErrors(plan, mapping, listOf(reordered)).isEmpty())
        assertTrue(
            MdbxMigrationVerifier.folderErrors(
                plan,
                mapping,
                listOf(MdbxStoredFolderEntry("target", null, "Work", "work", 1L))
            ).isEmpty()
        )
    }

    @Test
    fun missingAndChangedEntriesAreReported() {
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = emptyList(),
            entries = listOf(MdbxStoredVaultEntry("password:1", "login", "Example", "{}", false)),
            attachments = emptyList()
        )

        assertEquals(listOf("missing entry:password:1"), MdbxMigrationVerifier.entryErrors(plan, emptyMap(), emptyList()))
        val changed = MdbxStoredVaultEntry("password:1", "login", "Changed", "{}", false)
        assertEquals(
            listOf("entry mismatch:password:1"),
            MdbxMigrationVerifier.entryErrors(plan, emptyMap(), listOf(changed))
        )
    }

    @Test
    fun folderParentMismatchIsReported() {
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = listOf(
                MdbxStoredFolderEntry("parent", null, "Parent", "/parent", 1L),
                MdbxStoredFolderEntry("child", "parent", "Child", "/parent/child", 1L)
            ),
            entries = emptyList(),
            attachments = emptyList()
        )
        val mapping = mapOf("parent" to "target-parent", "child" to "target-child")
        val actual = listOf(
            MdbxStoredFolderEntry("target-parent", null, "Parent", "/target-parent", 1L),
            MdbxStoredFolderEntry("target-child", null, "Child", "/target-child", 1L)
        )

        assertEquals(
            listOf("folder mismatch:target-child"),
            MdbxMigrationVerifier.folderErrors(plan, mapping, actual)
        )
    }

    private fun sourceDatabase() = LocalMdbxDatabase(
        id = 1L,
        name = "Source",
        filePath = "source.mdbx",
        sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
        engineType = MdbxEngineType.KOTLIN_MDBX1.name
    )
}
