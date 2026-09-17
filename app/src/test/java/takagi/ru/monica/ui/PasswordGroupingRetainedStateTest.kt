package takagi.ru.monica.ui

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.password.PasswordAggregateRetainedState
import takagi.ru.monica.ui.password.PasswordGroupingSnapshotKey
import takagi.ru.monica.ui.password.StackCardMode

class PasswordGroupingRetainedStateTest {
    @Test fun onlyExactSnapshotsMaySkipGroupingAndLockClearsThem() {
        val state = PasswordAggregateRetainedState()
        val entries = listOf(PasswordEntry(id = 1, title = "Example", username = "user", website = "https://example.invalid", password = "synthetic"))
        val config = PasswordGroupingConfig(false, StackCardMode.AUTO, "title", "strict", emptySet(), emptyMap(), "Untitled")
        val key = PasswordGroupingSnapshotKey(entries, config)
        val generation = state.currentGeneration()
        assertTrue(state.updateGroupingIfCurrent(generation, key, mapOf("Example" to entries)))
        assertTrue(state.groupingSeed(key).isExactMatch)
        val changedMetadata = key.copy(config = config.copy(effectiveNoStackEntryIds = setOf(1)))
        assertTrue(state.groupingSeed(changedMetadata).hasSnapshot)
        assertFalse(state.groupingSeed(changedMetadata).isExactMatch)
        assertFalse(state.groupingSeed(key.copy(sourceEntries = entries.map { it.copy(title = "Edited") })).hasSnapshot)
        state.clear()
        assertFalse(state.groupingSeed(key).hasSnapshot)
        assertFalse(state.updateGroupingIfCurrent(generation, key, mapOf("Example" to entries)))
    }
}
