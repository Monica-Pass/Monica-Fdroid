package takagi.ru.monica.notes.ui.model

import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.viewmodel.LoadedListState
import takagi.ru.monica.viewmodel.ParsedNoteItem

class NoteListProjectionTest {
    private fun note(id: Long, title: String = "Note $id", content: String = "Body $id", tags: List<String> = emptyList()): SecureItem {
        val (encoded, fallback) = NoteContentCodec.encode(content, tags, isMarkdown = true)
        return SecureItem(id = id, itemType = ItemType.NOTE, title = title, itemData = encoded, notes = fallback,
            createdAt = Date(0), updatedAt = Date(0))
    }

    private fun loaded(rows: List<SecureItem>) = LoadedListState(NoteSnapshotCache().prepare(rows), isReady = true)

    @Test fun unchangedNotesReuseDecodedTextAndPreviewsAcrossUpdatesAndReordering() {
        var parses = 0
        val cache = NoteSnapshotCache { item -> parses++; ParsedNoteItem(item, NoteContentCodec.decodeFromItem(item)) }
        val a = note(1)
        val b = note(2)
        val first = cache.prepare(listOf(a, b))
        val reordered = cache.prepare(listOf(b.copy(), a.copy()))
        assertSame(first[1], reordered[0])
        assertSame(first[0].uiModel, reordered[1].uiModel)
        assertEquals(2, parses)
        val changed = cache.prepare(listOf(b, note(1, content = "**Changed**"), note(3)))
        assertSame(first[1], changed[0])
        assertEquals("Changed", changed[1].uiModel.previewText)
        assertEquals(4, parses)
        cache.prepare(listOf(b))
        cache.prepare(listOf(a, b))
        assertEquals("Removed notes must not be retained indefinitely", 5, parses)
    }

    @Test fun editingAnAttachmentOrSyncMetadataInvalidatesTheCachedRow() {
        val cache = NoteSnapshotCache()
        val original = note(1, content = "Hello ![](monica-image://inline)")
            .copy(imagePaths = "[\"inline\",\"legacy\"]", bitwardenVaultId = 7, bitwardenCipherId = "note", syncStatus = "PENDING")
        val first = cache.prepare(listOf(original)).single().uiModel
        assertEquals(listOf("inline", "legacy"), first.inlineImageIds)
        assertTrue(first.hasImageAttachment)
        assertEquals(SyncStatus.PENDING, first.syncStatus)
        val second = cache.prepare(listOf(original.copy(imagePaths = "", syncStatus = "SYNCED", title = "Renamed"))).single().uiModel
        assertEquals(listOf("inline"), second.inlineImageIds)
        assertFalse(second.hasImageAttachment)
        assertEquals(SyncStatus.SYNCED, second.syncStatus)
        assertEquals("Renamed", second.title)
    }

    @Test fun scopesKeepSourceOrderAndOnlyOfferTagsFromTheSelectedDatabase() {
        val state = loaded(listOf(
            note(3, tags = listOf("外部")).copy(mdbxDatabaseId = 7),
            note(2, tags = listOf("Work", "zeta")),
            note(1, tags = listOf("alpha", "Work")),
        ))
        val projector = NoteListProjector()
        val local = projector.project(state, NoteListQuery(NoteCategoryFilter.Local))
        assertEquals(listOf(2L, 1L), local.notes.map { it.id })
        assertEquals(listOf("alpha", "Work", "zeta"), local.availableTags)
        assertEquals(listOf(3L, 2L, 1L), local.allNotes.map { it.id })
        assertSame(local, projector.project(state, local.query))
        val external = projector.project(state, NoteListQuery(NoteCategoryFilter.MdbxDatabase(7)))
        assertEquals(listOf(3L), external.items.map { it.id })
        assertEquals(listOf("外部"), external.availableTags)
        val localAgain = projector.project(state, local.query)
        assertSame(local.items, localAgain.items)
    }

    @Test fun searchMatchesTitleBodyOrTagsAndCombinesWithAnExactCaseInsensitiveTag() {
        val state = loaded(listOf(
            note(1, title = "İstanbul", tags = listOf("Work")),
            note(2, content = "Visit ISTANBUL", tags = listOf("Home")),
            note(3, tags = listOf("istanbul", "Work")),
        ))
        val projector = NoteListProjector()
        val query = NoteListQuery(search = "istanbul")
        assertEquals(listOf(1L, 2L, 3L), projector.project(state, query).notes.map { it.id })
        assertEquals(listOf(1L, 3L), projector.project(state, query.copy(tag = "work")).notes.map { it.id })
        assertTrue(projector.project(state, query.copy(tag = "wor")).notes.isEmpty())
        assertEquals(3, projector.project(state, query.copy(search = "  ")).notes.size)
        assertTrue(projector.project(state, query.copy(search = "not found")).notes.isEmpty())
        assertEquals(listOf("Home", "istanbul", "Work"), projector.project(state, query.copy(search = "not found")).availableTags)
    }

    @Test fun aNewSnapshotInvalidatesScopeAndQueryCachesIncludingEmptyResults() {
        val projector = NoteListProjector()
        val query = NoteListQuery(NoteCategoryFilter.LocalStarred)
        val first = projector.project(loaded(listOf(note(1))), query)
        assertTrue(first.isReady)
        assertTrue(first.items.isEmpty())
        val updated = projector.project(loaded(listOf(note(1).copy(isFavorite = true))), query)
        assertEquals(listOf(1L), updated.items.map { it.id })
        val deleted = projector.project(loaded(emptyList()), query)
        assertTrue(deleted.isReady)
        assertTrue(deleted.items.isEmpty())
        assertTrue(deleted.allItems.isEmpty())
    }

    @Test fun readinessNeverExposesAnotherScopeWhileSearchChangesCanReuseVisibleResults() {
        val projector = NoteListProjector()
        val localQuery = NoteListQuery(NoteCategoryFilter.Local)
        assertFalse(projector.project(LoadedListState(), localQuery).isReadyFor(NoteCategoryFilter.Local))
        val ready = projector.project(loaded(listOf(note(1))), localQuery)
        assertTrue(ready.isReadyFor(NoteCategoryFilter.Local))
        assertTrue(ready.copy(query = localQuery.copy(search = "previous search")).isReadyFor(NoteCategoryFilter.Local))
        assertFalse(ready.isReadyFor(NoteCategoryFilter.All))
        assertFalse(ready.isReadyFor(NoteCategoryFilter.MdbxDatabase(7)))
        assertFalse(ready.isReadyFor(NoteCategoryFilter.LocalStarred))
    }
}
