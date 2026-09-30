package takagi.ru.monica.ui.password

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class GeneratorFrequentValuesTest {
    @Test fun presetsExcludedBeforeTakingFiveAndFrequencyWins() {
        val values = List(10) { "preset" } + List(4) { "popular" } + listOf("f", "e", "d", "c", "b", "a")
        assertEquals(listOf("popular", "a", "b", "c", "d"), generatorFrequentValues(values, setOf("preset")))
    }
    @Test fun exactCaseAndWhitespacePreserved() {
        assertEquals(listOf(" a ", "A", "a"), generatorFrequentValues(listOf("A", "a", " a ", "", "  "), emptySet()))
    }
    @Test fun stableOrderDoesNotDependOnDatabaseOrder() {
        val values = listOf("c", "b", "a", "a")
        assertEquals(generatorFrequentValues(values, emptySet()), generatorFrequentValues(values.reversed(), emptySet()))
        assertEquals(emptyList<String>(), generatorFrequentValues(values, emptySet(), 0))
    }
    @Test fun excludesTrashArchiveUnavailableAndConflictingSources() {
        val e = PasswordEntry(title="fixture", website="", username="sample", password="ciphertext")
        val sources = setOf("local", "mdbx:3", "keepass:2", "bitwarden:4")
        assertTrue(generatorEntryAccessible(e, sources))
        assertTrue(generatorEntryAccessible(e.copy(mdbxDatabaseId=3), sources))
        assertTrue(generatorEntryAccessible(e.copy(keepassDatabaseId=2), sources))
        assertTrue(generatorEntryAccessible(e.copy(bitwardenVaultId=4), sources))
        assertFalse(generatorEntryAccessible(e.copy(isDeleted=true), sources))
        assertFalse(generatorEntryAccessible(e.copy(isArchived=true), sources))
        assertFalse(generatorEntryAccessible(e.copy(bitwardenVaultId=9), sources))
        assertFalse(generatorEntryAccessible(e.copy(keepassDatabaseId=9), sources))
        assertFalse(generatorEntryAccessible(e.copy(mdbxDatabaseId=9), sources))
        assertFalse(generatorEntryAccessible(e.copy(mdbxDatabaseId=3, keepassDatabaseId=2), sources))
    }
}
