package takagi.ru.monica.ui.password

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.PasswordGroupingConfig
import takagi.ru.monica.viewmodel.CategoryFilter

class PasswordAggregateInteractionPerformanceTest {

    @Test
    fun `content type selection filters prebuilt items without copying the unfiltered list`() {
        val note = item("note:1", PasswordPageContentType.NOTE)
        val passkey = item("passkey:2", PasswordPageContentType.PASSKEY)
        val items = listOf(note, passkey)

        assertSame(items, filterPasswordAggregateItemsByContentTypes(items, emptySet()))
        assertEquals(
            listOf(note),
            filterPasswordAggregateItemsByContentTypes(
                items = items,
                selectedTypes = setOf(PasswordPageContentType.NOTE),
            )
        )
    }

    @Test
    fun `retained aggregate snapshot survives detail composition and clears on lock`() {
        val state = PasswordAggregateRetainedState()
        val items = listOf(item("note:1", PasswordPageContentType.NOTE))
        val key = PasswordAggregateSnapshotKey(
            displayedContentTypes = setOf(PasswordPageContentType.NOTE),
            searchQuery = "",
            categoryFilter = CategoryFilter.All,
            localCategoryIdsInScope = emptySet(),
        )

        assertFalse(state.seed(key).hasSnapshot)
        val generation = state.currentGeneration()
        assertTrue(state.updateIfCurrent(generation, key, items))

        val retained = state.seed(key)
        assertTrue(retained.hasSnapshot)
        assertSame(items, retained.items)
        assertFalse(
            state.seed(key.copy(searchQuery = "different query")).hasSnapshot
        )

        state.clear()
        assertFalse(state.updateIfCurrent(generation, key, items))
        assertFalse(state.seed(key).hasSnapshot)
        assertEquals(emptyList<PasswordAggregateListItemUi>(), state.seed(key).items)
    }

    @Test
    fun `retained password grouping snapshot survives dock removal and clears on lock`() {
        val state = PasswordAggregateRetainedState()
        val first = password(1L, "github.com")
        val second = password(2L, "github.com")
        val config = PasswordGroupingConfig(
            isLocalOnlyView = false,
            effectiveStackCardMode = StackCardMode.AUTO,
            effectiveGroupMode = "website",
            websiteStackMatchMode = "domain",
            effectiveNoStackEntryIds = emptySet(),
            effectiveManualStackGroupByEntryId = emptyMap(),
            untitledLabel = "Untitled",
        )
        val key = PasswordGroupingSnapshotKey(
            sourceEntries = listOf(first, second),
            config = config,
        )
        val groups = mapOf("github.com" to listOf(first, second))

        assertFalse(state.groupingSeed(key).hasSnapshot)
        val generation = state.currentGeneration()
        assertTrue(state.updateGroupingIfCurrent(generation, key, groups))
        assertSame(groups, state.groupingSeed(key).groups)
        assertFalse(
            state.groupingSeed(
                key.copy(config = config.copy(effectiveGroupMode = "title"))
            ).hasSnapshot
        )

        state.clear()
        assertFalse(state.updateGroupingIfCurrent(generation, key, groups))
        assertFalse(state.groupingSeed(key).hasSnapshot)
    }

    @Test
    fun `aggregate type changes stay outside the background full build inputs`() {
        val source = source("ui/password/PasswordListContentSupport.kt")
        val fullBuild = source
            .substringAfter("val aggregateAllVisibleItemsAsync = rememberPasswordAggregateAsyncItems(")
            .substringBefore("val aggregateVisibleItems = remember(")

        assertTrue(fullBuild.contains("withContext(Dispatchers.Default)"))
        assertTrue(fullBuild.contains("buildPasswordAggregateItems("))
        assertFalse(fullBuild.contains("aggregateContentTypeFilterTypes"))
        assertTrue(source.contains("var value by remember(stateKey)"))
    }

    @Test
    fun `main back stack view model owns snapshot and password lock clears it`() {
        val source = source("ui/password/PasswordListContent.kt")
        val support = source("ui/password/PasswordListContentSupport.kt")

        assertTrue(
            source.contains(
                "PasswordAggregateRetainedStateViewModel = viewModel()"
            )
        )
        assertTrue(
            source.contains(
                "aggregateRetainedStateViewModel.retainedState.clear()"
            )
        )
        assertTrue(
            source.contains(
                "retainedState = aggregateRetainedStateViewModel.retainedState"
            )
        )
        assertTrue(support.contains("retainedState.updateIfCurrent("))
    }

    @Test
    fun `aggregate source state flows provide their retained value on return`() {
        val support = source("ui/password/PasswordListContentSupport.kt")
        val billing = source("viewmodel/BillingAddressViewModel.kt")

        assertTrue(support.contains("allCards?.collectAsState()"))
        assertTrue(support.contains("allDocuments?.collectAsState()"))
        assertTrue(support.contains("allBillingAddresses?.collectAsState()"))
        assertTrue(support.contains("allNotes?.collectAsState()"))
        assertTrue(
            billing.contains("val allBillingAddresses: StateFlow<List<SecureItem>>")
        )
    }

    private fun item(
        key: String,
        type: PasswordPageContentType,
    ): PasswordAggregateListItemUi = PasswordAggregateListItemUi(
        key = key,
        entry = PasswordEntry(
            id = key.hashCode().toLong(),
            title = key,
            website = "",
            username = "",
            password = "",
        ),
        type = type,
        badgeText = type.name,
        badgeColor = Color.Blue,
        sortTime = 1L,
    )

    private fun password(id: Long, website: String): PasswordEntry = PasswordEntry(
        id = id,
        title = website,
        website = website,
        username = "user$id",
        password = "password$id",
        sortOrder = id.toInt(),
    )

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/$relativePath"
        ).readText()
    }
}
