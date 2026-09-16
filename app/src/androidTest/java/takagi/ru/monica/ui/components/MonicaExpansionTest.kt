package takagi.ru.monica.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.MdbxCard
import takagi.ru.monica.ui.screens.MdbxExpandableSection
import takagi.ru.monica.ui.theme.MonicaTheme

/** Exercises actual measured/drawn frames, including interruptions and the app's motion setting. */
@RunWith(AndroidJUnit4::class)
class MonicaExpansionTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.SIMPLIFIED_CHINESE)
    })
    private val animationsEnabled = mutableStateOf(true)

    private fun show(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val configuration = Configuration(chinese.resources.configuration).apply { this.fontScale = fontScale }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides chinese,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale),
                LocalExpansionAnimationsEnabled provides animationsEnabled.value
            ) {
                MonicaTheme(darkTheme = dark) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Monica", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Composable
    private fun DetailCard() {
        MdbxExpandableSection(
            title = "备注", subtitle = "轻触以查看完整内容", modifier = Modifier.testTag("card")
        ) {
            Text("账户使用说明", style = MaterialTheme.typography.titleSmall)
            Text(
                "这段备注记录了账户的使用方式。\n\n" +
                    "登录时请核对网站地址，并将恢复代码妥善保存。\n\n" +
                    "更换设备后，可以通过同步继续使用已有的数据。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        MdbxCard(Modifier.fillMaxWidth().testTag("following")) {
            Text("其他信息", Modifier.padding(20.dp), style = MaterialTheme.typography.titleSmall)
        }
    }

    @Composable
    private fun ControlledCards(expanded: Boolean) {
        MdbxCard(Modifier.fillMaxWidth().testTag("visibility")) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("详情", Modifier.weight(1f))
                MonicaExpansionChevron(expanded, null, Modifier.testTag("chevron"))
            }
            MonicaExpandableContent(expanded) {
                Text(LONG_TEXT, Modifier.padding(16.dp).testTag("body"))
            }
        }
        MdbxCard(Modifier.fillMaxWidth().testTag("size")) {
            Column(Modifier.animateMonicaContentSize().padding(16.dp)) {
                Text(if (expanded) LONG_TEXT else "第一行")
            }
        }
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        // A semantic click avoids native ripple timing interfering with the animation clock.
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun height(tag: String): Float = bounds(tag).height
    private fun update(state: MutableState<Boolean>, value: Boolean) = compose.runOnIdle { state.value = value }

    private fun screenshot(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir("expansion-tests"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    private fun chevronPixels(): List<Int> {
        val bitmap = compose.onNodeWithTag("chevron").captureToImage().asAndroidBitmap()
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            listOf(bitmap.width, bitmap.height) + pixels.toList()
        } finally { bitmap.recycle() }
    }

    private fun assertRounded(bitmap: Bitmap, card: Rect) {
        val background = bitmap.getPixel(4, 4)
        val left = card.left.roundToInt() + 3
        val right = card.right.roundToInt() - 4
        val top = card.top.roundToInt() + 3
        val bottom = card.bottom.roundToInt() - 4
        for ((x, y) in listOf(left to top, right to top, left to bottom, right to bottom)) {
            assertTrue("The card must keep all four rounded corners", colorDistance(background, bitmap.getPixel(x, y)) < 12)
        }
        assertTrue("The card's bottom edge must still be drawn",
            colorDistance(background, bitmap.getPixel(card.center.x.roundToInt(), bottom)) > 3)
    }

    private fun exerciseMdbxMotion(dark: Boolean, fontScale: Float, prefix: String) {
        show(dark, fontScale) { DetailCard() }
        val collapsed = height("card")
        val headerTop = compose.onNodeWithText("备注").fetchSemanticsNode().boundsInRoot.top
        val gap = bounds("following").top - bounds("card").bottom
        screenshot("$prefix-closed").recycle()
        val opening = mutableListOf(collapsed)
        click("备注")
        repeat(18) { frame ->
            advance(32)
            val card = bounds("card")
            opening += card.height
            assertEquals(headerTop, compose.onNodeWithText("备注").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(gap, bounds("following").top - card.bottom, 1f)
            val bitmap = screenshot("$prefix-open-${frame.toString().padStart(2, '0')}")
            try { assertRounded(bitmap, card) } finally { bitmap.recycle() }
        }
        advance(1000)
        val expanded = height("card")
        assertTrue(expanded > collapsed + 100f)
        assertTrue("Expansion must have intermediate heights", opening.any { it > collapsed + 5f && it < expanded - 5f })
        assertTrue("Expansion must not bounce", opening.zipWithNext().all { (a, b) -> b >= a - 1f })
        screenshot("$prefix-opened").recycle()

        val closing = mutableListOf(expanded)
        click("备注")
        repeat(18) { frame ->
            advance(32)
            val card = bounds("card")
            closing += card.height
            assertEquals(gap, bounds("following").top - card.bottom, 1f)
            val bitmap = screenshot("$prefix-close-${frame.toString().padStart(2, '0')}")
            try { assertRounded(bitmap, card) } finally { bitmap.recycle() }
        }
        advance(1000)
        assertEquals(collapsed, height("card"), 1f)
        assertTrue("Collapse must have intermediate heights", closing.any { it > collapsed + 5f && it < expanded - 5f })
        assertTrue("Collapse must not bounce", closing.zipWithNext().all { (a, b) -> b <= a + 1f })
        compose.onNodeWithText("账户使用说明").assertDoesNotExist()
    }

    @Test fun mdbxExpansionAndCollapseKeepCornersAndFollowingCardContinuous() {
        exerciseMdbxMotion(dark = false, fontScale = 1f, prefix = "light")
    }

    @Test fun mdbxMotionKeepsCornersInDarkThemeWithLargeText() {
        exerciseMdbxMotion(dark = true, fontScale = 1.4f, prefix = "dark-large")
    }

    @Test fun rapidReversalsContinueFromCurrentHeight() {
        show { DetailCard() }
        val collapsed = height("card")
        click("备注")
        advance(1000)
        val expanded = height("card")
        click("备注")
        advance(1000)
        click("备注")
        advance(112)
        repeat(6) {
            val before = height("card")
            assertTrue(before > collapsed && before < expanded)
            click("备注")
            advance(32)
            assertTrue("Reversing must not restart at either endpoint",
                abs(height("card") - before) < (expanded - collapsed) * 0.3f)
            advance(48)
        }
        advance(1000)
        assertEquals(expanded, height("card"), 1f)
        compose.onNodeWithText("账户使用说明").assertIsDisplayed()
    }

    @Test fun reducedMotionShowsAndResizesContentImmediately() {
        animationsEnabled.value = false
        val expanded = mutableStateOf(false)
        show { ControlledCards(expanded.value) }
        val closedVisibility = height("visibility")
        val closedSize = height("size")
        update(expanded, true)
        advance(32)
        val fullVisibility = height("visibility")
        val fullSize = height("size")
        assertTrue(fullVisibility > closedVisibility + 100f)
        assertTrue(fullSize > closedSize + 100f)
        advance(64)
        assertEquals(fullVisibility, height("visibility"), 1f)
        assertEquals(fullSize, height("size"), 1f)
        update(expanded, false)
        advance(32)
        assertEquals(closedVisibility, height("visibility"), 1f)
        assertEquals(closedSize, height("size"), 1f)
        compose.onNodeWithTag("body").assertDoesNotExist()
    }

    @Test fun disablingMotionDuringExpansionFinishesAtTheRequestedSize() {
        animationsEnabled.value = false
        val expanded = mutableStateOf(true)
        show { ControlledCards(expanded.value) }
        val fullVisibility = height("visibility")
        val fullSize = height("size")
        val fullChevron = chevronPixels()
        update(expanded, false)
        advance(32)
        val closedVisibility = height("visibility")
        val closedSize = height("size")
        val closedChevron = chevronPixels()
        update(animationsEnabled, true)
        advance(32)
        update(expanded, true)
        advance(96)
        assertTrue(height("visibility") > closedVisibility && height("visibility") < fullVisibility)
        assertTrue(height("size") > closedSize && height("size") < fullSize)
        update(animationsEnabled, false)
        advance(32)
        assertEquals(fullVisibility, height("visibility"), 1f)
        assertEquals(fullSize, height("size"), 1f)
        assertEquals("The chevron must also finish immediately", fullChevron, chevronPixels())
        update(animationsEnabled, true)
        advance(32)
        update(expanded, false)
        advance(96)
        update(animationsEnabled, false)
        advance(32)
        assertEquals(closedVisibility, height("visibility"), 1f)
        assertEquals(closedSize, height("size"), 1f)
        assertEquals(closedChevron, chevronPixels())
    }

    @Test fun resizingExistingTextHasIntermediateHeightsInBothDirections() {
        val expanded = mutableStateOf(true)
        show { ControlledCards(expanded.value) }
        val fullSize = height("size")
        advance(32)
        assertEquals("Initially expanded content should already be at full height", fullSize, height("size"), 1f)
        update(expanded, false)
        advance(96)
        val shrinking = height("size")
        advance(1000)
        val closedSize = height("size")
        assertTrue(shrinking > closedSize && shrinking < fullSize)
        update(expanded, true)
        advance(96)
        assertTrue(height("size") > closedSize && height("size") < fullSize)
        advance(1000)
        assertEquals(fullSize, height("size"), 1f)
    }

    @Test fun markdownCodeExpansionMovesFollowingTextSmoothlyAndCopiesFullCode() {
        val code = (1..16).joinToString("\n") { "line_$it = $it" }
        val previewCode = code.lines().take(4).joinToString("\n")
        val remainingCode = code.lines().drop(4).joinToString("\n")
        show {
            MarkdownPreviewText(
                "```\n$code\n```\n\n后续内容", emptyMap(),
                searchHighlightQuery = "line_4 = 4\nline_5 = 5", showSearchHighlight = true,
                modifier = Modifier.testTag("markdown")
            )
        }
        val collapsed = height("markdown")
        val followingTop = compose.onNodeWithText("后续内容").fetchSemanticsNode().boundsInRoot.top
        click(chinese.getString(R.string.copy))
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        screenshot("markdown-closed").recycle()
        click(chinese.getString(R.string.expand))
        advance(96)
        val opening = height("markdown")
        screenshot("markdown-opening").recycle()
        assertEquals(opening - collapsed,
            compose.onNodeWithText("后续内容").fetchSemanticsNode().boundsInRoot.top - followingTop, 1f)
        advance(1000)
        val fullHeight = height("markdown")
        assertTrue(opening > collapsed && opening < fullHeight)
        compose.onNodeWithText(remainingCode).assertIsDisplayed()
        val previewSpans = compose.onNodeWithText(previewCode).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().spanStyles
        val remainingSpans = compose.onNodeWithText(remainingCode).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().spanStyles
        assertTrue("Search highlighting must cross the preview boundary", previewSpans.any { it.end == previewCode.length })
        assertTrue(remainingSpans.any { it.start == 0 && it.end == "line_5 = 5".length })
        screenshot("markdown-opened").recycle()
        click(chinese.getString(R.string.collapse))
        advance(96)
        assertTrue(height("markdown") > collapsed && height("markdown") < fullHeight)
        compose.onNodeWithText(remainingCode).assertExists()
        screenshot("markdown-closing").recycle()
        advance(1000)
        compose.onNodeWithText(remainingCode).assertDoesNotExist()
        assertEquals(collapsed, height("markdown"), 1f)
        assertEquals(followingTop, compose.onNodeWithText("后续内容").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))

    private companion object {
        const val LONG_TEXT = "第一行\n第二行\n第三行\n第四行\n第五行\n第六行"
    }
}
