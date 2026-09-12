package takagi.ru.monica.ui.note

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.system.measureNanoTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.ui.model.NoteListProjector
import takagi.ru.monica.notes.ui.model.NoteListQuery
import takagi.ru.monica.notes.ui.model.NoteSnapshotCache
import takagi.ru.monica.viewmodel.LoadedListState

@RunWith(AndroidJUnit4::class)
class NoteListPerformanceTest {
    @Test fun measuresCachedPreparationAndSearchWithoutRepeatingPreviewDecoding() {
        val measurements = JSONArray()
        var sink = 0
        for (size in listOf(1000, 5000, 10000)) {
            val (encoded, body) = NoteContentCodec.encode("# Prepared note\n**Formatted** text and a [link](https://example.invalid).", listOf("work"), true)
            val source = List(size) { index -> SecureItem(id = index + 1L, itemType = ItemType.NOTE, title = "Note $index",
                itemData = encoded, notes = body, isFavorite = index % 3 == 0) }
            val equivalent = source.map { it.copy() }
            val cache = NoteSnapshotCache()
            val parsed = cache.prepare(source)
            assertSame(parsed.first(), cache.prepare(equivalent).first())
            val state = LoadedListState(parsed, isReady = true)
            val projector = NoteListProjector()
            val queries = listOf(NoteListQuery(NoteCategoryFilter.Local, "prepared", "work"),
                NoteListQuery(NoteCategoryFilter.LocalStarred, "formatted", "work"))
            repeat(6) { cache.prepare(equivalent); projector.project(state, queries[it % 2]) }
            val coldTimes = List(5) { measureNanoTime { sink += NoteSnapshotCache().prepare(source).size } / 1e6 }
            val cachedTimes = List(11) { measureNanoTime { sink += cache.prepare(equivalent).size } / 1e6 }
            val queryTimes = List(11) { iteration -> measureNanoTime {
                sink += projector.project(state, queries[iteration % 2]).items.size
            } / 1e6 }
            measurements.put(JSONObject().put("notes", size)
                .put("coldDecodeAndPreviewMedianMs", coldTimes.sorted()[2])
                .put("unchangedSnapshotMedianMs", cachedTimes.sorted()[5])
                .put("scopeAndSearchMedianMs", queryTimes.sorted()[5]))
        }
        assertTrue(sink > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("note-performance"), "cache-cost.json")
            .writeText(JSONObject().put("measurements", measurements).toString(2))
    }
}
