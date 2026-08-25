package takagi.ru.monica.keepass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class KeePassFolderDragPolicyTest {
    @Test
    fun `all selected folders must be valid for a batch drop`() {
        val sourceOne = UUID.randomUUID()
        val sourceTwo = UUID.randomUUID()
        val childOfOne = UUID.randomUUID()
        val target = UUID.randomUUID()
        val browser = snapshotWithDescendant(
            sourceOne = KeePassNativeGroupIdentity(1L, sourceOne),
            sourceTwo = KeePassNativeGroupIdentity(1L, sourceTwo),
            childOfOne = KeePassNativeGroupIdentity(1L, childOfOne),
            target = KeePassNativeGroupIdentity(1L, target),
        )

        val sources = setOf(
            KeePassNativeGroupIdentity(1L, sourceOne),
            KeePassNativeGroupIdentity(1L, sourceTwo),
        )

        assertTrue(sources.all { source ->
            KeePassFolderDragPolicy.canDrop(source, KeePassNativeGroupIdentity(1L, target), browser)
        })
        assertFalse(sources.all { source ->
            KeePassFolderDragPolicy.canDrop(source, KeePassNativeGroupIdentity(1L, childOfOne), browser)
        })
        assertTrue(
            KeePassFolderDragPolicy.canBatchDrop(
                sources,
                KeePassNativeGroupIdentity(1L, target),
                browser,
            )
        )
        assertFalse(
            KeePassFolderDragPolicy.canBatchDrop(
                sources + KeePassNativeGroupIdentity(1L, childOfOne),
                KeePassNativeGroupIdentity(1L, target),
                browser,
            )
        )
    }

    private fun snapshotWithDescendant(
        sourceOne: KeePassNativeGroupIdentity,
        sourceTwo: KeePassNativeGroupIdentity,
        childOfOne: KeePassNativeGroupIdentity,
        target: KeePassNativeGroupIdentity,
    ): KeePassNativeBrowserSnapshot {
        fun record(
            identity: KeePassNativeGroupIdentity,
            parent: KeePassNativeGroupIdentity?,
            children: List<KeePassNativeGroupIdentity> = emptyList(),
        ) = KeePassNativeGroupRecord(
            identity = identity,
            occurrenceIndex = 0,
            name = identity.groupUuid.toString(),
            notes = "",
            parentGroup = parent,
            legacyPath = identity.groupUuid.toString(),
            depth = if (parent == null) 0 else 1,
            isInRecycleBin = false,
            icon = app.keemobile.kotpass.constants.PredefinedIcon.Folder,
            customIconUuid = null,
            customIcon = null,
            times = null,
            expanded = true,
            defaultAutoTypeSequence = null,
            enableAutoType = app.keemobile.kotpass.constants.GroupOverride.Inherit,
            enableSearching = app.keemobile.kotpass.constants.GroupOverride.Inherit,
            tags = emptyList(),
            customData = emptyMap(),
            childGroups = children,
            childEntries = emptyList(),
            nativeGroup = app.keemobile.kotpass.models.Group(
                uuid = identity.groupUuid,
                name = identity.groupUuid.toString(),
            ),
        )
        val records = listOf(
            record(sourceOne, null, listOf(childOfOne)),
            record(sourceTwo, null),
            record(childOfOne, sourceOne),
            record(target, null),
        )
        return KeePassNativeBrowserSnapshot(
            databaseId = 1L,
            sourceRevision = KeePassSourceRevision("test", 0L),
            rootGroup = records.first(),
            groups = records,
            entries = emptyList(),
            groupsByIdentity = records.groupBy { it.identity },
            entriesByIdentity = emptyMap(),
            groupsByLegacyPath = emptyMap(),
        )
    }
    @Test
    fun rejectsSelfAndDescendantTargets() {
        val source = KeePassNativeGroupIdentity(1, java.util.UUID.randomUUID())
        val child = KeePassNativeGroupIdentity(1, java.util.UUID.randomUUID())
        val other = KeePassNativeGroupIdentity(1, java.util.UUID.randomUUID())
        val browser = snapshot(source, child, other)

        assertFalse(KeePassFolderDragPolicy.canDrop(source, source, browser))
        assertFalse(KeePassFolderDragPolicy.canDrop(source, child, browser))
        assertTrue(KeePassFolderDragPolicy.canDrop(source, other, browser))
    }

    private fun snapshot(
        source: KeePassNativeGroupIdentity,
        child: KeePassNativeGroupIdentity,
        other: KeePassNativeGroupIdentity,
    ): KeePassNativeBrowserSnapshot {
        fun group(
            identity: KeePassNativeGroupIdentity,
            parent: KeePassNativeGroupIdentity?,
            children: List<KeePassNativeGroupIdentity> = emptyList(),
        ) = KeePassNativeGroupRecord(
            identity = identity,
            occurrenceIndex = 0,
            name = identity.groupUuid.toString(),
            notes = "",
            parentGroup = parent,
            legacyPath = identity.groupUuid.toString(),
            depth = if (parent == null) 0 else 1,
            isInRecycleBin = false,
            icon = app.keemobile.kotpass.constants.PredefinedIcon.Folder,
            customIconUuid = null,
            customIcon = null,
            times = null,
            expanded = true,
            defaultAutoTypeSequence = null,
            enableAutoType = app.keemobile.kotpass.constants.GroupOverride.Inherit,
            enableSearching = app.keemobile.kotpass.constants.GroupOverride.Inherit,
            tags = emptyList(),
            customData = emptyMap(),
            childGroups = children,
            childEntries = emptyList(),
            nativeGroup = app.keemobile.kotpass.models.Group(
                uuid = identity.groupUuid,
                name = identity.groupUuid.toString(),
            ),
        )
        val root = group(source, null, listOf(child))
        val childRecord = group(child, source)
        val otherRecord = group(other, null)
        val groups = listOf(root, childRecord, otherRecord)
        return KeePassNativeBrowserSnapshot(
            databaseId = 1,
            sourceRevision = KeePassSourceRevision("test", 0L),
            rootGroup = root,
            groups = groups,
            entries = emptyList(),
            groupsByIdentity = groups.groupBy { it.identity },
            entriesByIdentity = emptyMap(),
            groupsByLegacyPath = emptyMap(),
        )
    }
}
