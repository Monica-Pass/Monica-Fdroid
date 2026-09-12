package takagi.ru.monica.notes.ui.model

import java.util.Locale
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.domain.NoteScopeIndex
import takagi.ru.monica.viewmodel.LoadedListState
import takagi.ru.monica.viewmodel.ParsedNoteItem

internal data class NoteListQuery(
    val scope: NoteCategoryFilter = NoteCategoryFilter.All,
    val search: String = "",
    val tag: String? = null,
)

internal data class NoteListProjection(
    val query: NoteListQuery = NoteListQuery(),
    val notes: List<SecureItem> = emptyList(),
    val allNotes: List<SecureItem> = emptyList(),
    val items: List<NoteListItemUiModel> = emptyList(),
    val allItems: List<NoteListItemUiModel> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val isReady: Boolean = false,
)

internal fun NoteListProjection.isReadyFor(scope: NoteCategoryFilter): Boolean = isReady && query.scope == scope

/** Bounded to the current source snapshot; unchanged rows retain decoded text and previews. */
internal class NoteSnapshotCache(
    private val parse: (SecureItem) -> ParsedNoteItem = { item ->
        ParsedNoteItem(item, NoteContentCodec.decodeFromItem(item))
    },
) {
    private var previous = emptyMap<Long, ParsedNoteItem>()

    fun prepare(items: List<SecureItem>): List<ParsedNoteItem> {
        val next = HashMap<Long, ParsedNoteItem>(items.size)
        val result = items.map { item ->
            val parsed = previous[item.id]?.takeIf { it.item == item } ?: parse(item)
            next[item.id] = parsed
            parsed
        }
        previous = next
        return result
    }
}

/** Runs in the ViewModel's Default dispatcher, never during composition or scrolling. */
internal class NoteListProjector {
    private data class ScopeRows(
        val indices: IntArray,
        val rawItems: List<SecureItem>,
        val uiItems: List<NoteListItemUiModel>,
        val tags: List<String>,
    )

    private var source: List<ParsedNoteItem>? = null
    private var allNotes = emptyList<SecureItem>()
    private var allItems = emptyList<NoteListItemUiModel>()
    private var scopeIndex: NoteScopeIndex? = null
    private val scopes = LinkedHashMap<NoteCategoryFilter, ScopeRows>()
    private var previousResult: NoteListProjection? = null

    fun project(state: LoadedListState<ParsedNoteItem>, query: NoteListQuery): NoteListProjection {
        if (!state.isReady) return NoteListProjection(query = query)
        val rows = state.items
        if (source !== rows) {
            source = rows
            allNotes = rows.map { it.item }
            allItems = rows.map { it.uiModel }
            scopeIndex = null
            scopes.clear()
            previousResult = null
        }
        previousResult?.takeIf { it.query == query }?.let { return it }
        val scoped = scopes.getOrPut(query.scope) {
            val indices = if (query.scope == NoteCategoryFilter.All) IntArray(rows.size) { it } else {
                val index = scopeIndex ?: NoteScopeIndex(allNotes).also { scopeIndex = it }
                index.select(query.scope)
            }
            val tags = linkedSetOf<String>()
            for (index in indices) {
                rows[index].content.tags.forEach { tag -> tag.trim().takeIf(String::isNotBlank)?.let(tags::add) }
            }
            ScopeRows(
                indices,
                if (query.scope == NoteCategoryFilter.All) allNotes else indices.map { rows[it].item },
                if (query.scope == NoteCategoryFilter.All) allItems else indices.map { rows[it].uiModel },
                tags.sortedBy { it.lowercase(Locale.getDefault()) },
            )
        }
        if (scopes.size > 4) scopes.remove(scopes.keys.first())
        val hasSearch = query.search.isNotBlank()
        val hasTag = !query.tag.isNullOrBlank()
        var selectedNotes = scoped.rawItems
        var selectedItems = scoped.uiItems
        if (hasSearch || hasTag) {
            val raw = ArrayList<SecureItem>()
            val ui = ArrayList<NoteListItemUiModel>()
            for (index in scoped.indices) {
                val row = rows[index]
                val matchesSearch = !hasSearch || row.item.title.contains(query.search, ignoreCase = true) ||
                    row.content.content.contains(query.search, ignoreCase = true) ||
                    row.content.tags.any { it.contains(query.search, ignoreCase = true) }
                val matchesTag = !hasTag || row.content.tags.any { it.equals(query.tag, ignoreCase = true) }
                if (matchesSearch && matchesTag) {
                    raw.add(row.item)
                    ui.add(row.uiModel)
                }
            }
            selectedNotes = raw
            selectedItems = ui
        }
        return NoteListProjection(
            query = query,
            notes = selectedNotes,
            allNotes = allNotes,
            items = selectedItems,
            allItems = allItems,
            availableTags = scoped.tags,
            isReady = true,
        ).also { previousResult = it }
    }
}
