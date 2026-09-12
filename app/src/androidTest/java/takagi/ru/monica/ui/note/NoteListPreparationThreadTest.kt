package takagi.ru.monica.ui.note

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.ui.model.NoteListProjection
import takagi.ru.monica.notes.ui.model.NoteListQuery
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.ui.screens.ExpressiveNoteCard
import takagi.ru.monica.viewmodel.NoteViewModel

@RunWith(AndroidJUnit4::class)
class NoteListPreparationThreadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun decodingPreviewsAndScopePreparationStayOffMainAndAreReusedWhileScrolling() {
        val (encoded, body) = NoteContentCodec.encode("# Synthetic note\n**Prepared** preview", listOf("work"), true)
        val backing = List(1500) { index -> SecureItem(id = index + 1L, itemType = ItemType.NOTE,
            title = "Note $index", itemData = encoded, notes = body, isFavorite = index % 3 == 0) }
        val reads = AtomicInteger()
        val mainReads = AtomicInteger()
        val tracked = object : AbstractList<SecureItem>() {
            override val size: Int get() = backing.size
            override fun get(index: Int): SecureItem {
                reads.incrementAndGet()
                if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet()
                return backing[index]
            }
        }
        val source = MutableStateFlow<List<SecureItem>>(tracked)
        val dao = Proxy.newProxyInstance(SecureItemDao::class.java.classLoader, arrayOf(SecureItemDao::class.java)) { _, method, _ ->
            when (method.name) {
                "getItemsByType" -> source
                "getAllItems" -> flowOf(emptyList<SecureItem>())
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as SecureItemDao
        val viewModel = NoteViewModel(SecureItemRepository(dao))
        val rendered = AtomicReference<NoteListProjection>()
        viewModel.updateNoteListQuery(NoteListQuery(NoteCategoryFilter.Local))
        try {
            compose.setContent {
                // Read in this composition scope so SideEffect records every projection,
                // rather than only observing it later inside LazyColumn's content scope.
                val projection = viewModel.noteListProjectionState.collectAsState().value
                SideEffect { rendered.set(projection) }
                MaterialTheme {
                    LazyColumn(Modifier.fillMaxSize().testTag("notes")) {
                        items(projection.items, key = { it.id }) { note ->
                            ExpressiveNoteCard(note, isSelected = false, isGridMode = false, onClick = {}, onLongClick = {})
                        }
                    }
                }
            }
            compose.waitUntil(20_000) { rendered.get()?.items?.size == 1500 }
            compose.waitForIdle()
            val preparedReads = reads.get()
            assertTrue(preparedReads >= 1500)
            repeat(3) {
                compose.onNodeWithTag("notes").performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            assertEquals("Scrolling must not rebuild the source snapshot", preparedReads, reads.get())
            compose.runOnIdle { viewModel.updateNoteListQuery(NoteListQuery(NoteCategoryFilter.LocalStarred, "prepared", "work")) }
            compose.waitUntil(10_000) { rendered.get()?.query?.scope == NoteCategoryFilter.LocalStarred && rendered.get()?.items?.size == 500 }
            assertEquals("Changing a query must reuse decoded notes", preparedReads, reads.get())
            assertEquals("Source preparation must not read notes on the UI thread", 0, mainReads.get())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
