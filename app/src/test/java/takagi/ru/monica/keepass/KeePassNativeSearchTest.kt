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
import app.keemobile.kotpass.models.TimeData
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeSearchTest {
    private val now = Instant.parse("2026-08-17T12:00:00Z")

    @Test
    fun `search covers selected fields while protected values require explicit opt in`() {
        val entry = Entry(
            uuid = UUID.randomUUID(),
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("GitHub"),
                "UserName" to EntryValue.Plain("OctoCat"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("Hidden42")),
                "Recovery Code" to EntryValue.Plain("custom-value")
            ),
            tags = listOf("work")
        )
        val browser = browser(database().modifyParentGroup { copy(entries = listOf(entry)) })

        assertEquals(1, search(browser, "github").entries.size)
        assertEquals(1, search(browser, "custom-value").entries.size)
        assertTrue(search(browser, "Hidden42").entries.isEmpty())
        assertEquals(
            1,
            search(
                browser,
                "Hidden42",
                fields = setOf(KeePassNativeSearchField.PASSWORD),
                searchProtectedValues = true
            ).entries.size
        )
    }

    @Test
    fun `group UUID scope isolates duplicate same-path groups`() {
        val firstGroupUuid = UUID.randomUUID()
        val secondGroupUuid = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = firstGroupUuid,
                        name = "Duplicate",
                        entries = listOf(entry("First match"))
                    ),
                    Group(
                        uuid = secondGroupUuid,
                        name = "Duplicate",
                        entries = listOf(entry("Second match"))
                    )
                )
            )
        }
        val browser = browser(database)

        val result = search(
            browser = browser,
            query = "match",
            groupScope = KeePassNativeGroupIdentity(7L, secondGroupUuid)
        )

        assertEquals(listOf("Second match"), result.entries.map { it.title })
    }

    @Test
    fun `regex case expiry recycle bin and template options are deterministic`() {
        val recycleUuid = UUID.randomUUID()
        val active = entry("Prod-123")
        val expired = entry("Prod-999").copy(
            times = TimeData(
                creationTime = now.minusSeconds(3_600),
                lastAccessTime = now.minusSeconds(1_800),
                lastModificationTime = now.minusSeconds(1_200),
                locationChanged = now.minusSeconds(600),
                expiryTime = now.minusSeconds(60),
                expires = true,
                usageCount = 0
            )
        )
        val template = entry("Prod-Template", "_etm_template" to EntryValue.Plain("1"))
        val trashed = entry("Prod-Trash")
        val base = database().modifyParentGroup {
            copy(
                entries = listOf(active, expired, template),
                groups = listOf(Group(uuid = recycleUuid, name = "Recycle Bin", entries = listOf(trashed)))
            )
        }
        val database = when (base) {
            is KeePassDatabase.Ver4x -> base.copy(
                content = base.content.copy(
                    meta = base.content.meta.copy(recycleBinEnabled = true, recycleBinUuid = recycleUuid)
                )
            )
            is KeePassDatabase.Ver3x -> base.copy(
                content = base.content.copy(
                    meta = base.content.meta.copy(recycleBinEnabled = true, recycleBinUuid = recycleUuid)
                )
            )
        }
        val browser = browser(database)

        val result = KeePassNativeSearch.search(
            browser,
            KeePassNativeSearchOptions(
                query = "^Prod-[0-9]{3}$",
                useRegex = true,
                caseSensitive = true,
                includeExpired = false,
                includeRecycleBin = false,
                includeTemplates = false,
                fields = setOf(KeePassNativeSearchField.TITLE)
            ),
            now = now
        )

        assertEquals(listOf("Prod-123"), result.entries.map { it.title })
        assertEquals(1, KeePassNativeSearch.search(
            browser,
            KeePassNativeSearchOptions(
                query = "prod-trash",
                includeRecycleBin = true,
                fields = setOf(KeePassNativeSearchField.TITLE)
            ),
            now
        ).entries.size)
        assertEquals(1, KeePassNativeSearch.search(
            browser,
            KeePassNativeSearchOptions(
                query = "prod-template",
                includeTemplates = true,
                fields = setOf(KeePassNativeSearchField.TITLE)
            ),
            now
        ).entries.size)
    }

    @Test
    fun `invalid regular expression reports an error without crashing`() {
        val browser = browser(database().modifyParentGroup { copy(entries = listOf(entry("Test"))) })

        val result = KeePassNativeSearch.search(
            browser,
            KeePassNativeSearchOptions(query = "[", useRegex = true),
            now
        )

        assertTrue(result.entries.isEmpty())
        assertNotNull(result.error)
    }

    private fun search(
        browser: KeePassNativeBrowserSnapshot,
        query: String,
        fields: Set<KeePassNativeSearchField> = KeePassNativeSearchOptions.DEFAULT_FIELDS,
        searchProtectedValues: Boolean = false,
        groupScope: KeePassNativeGroupIdentity? = null
    ): KeePassNativeSearchResult {
        return KeePassNativeSearch.search(
            browser,
            KeePassNativeSearchOptions(
                query = query,
                fields = fields,
                searchProtectedValues = searchProtectedValues,
                groupScope = groupScope
            ),
            now
        )
    }

    private fun browser(database: KeePassDatabase): KeePassNativeBrowserSnapshot {
        val session = KeePassNativeSessionBuilder.build(
            databaseId = 7L,
            sourceRevision = KeePassSourceRevision("search", 1),
            database = database,
            pathKeyBuilder = { parent, name -> if (parent.isNullOrBlank()) name else "$parent/$name" }
        )
        return KeePassNativeBrowserBuilder.build(session)
    }

    private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica native search test", name = "Search"),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun entry(title: String, vararg fields: Pair<String, EntryValue>): Entry = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.of("Title" to EntryValue.Plain(title), *fields)
    )
}
