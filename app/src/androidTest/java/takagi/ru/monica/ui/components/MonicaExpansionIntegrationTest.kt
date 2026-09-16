package takagi.ru.monica.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.screens.PasswordItemRow
import takagi.ru.monica.ui.theme.MonicaTheme

/** Tests the production editor card and actual detail fields, including immediate secret masking. */
@RunWith(AndroidJUnit4::class)
class MonicaExpansionIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val animations = mutableStateOf(true)
    private val secret = "Synthetic-secret-for-expansion-0123456789-".repeat(4)

    private fun show(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalExpansionAnimationsEnabled provides animations.value,
            ) {
                MonicaTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun advance(time: Long) {
        compose.mainClock.advanceTimeBy(time)
        compose.waitForIdle()
    }

    private fun clickDescription(resource: Int) = compose.onNodeWithContentDescription(context.getString(resource))
        .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }

    private fun clickTitle() = compose.onNodeWithText("Optional details")
        .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }

    private fun bounds(tag: String = "card"): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun height(tag: String = "card") = bounds(tag).height

    private fun heights(tag: String = "card"): List<Float> = List(18) { advance(32); height(tag) }

    private fun assertMotion(samples: List<Float>, closed: Float, open: Float, opening: Boolean) {
        assertTrue("Real content must grow by several lines", open > closed + 40f)
        assertTrue("The actual component must render intermediate heights", samples.any { it > closed + 2f && it < open - 2f })
        assertTrue("Expansion must not overshoot its endpoints", samples.all { it >= closed - 1f && it <= open + 1f })
        assertTrue("Motion must not bounce", samples.zipWithNext().all { (a, b) ->
            if (opening) b >= a - 1f else b <= a + 1f
        })
    }

    private fun assertClipboard(expected: String) = compose.runOnIdle {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun sharedPasswordFieldHasFixedMaskSmoothHeightAndImmediateRemasking() {
        val visible = mutableStateOf(false)
        val value = mutableStateOf(secret)
        show {
            Card(Modifier.fillMaxWidth().testTag("card"), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    PasswordField("Secret", value.value, visible.value, { visible.value = !visible.value }, LocalContext.current)
                }
            }
        }
        val closed = height()
        compose.onNodeWithText("••••••••").assertIsDisplayed()
        compose.runOnIdle { value.value = "short" }
        advance(64)
        assertEquals("Mask size must not depend on secret length", closed, height(), 1f)
        compose.runOnIdle { value.value = secret }
        advance(64)
        assertEquals(closed, height(), 1f)
        clickDescription(R.string.show)
        val opening = heights()
        advance(1000)
        val open = height()
        assertMotion(opening, closed, open, true)
        clickDescription(R.string.copy)
        assertClipboard(secret)

        clickDescription(R.string.hide)
        advance(16)
        compose.onNodeWithText(secret).assertDoesNotExist()
        compose.onNodeWithText("••••••••").assertIsDisplayed()
        assertTrue("Remasking must happen before the height finishes shrinking", height() > closed + 2f)
        val closing = heights()
        advance(1000)
        assertMotion(closing, closed, open, false)
        assertEquals(closed, height(), 1f)
        clickDescription(R.string.copy)
        assertClipboard(secret)
    }

    private fun formMotion(dark: Boolean, fontScale: Float) {
        val expanded = mutableStateOf(false)
        show(dark, fontScale) {
            MonicaExpandableCard("Optional details", Icons.Default.Tune, expanded.value,
                { expanded.value = it }, Modifier.testTag("card")) {
                OutlinedTextField("example", {}, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField("example@example.test", {}, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                Text("Additional details stay inside the rounded card.")
            }
            Text("Following section", Modifier.testTag("following").padding(8.dp))
        }
        val closed = height()
        val gap = bounds("following").top - bounds().bottom
        clickTitle()
        val opening = mutableListOf<Float>()
        repeat(18) {
            advance(32)
            opening += height()
            assertEquals("Following sections must move with the card", gap, bounds("following").top - bounds().bottom, 1f)
            if (it == 3) {
                val card = bounds()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                try {
                    val background = bitmap.getPixel(2, 2)
                    for ((x, y) in listOf(card.left + 2 to card.top + 2, card.right - 3 to card.top + 2,
                        card.left + 2 to card.bottom - 3, card.right - 3 to card.bottom - 3)) {
                        val pixel = bitmap.getPixel(x.roundToInt(), y.roundToInt())
                        val distance = listOf(0, 8, 16).sumOf { shift ->
                            abs(((background shr shift) and 255) - ((pixel shr shift) and 255))
                        }
                        assertTrue("All four card corners must remain rounded during expansion", distance < 18)
                    }
                } finally { bitmap.recycle() }
            }
        }
        advance(1000)
        val open = height()
        assertMotion(opening, closed, open, true)
        clickTitle()
        advance(96)
        val interrupted = height()
        clickTitle()
        advance(32)
        assertTrue("Reversal must continue from the rendered height", abs(height() - interrupted) < (open - closed) * 0.35f)
        advance(1000)
        assertEquals(open, height(), 1f)
        clickTitle()
        val closing = heights()
        advance(1000)
        assertMotion(closing, closed, open, false)
        assertEquals(closed, height(), 1f)
        compose.onNodeWithText("Email").assertDoesNotExist()
    }

    @Test fun editorSectionsMoveSmoothlyKeepCornersAndReverse() = formMotion(false, 1f)
    @Test fun editorSectionsSupportDarkThemeAndLargeText() = formMotion(true, 1.3f)

    @Test fun passwordDetailMainValueUsesTheSharedMotion() {
        show {
            Card(Modifier.fillMaxWidth().testTag("card"), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    PasswordItemRow(
                        entry = PasswordEntry(id = 1, title = "Synthetic", website = "", username = "", password = ""),
                        displayPassword = secret, unavailableSource = null, onResyncUnreadable = {},
                        isResyncingUnreadable = false, showSecurityAnalysis = false,
                        index = 1, showIndex = false, onDelete = {}, context = LocalContext.current,
                        canDelete = false, onCreateSend = null,
                    )
                }
            }
        }
        val closed = height()
        clickDescription(R.string.show)
        val opening = heights()
        advance(1000)
        val open = height()
        assertMotion(opening, closed, open, true)
        clickDescription(R.string.hide)
        advance(16)
        compose.onNodeWithText(secret).assertDoesNotExist()
        val closing = heights()
        advance(1000)
        assertMotion(closing, closed, open, false)
        assertEquals(closed, height(), 1f)
    }

    @Test fun protectedCustomFieldsResizeAndCopyTheOriginalValue() {
        show {
            CustomFieldDetailCard(CustomField(entryId = 1, title = "Protected field", value = secret, isProtected = true),
                onCopy = {}, modifier = Modifier.testTag("card"))
        }
        val closed = height()
        clickDescription(R.string.custom_field_show_content)
        val opening = heights()
        advance(1000)
        val open = height()
        assertMotion(opening, closed, open, true)
        clickDescription(R.string.custom_field_hide_content)
        advance(16)
        compose.onNodeWithText(secret).assertDoesNotExist()
        val closing = heights()
        advance(1000)
        assertMotion(closing, closed, open, false)
        clickDescription(R.string.copy)
        assertClipboard(secret)
    }

    @Test fun reducedMotionAppliesToRealEditorAndDetailComponents() {
        val expanded = mutableStateOf(false)
        val visible = mutableStateOf(false)
        animations.value = false
        show {
            MonicaExpandableCard("Optional details", Icons.Default.Tune, expanded.value,
                { expanded.value = it }, Modifier.testTag("form")) {
                Text("One\nTwo\nThree\nFour")
            }
            Card(Modifier.fillMaxWidth().testTag("card")) {
                Column(Modifier.padding(16.dp)) {
                    PasswordField("Secret", secret, visible.value, { visible.value = !visible.value }, LocalContext.current)
                }
            }
        }
        val closedForm = height("form")
        val closedSecret = height()
        clickTitle()
        clickDescription(R.string.show)
        advance(32)
        val openForm = height("form")
        val openSecret = height()
        assertTrue(openForm > closedForm + 40f)
        assertTrue(openSecret > closedSecret + 40f)
        advance(128)
        assertEquals(openForm, height("form"), 1f)
        assertEquals(openSecret, height(), 1f)
        clickTitle()
        clickDescription(R.string.hide)
        advance(32)
        assertEquals(closedForm, height("form"), 1f)
        assertEquals(closedSecret, height(), 1f)
        compose.onNodeWithText(secret).assertDoesNotExist()
    }
}
