package takagi.ru.monica.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object MdbxMigrationVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    fun entryErrors(
        plan: MdbxMigrationPlan,
        targetFolderIds: Map<String, String>,
        actualEntries: List<MdbxStoredVaultEntry>
    ): List<String> {
        val expected = plan.entries.associate { entryPlan ->
            val targetFolderId = entryPlan.sourceFolderId?.let(targetFolderIds::get)
            val entry = MdbxMigrationEntryMapper.rewrite(entryPlan, targetFolderId)
            entry.entryId to ComparableEntry.from(entry, json)
        }
        val actual = actualEntries.associate { entry ->
            entry.entryId to ComparableEntry.from(entry, json)
        }
        return buildList {
            (expected.keys - actual.keys).sorted().forEach { add("missing entry:$it") }
            (actual.keys - expected.keys).sorted().forEach { add("unexpected entry:$it") }
            (expected.keys intersect actual.keys).sorted().forEach { entryId ->
                if (expected[entryId] != actual[entryId]) add("entry mismatch:$entryId")
            }
        }
    }

    fun folderErrors(
        plan: MdbxMigrationPlan,
        targetFolderIds: Map<String, String>,
        actualFolders: List<MdbxStoredFolderEntry>
    ): List<String> {
        val expected = plan.folders.associate { folder ->
            targetFolderIds.getValue(folder.sourceFolderId) to ComparableFolder(
                name = folder.targetDisplayName,
                parentFolderId = folder.sourceParentFolderId
                    .normalizedMigrationParentId()
                    ?.let(targetFolderIds::get)
            )
        }
        val actual = actualFolders.associate { folder ->
            folder.folderId to ComparableFolder(
                name = folder.name,
                parentFolderId = folder.parentFolderId.normalizedMigrationParentId()
            )
        }
        return buildList {
            (expected.keys - actual.keys).sorted().forEach { add("missing folder:$it") }
            (actual.keys - expected.keys).sorted().forEach { add("unexpected folder:$it") }
            (expected.keys intersect actual.keys).sorted().forEach { folderId ->
                if (expected[folderId] != actual[folderId]) add("folder mismatch:$folderId")
            }
        }
    }

    private data class ComparableFolder(
        val name: String,
        val parentFolderId: String?
    )

    private fun String?.normalizedMigrationParentId(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value.takeUnless { it.equals("root", ignoreCase = true) }
    }

    private data class ComparableEntry(
        val type: String,
        val title: String,
        val payload: JsonElement?,
        val deleted: Boolean
    ) {
        companion object {
            fun from(entry: MdbxStoredVaultEntry, json: Json): ComparableEntry = ComparableEntry(
                type = entry.entryType,
                title = entry.title,
                payload = runCatching { json.parseToJsonElement(entry.payloadJson) }.getOrNull(),
                deleted = entry.deleted
            )
        }
    }
}
