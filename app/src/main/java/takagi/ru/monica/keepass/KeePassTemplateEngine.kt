package takagi.ru.monica.keepass

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import java.time.Instant
import java.util.UUID

/**
 * KeePass entry-template operations which do not depend on Android UI or Room.
 *
 * KeePass stores the template group UUID in Meta.EntryTemplatesGroup.  Monica
 * also writes a small marker field so databases created by other clients can
 * still be recognised when the metadata pointer is missing or stale.
 */
internal object KeePassTemplateEngine {
    const val TEMPLATE_GROUP_NAME = "Entry Templates"
    const val TEMPLATE_MARKER_FIELD = "_etm_template"
    const val TEMPLATE_MARKER_VALUE = "Monica"

    data class Mutation(
        val database: KeePassDatabase,
        val entryUuid: UUID,
        val templateGroupUuid: UUID,
    )

    fun templateGroupUuid(database: KeePassDatabase): UUID? {
        val metadataUuid = database.content.meta.entryTemplatesGroup
        if (metadataUuid != null && findGroup(database.content.group, metadataUuid) != null) {
            return metadataUuid
        }
        return findGroup(database.content.group) { group ->
            group.name.equals(TEMPLATE_GROUP_NAME, ignoreCase = true)
        }?.uuid
    }

    fun ensureTemplateGroup(
        database: KeePassDatabase,
        name: String = TEMPLATE_GROUP_NAME,
        now: Instant = Instant.now(),
    ): Pair<KeePassDatabase, UUID> {
        templateGroupUuid(database)?.let { uuid ->
            val repaired = if (database.content.meta.entryTemplatesGroup == uuid) {
                database
            } else {
                database.modifyContent {
                    copy(meta = meta.copy(entryTemplatesGroup = uuid, entryTemplatesGroupChanged = now))
                }
            }
            return repaired to uuid
        }

        val uuid = UUID.randomUUID()
        val templateGroup = KeePassNativeMutation { now }.initializeGroup(
            Group(
                uuid = uuid,
                name = name.trim().ifBlank { TEMPLATE_GROUP_NAME },
                icon = PredefinedIcon.Key,
            )
        )
        val updatedRoot = database.content.group.copy(
            groups = database.content.group.groups + templateGroup,
        )
        val updated = database.modifyContent {
            copy(
                group = updatedRoot,
                meta = meta.copy(
                    entryTemplatesGroup = uuid,
                    entryTemplatesGroupChanged = now,
                ),
            )
        }
        return updated to uuid
    }

    fun isTemplate(entry: Entry): Boolean = entry.fields.entries.any { (name, value) ->
        name.equals(TEMPLATE_MARKER_FIELD, ignoreCase = true) &&
            value.content.isNotBlank()
    }

    fun saveAsTemplate(
        database: KeePassDatabase,
        sourceEntryUuid: UUID,
        titleOverride: String? = null,
        now: Instant = Instant.now(),
    ): Mutation {
        val (withGroup, templateGroupUuid) = ensureTemplateGroup(database, now = now)
        val source = findEntry(withGroup.content.group, sourceEntryUuid)
            ?: throw IllegalArgumentException("KeePass template source entry not found: $sourceEntryUuid")
        val templateUuid = UUID.randomUUID()
        val template = source.copy(
            uuid = templateUuid,
            history = emptyList(),
            previousParentGroup = null,
            times = null,
            fields = source.fields
                .toMutableMap()
                .apply {
                    if (!titleOverride.isNullOrBlank()) {
                        this["Title"] = EntryValue.Plain(titleOverride.trim())
                    }
                    this[TEMPLATE_MARKER_FIELD] = EntryValue.Plain(TEMPLATE_MARKER_VALUE)
                }
                .toEntryFields(),
        ).let { KeePassNativeMutation { now }.initializeEntry(it) }
        val inserted = insertEntry(withGroup, templateGroupUuid, template)
        return Mutation(inserted, templateUuid, templateGroupUuid)
    }

    fun instantiate(
        database: KeePassDatabase,
        templateEntryUuid: UUID,
        targetGroupUuid: UUID,
        titleOverride: String? = null,
        now: Instant = Instant.now(),
    ): Mutation {
        val template = findEntry(database.content.group, templateEntryUuid)
            ?: throw IllegalArgumentException("KeePass template not found: $templateEntryUuid")
        require(isTemplate(template)) { "KeePass entry is not a template: $templateEntryUuid" }
        require(findGroup(database.content.group, targetGroupUuid) != null) {
            "KeePass target group not found: $targetGroupUuid"
        }
        val newUuid = UUID.randomUUID()
        val fields = template.fields
            .filterKeys { !it.equals(TEMPLATE_MARKER_FIELD, ignoreCase = true) }
            .toMutableMap()
            .apply {
                if (!titleOverride.isNullOrBlank()) {
                    this["Title"] = EntryValue.Plain(titleOverride.trim())
                }
            }
        val entry = KeePassNativeMutation { now }.initializeEntry(
            template.copy(
                uuid = newUuid,
                fields = fields.toEntryFields(),
                history = emptyList(),
                previousParentGroup = null,
                times = null,
            )
        )
        return Mutation(insertEntry(database, targetGroupUuid, entry), newUuid, templateGroupUuid(database)
            ?: error("KeePass template group metadata is missing"))
    }

    fun deleteTemplate(database: KeePassDatabase, templateEntryUuid: UUID): KeePassDatabase {
        val template = findEntry(database.content.group, templateEntryUuid)
            ?: throw IllegalArgumentException("KeePass template not found: $templateEntryUuid")
        require(isTemplate(template)) { "KeePass entry is not a template: $templateEntryUuid" }
        return KeePassLosslessTransfer.removeEntry(database, templateEntryUuid)
    }

    fun listTemplates(database: KeePassDatabase): List<Entry> {
        val groupUuid = templateGroupUuid(database) ?: return emptyList()
        val group = findGroup(database.content.group, groupUuid) ?: return emptyList()
        return collectEntries(group).filter(::isTemplate)
    }

    private fun Map<String, EntryValue>.toEntryFields(): EntryFields =
        EntryFields.of(*toList().toTypedArray())

    private fun insertEntry(database: KeePassDatabase, targetGroupUuid: UUID, entry: Entry): KeePassDatabase {
        require(findGroup(database.content.group, targetGroupUuid) != null) {
            "KeePass target group not found: $targetGroupUuid"
        }
        fun insert(group: Group): Group {
            if (group.uuid == targetGroupUuid) return group.copy(entries = group.entries + entry)
            return group.copy(groups = group.groups.map(::insert))
        }
        return database.modifyContent { copy(group = insert(group)) }
    }

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child -> findEntry(child, uuid)?.let { return it } }
        return null
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }

    private fun findGroup(group: Group, predicate: (Group) -> Boolean): Group? {
        if (predicate(group)) return group
        group.groups.forEach { child -> findGroup(child, predicate)?.let { return it } }
        return null
    }

    private fun collectEntries(group: Group): List<Entry> =
        group.entries + group.groups.flatMap(::collectEntries)
}
