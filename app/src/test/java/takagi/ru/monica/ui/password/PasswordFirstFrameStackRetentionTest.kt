package takagi.ru.monica.ui.password

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.PasswordGroupingConfig
import takagi.ru.monica.ui.resolvePasswordGroupsForRender
import takagi.ru.monica.ui.resolvePasswordListInitialRenderState

class PasswordFirstFrameStackRetentionTest {

    @Test
    fun `transient empty stack metadata reuses the last valid grouped model`() {
        val state = PasswordAggregateRetainedState()
        val first = password(1L, "github.com")
        val second = password(2L, "github.com")
        val settledConfig = groupingConfig(
            manualStacks = mapOf(first.id to "github", second.id to "github")
        )
        val transientConfig = settledConfig.copy(
            effectiveManualStackGroupByEntryId = emptyMap(),
            effectiveNoStackEntryIds = emptySet(),
        )
        val settledKey = PasswordGroupingSnapshotKey(
            sourceEntries = listOf(first, second),
            config = settledConfig,
        )
        val transientKey = settledKey.copy(config = transientConfig)
        val groups = mapOf("manual_stack:github" to listOf(first, second))

        assertTrue(
            state.updateGroupingIfCurrent(
                expectedGeneration = state.currentGeneration(),
                key = settledKey,
                groups = groups,
            )
        )

        val seed = state.groupingSeed(transientKey)
        assertTrue(seed.hasSnapshot)
        assertSame(groups, seed.groups)
    }

    @Test
    fun `manual stack metadata is retained only for the same password revisions`() {
        val state = PasswordAggregateRetainedState()
        val revisions = listOf(
            PasswordGroupingEntryRevision(id = 1L, updatedAtMillis = 100L),
            PasswordGroupingEntryRevision(id = 2L, updatedAtMillis = 200L),
        )
        val metadata = PasswordManualStackMetadata(
            revisions = revisions,
            manualStackGroupByEntryId = mapOf(1L to "github", 2L to "github"),
            noStackEntryIds = emptySet(),
        )

        state.updateManualStackMetadata(metadata)

        assertSame(metadata, state.seedManualStackMetadata(revisions))
        assertNull(
            state.seedManualStackMetadata(
                revisions = revisions.mapIndexed { index, revision ->
                    if (index == 0) revision.copy(updatedAtMillis = 101L) else revision
                }
            )
        )
    }

    @Test
    fun `pending grouping never renders a one-card-per-password fallback`() {
        val result = resolvePasswordGroupsForRender(
            groupedPasswords = emptyMap(),
            hasGroupedPasswordsReadyForCurrentInputs = false,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `pending folder grouping keeps the previous complete grouped model`() {
        val first = password(1L, "github.com")
        val second = password(2L, "github.com")
        val previousGroups = mapOf("github.com" to listOf(first, second))

        val result = resolvePasswordGroupsForRender(
            groupedPasswords = previousGroups,
            hasGroupedPasswordsReadyForCurrentInputs = false,
        )

        assertSame(previousGroups, result)
    }

    @Test
    fun `completed page does not show initial loader while folder grouping is pending`() {
        val renderState = resolvePasswordListInitialRenderState(
            hasCompletedInitialPasswordListStabilization = true,
            passwordEntriesReady = true,
            allPasswordsForUiReady = true,
            categoriesReady = true,
            shouldRenderPasswordGroups = true,
            hasGroupedPasswordsReadyForCurrentInputs = false,
            visiblePasswordIds = listOf(1L, 2L),
            groupedPasswordIds = emptyList(),
            displayedContentTypes = setOf(PasswordPageContentType.PASSWORD),
            searchQuery = "",
        )

        assertFalse(renderState.isPasswordPageListModelReady)
        assertFalse(renderState.shouldGateInitialContent)
    }

    @Test
    fun `first page load still waits for its grouping model`() {
        val renderState = resolvePasswordListInitialRenderState(
            hasCompletedInitialPasswordListStabilization = false,
            passwordEntriesReady = true,
            allPasswordsForUiReady = true,
            categoriesReady = true,
            shouldRenderPasswordGroups = true,
            hasGroupedPasswordsReadyForCurrentInputs = false,
            visiblePasswordIds = listOf(1L, 2L),
            groupedPasswordIds = emptyList(),
            displayedContentTypes = setOf(PasswordPageContentType.PASSWORD),
            searchQuery = "",
        )

        assertFalse(renderState.isPasswordPageListModelReady)
        assertTrue(renderState.shouldGateInitialContent)
    }

    private fun groupingConfig(
        manualStacks: Map<Long, String> = emptyMap(),
    ): PasswordGroupingConfig = PasswordGroupingConfig(
        isLocalOnlyView = false,
        effectiveStackCardMode = StackCardMode.AUTO,
        effectiveGroupMode = "website",
        websiteStackMatchMode = "domain",
        effectiveNoStackEntryIds = emptySet(),
        effectiveManualStackGroupByEntryId = manualStacks,
        untitledLabel = "Untitled",
    )

    private fun password(id: Long, website: String): PasswordEntry = PasswordEntry(
        id = id,
        title = website,
        website = website,
        username = "user$id",
        password = "password$id",
        sortOrder = id.toInt(),
    )
}
