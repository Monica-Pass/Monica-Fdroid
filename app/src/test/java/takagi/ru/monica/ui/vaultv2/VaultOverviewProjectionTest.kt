package takagi.ru.monica.ui.vaultv2

import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.utils.KeePassGroupInfo

class VaultOverviewProjectionTest {
    private val sources = listOf(VaultOverviewSource("local", "Local", "Monica"),
        VaultOverviewSource("bitwarden:2", "Work", "Bitwarden"), VaultOverviewSource("keepass:3", "Private", "KeePass"))

    private fun password(id: Long, vault: Long? = null, favorite: Boolean = false) = buildVaultV2PasswordItems(listOf(
        PasswordEntry(id = id, title = "Password $id", username = "user", website = "", password = "secret",
            bitwardenVaultId = vault, isFavorite = favorite, createdAt = Date(10)),
    )).single()

    private fun secure(id: Long, type: VaultV2ItemType, vault: Long? = null, favorite: Boolean = false): VaultV2Item {
        val domainType = when (type) {
            VaultV2ItemType.BANK_CARD -> ItemType.BANK_CARD
            VaultV2ItemType.DOCUMENT -> ItemType.DOCUMENT
            VaultV2ItemType.BILLING_ADDRESS -> ItemType.BILLING_ADDRESS
            else -> ItemType.NOTE
        }
        return VaultV2Item("${type.name}:$id", type, "$type $id", "", favorite, "$id", emptyList(),
            secureItem = SecureItem(id = id, itemType = domainType, title = "$id", itemData = "secret",
                bitwardenVaultId = vault, createdAt = Date(10)))
    }

    private fun project(items: List<VaultV2Item>, scope: String = "local", config: VaultOverviewConfig = VaultOverviewConfig(),
        sourceList: List<VaultOverviewSource> = sources, usage: Map<String, VaultOverviewUsage> = emptyMap()) =
        buildVaultOverviewSnapshot(items, sourceList, scope, config, usage, emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(),
            aggregate = ::aggregateVaultOverviewKotlin)

    @Test fun walletAndItemRankingsCannotCrossAndPinsOverrideUsage() {
        val password = password(1)
        val card = secure(2, VaultV2ItemType.BANK_CARD)
        val document = secure(3, VaultV2ItemType.DOCUMENT)
        val address = secure(4, VaultV2ItemType.BILLING_ADDRESS)
        val note = secure(5, VaultV2ItemType.NOTE)
        val list = listOf(password, card, document, address, note)
        val usage = list.associate { it.overviewIdentity() to VaultOverviewUsage(it.overviewIdentity(), 50, 99) }
        val config = VaultOverviewConfig(pinnedCards = listOf(document.overviewIdentity(), password.overviewIdentity()),
            pinnedItems = listOf(note.overviewIdentity(), card.overviewIdentity()))
        val result = project(list, config = config, usage = usage)
        assertEquals(listOf(document, card, address), result.cards)
        assertEquals(listOf(note, password), result.frequentItems)
        val noCardRecommendations = project(list, config = config.copy(recommendCards = false), usage = usage)
        assertEquals(listOf(document), noCardRecommendations.cards)
        assertEquals(listOf(note, password), noCardRecommendations.frequentItems)
    }

    @Test fun scopeAndLocksApplyToEveryCountAndPreview() {
        val local = password(1, favorite = true)
        val remote = secure(2, VaultV2ItemType.BANK_CARD, vault = 2, favorite = true)
        val config = VaultOverviewConfig(pinnedItems = listOf(local.overviewIdentity()), pinnedCards = listOf(remote.overviewIdentity()))
        assertEquals(listOf(local), project(listOf(local, remote), config = config).items)
        val all = project(listOf(local, remote), scope = "all", config = config)
        assertEquals(listOf(local, remote), all.favorites)
        assertEquals(1, all.sourceCounts["bitwarden:2"])
        val locked = project(listOf(local, remote), scope = "all", config = config,
            sourceList = sources.map { if (it.key == "bitwarden:2") it.copy(locked = true) else it })
        assertEquals(listOf(local), locked.items)
        assertTrue(locked.cards.isEmpty())
        assertEquals(0, locked.sourceCounts["bitwarden:2"])
        assertEquals(0, locked.typeCounts[VaultV2ItemType.BANK_CARD])
        assertNotEquals(all.accessibleSources, locked.accessibleSources)
        assertTrue(project(listOf(local, remote), scope = "mdbx:404").items.isEmpty())
    }

    @Test fun emptyAndRenamedKeePassFoldersKeepTheirDatabaseAndUuid() {
        val local = password(1).let { it.copy(passwordEntry = it.passwordEntry!!.copy(categoryId = 7)) }
        val keepass = password(2).let { it.copy(passwordEntry = it.passwordEntry!!.copy(
            keepassDatabaseId = 3, keepassGroupPath = "Old name", keepassGroupUuid = "stable-folder")) }
        val snapshot = buildVaultOverviewSnapshot(listOf(local, keepass), sources, "all", VaultOverviewConfig(), emptyMap(), emptyMap(),
            listOf(Category(7, "Same name")), emptyList(), emptyMap(), emptyList(),
            mapOf(3L to listOf(KeePassGroupInfo("Same name", "Same name", "stable-folder"), KeePassGroupInfo("Empty", "Empty", "empty"))),
            aggregate = ::aggregateVaultOverviewKotlin)
        assertEquals(setOf("local/7", "keepass:3/stable-folder", "keepass:3/empty"), snapshot.folders.map { it.key }.toSet())
        assertEquals("Same name", snapshot.folders.first { it.key == "keepass:3/stable-folder" }.name)
        assertEquals(1, snapshot.folders.first { it.key == "keepass:3/stable-folder" }.count)
        assertEquals(0, snapshot.folders.first { it.key == "keepass:3/empty" }.count)
    }

    @Test fun topEightAreStableAndRecommendationSwitchesAreIndependent() {
        val items = (1L..20L).map(::password)
        val usage = items.associate { it.overviewIdentity() to VaultOverviewUsage(it.overviewIdentity(), 4, 99) }
        assertEquals(items.take(8), project(items, usage = usage).frequentItems)
        val pinned = items.last().overviewIdentity()
        val result = project(items, config = VaultOverviewConfig(pinnedItems = listOf(pinned), recommendItems = false), usage = usage)
        assertEquals(listOf(items.last()), result.frequentItems)
        assertTrue(result.cards.isEmpty())
    }

    @Test fun smallSnapshotsAvoidJniAndUnavailableNativeFallsBack() {
        val metadata = longArrayOf(1, 1, 0, 0, 3, 7, 0, 0, -1, 1, 0, 10, 99, 0)
        var calls = 0
        val result = aggregateVaultOverview(metadata) { calls++; null }
        assertEquals(0, calls)
        assertArrayEquals(intArrayOf(0), result.items)
        val large = LongArray(OVERVIEW_HEADER + OVERVIEW_NATIVE_THRESHOLD * OVERVIEW_ROW_WIDTH)
        metadata.copyInto(large, endIndex = OVERVIEW_HEADER)
        for (i in 0 until OVERVIEW_NATIVE_THRESHOLD) metadata.copyInto(large, OVERVIEW_HEADER + i * OVERVIEW_ROW_WIDTH, OVERVIEW_HEADER)
        val fallback = aggregateVaultOverview(large) { calls++; null }
        assertEquals(1, calls)
        assertArrayEquals(aggregateVaultOverviewKotlin(large).items, fallback.items)
        assertNull(decodeVaultOverviewAggregation(intArrayOf(1, Int.MAX_VALUE, 0, 0, 0), metadata))
        assertNull(decodeVaultOverviewAggregation(intArrayOf(2, 0, 0, 0, 0), metadata))
    }

    @Test fun removalSuppressesAutomaticRecommendationsButPreservesFavoritesCountsAndOtherDatabases() {
        val local = password(1, favorite = true)
        val remote = password(1, vault = 2, favorite = true)
        val card = secure(2, VaultV2ItemType.BANK_CARD, favorite = true)
        val rows = listOf(local, remote, card)
        val usage = rows.associate { it.overviewIdentity() to VaultOverviewUsage(it.overviewIdentity(), 500, 9999) }
        val config = VaultOverviewConfig(pinnedItems = listOf(local.overviewIdentity()))
            .removeFrequentItems(listOf(local.overviewIdentity()))
        val snapshot = project(rows, scope = "all", config = VaultOverviewConfig.decode(config.encode()), usage = usage)
        assertEquals(listOf(remote), snapshot.frequentItems)
        assertEquals(rows, snapshot.items)
        assertEquals(rows, snapshot.favorites)
        assertEquals(listOf(card), snapshot.cards)
        assertEquals(2, snapshot.typeCounts[VaultV2ItemType.PASSWORD])
        assertEquals(2, snapshot.sourceCounts["local"])
        assertEquals(listOf(local, remote), project(rows, scope = "all", usage = usage,
            config = config.togglePinnedItem(local.overviewIdentity())).frequentItems)
    }

    @Test fun removedItemsCannotReturnThroughLegacyPasswordUsage() {
        val row = password(1, favorite = true)
        val config = VaultOverviewConfig().removeFrequentItems(listOf(row.overviewIdentity()))
        val snapshot = buildVaultOverviewSnapshot(listOf(row), sources, "local", config, emptyMap(),
            mapOf(1L to PasswordQuickAccessRecord(passwordId = 1, openCount = 100, lastOpenedAt = 9999)),
            emptyList(), emptyList(), emptyMap(), emptyList(), aggregate = ::aggregateVaultOverviewKotlin)
        assertTrue(snapshot.frequentItems.isEmpty())
        assertEquals(listOf(row), snapshot.favorites)
        assertEquals(listOf(row), snapshot.items)
    }

    @Test fun repeatedScopeChangesReuseAnImmutablePreparedBatch() {
        val local = password(1)
        val work = password(2, vault = 2)
        val rows = listOf(local, work)
        val archived = listOf(local.passwordEntry!!.copy(isArchived = true), work.passwordEntry!!.copy(isArchived = true))
        val prepared = prepareVaultOverview(rows, sources, VaultOverviewConfig(), emptyMap(), emptyMap(),
            emptyList(), emptyList(), emptyMap(), archived)
        val originalBatch = prepared.metadata.copyOf()
        for (scope in listOf("local", "bitwarden:2", "all", "keepass:3", "local", "all")) {
            val result = projectVaultOverview(prepared, scope, ::aggregateVaultOverviewKotlin)
            assertEquals(project(rows, scope).items, result.items)
            assertEquals(if (scope == "all") 2 else if (scope == "keepass:3") 0 else 1, result.archiveCount)
            assertArrayEquals(originalBatch, prepared.metadata)
        }
        assertTrue(projectVaultOverview(prepared, "mdbx:404", ::aggregateVaultOverviewKotlin).items.isEmpty())
    }
}
