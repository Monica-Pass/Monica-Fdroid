package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.ui.components.ExpressiveLazyListScrollbar
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultDateScrollbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullDateFitsWhileDraggingAtLargeSystemFont() {
        compose.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(80) { Text("Fixture $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                    ExpressiveLazyListScrollbar(state,
                        Modifier.align(Alignment.CenterEnd).testTag("date_scrollbar"),
                        labelForIndex = { "2026/09/29" })
                }
            }
        }
        compose.onNodeWithTag("date_scrollbar").performTouchInput {
            down(center.copy(y = 20f))
            moveTo(center, delayMillis = 300)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("vault_scrollbar_label").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("2026/09/29").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "date-scrollbar-315.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val layout = layouts.single()
        // With softWrap=false Compose may retain a wider paragraph constraint than
        // the measured glyphs. Check the actual glyph bounds, not that constraint.
        assertTrue("Date glyphs must fit", layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue("Date glyphs must fit", layout.getLineLeft(0) >= 0f)
        assertFalse("Date line must fit vertically", layout.didOverflowHeight)
        compose.onNodeWithTag("date_scrollbar").performTouchInput { up() }
    }
}
