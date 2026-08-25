package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.utils.KeePassNativeProjectionBundleBuilder
import takagi.ru.monica.utils.KeePassNativeProjectionBundleCache

class KeePassNativeSessionTest {
    @Test
    fun `indexes duplicate sibling paths by stable database and group UUID identity`() {
        val firstGroupUuid = UUID.randomUUID()
        val secondGroupUuid = UUID.randomUUID()
        val firstEntryUuid = UUID.randomUUID()
        val secondEntryUuid = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = firstGroupUuid,
                        name = "Duplicate",
                        entries = listOf(entry(firstEntryUuid, "First"))
                    ),
                    Group(
                        uuid = secondGroupUuid,
                        name = "Duplicate",
                        entries = listOf(entry(secondEntryUuid, "Second"))
                    )
                )
            )
        }

        val session = KeePassNativeSessionBuilder.build(
            databaseId = 42L,
            sourceRevision = revision("revision-a"),
            database = database,
            pathKeyBuilder = ::testPath
        )

        assertEquals(2, session.groupsByLegacyPath.getValue("Duplicate").size)
        assertTrue(
            session.groupsByIdentity.containsKey(
                KeePassNativeGroupIdentity(42L, firstGroupUuid)
            )
        )
        assertTrue(
            session.groupsByIdentity.containsKey(
                KeePassNativeGroupIdentity(42L, secondGroupUuid)
            )
        )
        assertEquals(
            firstGroupUuid,
            session.entriesByIdentity
                .getValue(KeePassNativeEntryIdentity(42L, firstEntryUuid))
                .single()
                .parentGroup.groupUuid
        )
        assertEquals(
            secondGroupUuid,
            session.entriesByIdentity
                .getValue(KeePassNativeEntryIdentity(42L, secondEntryUuid))
                .single()
                .parentGroup.groupUuid
        )
    }

    @Test
    fun `retains duplicate UUID evidence instead of silently overwriting native nodes`() {
        val duplicateEntryUuid = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = UUID.randomUUID(),
                        name = "One",
                        entries = listOf(entry(duplicateEntryUuid, "One"))
                    ),
                    Group(
                        uuid = UUID.randomUUID(),
                        name = "Two",
                        entries = listOf(entry(duplicateEntryUuid, "Two"))
                    )
                )
            )
        }

        val session = KeePassNativeSessionBuilder.build(
            databaseId = 7L,
            sourceRevision = revision("revision-b"),
            database = database,
            pathKeyBuilder = ::testPath
        )

        val identity = KeePassNativeEntryIdentity(7L, duplicateEntryUuid)
        assertEquals(2, session.entriesByIdentity.getValue(identity).size)
        assertTrue(identity in session.duplicateEntryIdentities)
    }

    @Test
    fun `session cache reuses same revision and rebuilds after revision change or invalidation`() {
        var buildCount = 0
        val cache = KeePassNativeSessionCache { databaseId, sourceRevision, database ->
            buildCount++
            KeePassNativeSessionBuilder.build(
                databaseId = databaseId,
                sourceRevision = sourceRevision,
                database = database,
                pathKeyBuilder = ::testPath
            )
        }
        val database = database()
        val firstRevision = revision("revision-one")
        val secondRevision = revision("revision-two")

        val first = cache.getOrCreate(1L, firstRevision, database)
        val reused = cache.getOrCreate(1L, firstRevision, database)
        val changed = cache.getOrCreate(1L, secondRevision, database)
        cache.invalidate(1L)
        val rebuilt = cache.getOrCreate(1L, secondRevision, database)

        assertSame(first, reused)
        assertNotSame(first, changed)
        assertNotSame(changed, rebuilt)
        assertEquals(3, buildCount)
    }

    @Test
    fun `deferred session avoids rebuilding the complete native index until first use`() {
        var buildCount = 0
        val cache = KeePassNativeSessionCache { databaseId, sourceRevision, database ->
            buildCount++
            KeePassNativeSessionBuilder.build(
                databaseId = databaseId,
                sourceRevision = sourceRevision,
                database = database,
                pathKeyBuilder = ::testPath
            )
        }
        val database = database()
        val revision = revision("session-deferred")

        val deferred = cache.deferred(12L, revision, database)

        assertEquals(0, buildCount)
        val first = deferred.value
        val reused = deferred.value
        assertSame(first, reused)
        assertEquals(1, buildCount)
    }

    @Test
    fun `session exposes raw KDBX as source of truth and immutable revision token`() {
        val database = database()
        val revision = revision("raw-source")

        val session = KeePassNativeSessionBuilder.build(
            databaseId = 99L,
            sourceRevision = revision,
            database = database,
            pathKeyBuilder = ::testPath
        )

        assertSame(database, session.database)
        assertEquals(revision.sha256, session.revisionToken)
        assertEquals(revision, session.sourceRevision)
    }

    @Test
    fun `projection bundle cache reuses one traversal and reference context per session revision`() {
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = UUID.randomUUID(),
                        name = "Accounts",
                        entries = listOf(entryWithReference(UUID.randomUUID(), "GitHub"))
                    )
                )
            )
        }
        var buildCount = 0
        val cache = KeePassNativeProjectionBundleCache { session ->
            buildCount++
            KeePassNativeProjectionBundleBuilder.build(session)
        }
        val firstSession = KeePassNativeSessionBuilder.build(
            databaseId = 5L,
            sourceRevision = revision("projection-a"),
            database = database,
            pathKeyBuilder = ::testPath
        )
        val secondSession = KeePassNativeSessionBuilder.build(
            databaseId = 5L,
            sourceRevision = revision("projection-b"),
            database = database,
            pathKeyBuilder = ::testPath
        )

        val first = cache.getOrCreate(firstSession)
        val reused = cache.getOrCreate(firstSession)
        val changed = cache.getOrCreate(secondSession)

        assertSame(first, reused)
        assertNotSame(first, changed)
        assertEquals(2, buildCount)
        assertEquals(listOf("GitHub"), first.entries.map { it.entry.fields.getValue("Title").content })
        assertEquals(listOf("Accounts"), first.groups(includeRecycleBin = false).map { it.name })
        assertSame(first.entries.single().entry, first.resolutionContext?.entries?.single())
    }

    @Test
    fun `entries without reference fields do not build the entry reference index`() {
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = UUID.randomUUID(),
                        name = "Accounts",
                        entries = listOf(entry(UUID.randomUUID(), "GitHub"))
                    )
                )
            )
        }
        val session = KeePassNativeSessionBuilder.build(
            databaseId = 6L,
            sourceRevision = revision("projection-groups-only"),
            database = database,
            pathKeyBuilder = ::testPath
        )
        var referenceBuildCount = 0
        val bundle = KeePassNativeProjectionBundleBuilder.build(session) { entries ->
            referenceBuildCount++
            takagi.ru.monica.utils.KeePassFieldReferenceResolver.buildContext(entries)
        }

        assertEquals(0, referenceBuildCount)
        assertEquals(listOf("Accounts"), bundle.groups(includeRecycleBin = false).map { it.name })
        assertEquals(0, referenceBuildCount)
        assertEquals(listOf("GitHub"), bundle.entries.map { it.entry.fields.getValue("Title").content })
        assertEquals(0, referenceBuildCount)
        assertNull(bundle.resolutionContext)
        assertEquals(0, referenceBuildCount)
    }

    @Test
    fun `deferred projection bundle does not rebuild the complete projection during save`() {
        var buildCount = 0
        val cache = KeePassNativeProjectionBundleCache { session ->
            buildCount++
            KeePassNativeProjectionBundleBuilder.build(session)
        }
        val session = KeePassNativeSessionBuilder.build(
            databaseId = 8L,
            sourceRevision = revision("projection-deferred"),
            database = database(),
            pathKeyBuilder = ::testPath
        )

        val deferred = cache.deferred(session)

        assertEquals(0, buildCount)
        val first = deferred.value
        val reused = deferred.value
        assertSame(first, reused)
        assertEquals(1, buildCount)
    }

    private fun database(): KeePassDatabase {
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "Monica native session test", name = "Session"),
            credentials = Credentials.from(EncryptedValue.fromString("password"))
        )
    }

    private fun entry(uuid: UUID, title: String): Entry {
        return Entry(
            uuid = uuid,
            fields = EntryFields.of("Title" to EntryValue.Plain(title))
        )
    }

    private fun entryWithReference(uuid: UUID, title: String): Entry {
        return Entry(
            uuid = uuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain(title),
                "Notes" to EntryValue.Plain("{REF:T@I:$uuid}")
            )
        )
    }

    private fun revision(value: String): KeePassSourceRevision {
        return KeePassSourceRevision(sha256 = value, sizeBytes = value.length.toLong())
    }

    private fun testPath(parent: String?, name: String): String {
        return if (parent.isNullOrBlank()) name else "$parent/$name"
    }
}
