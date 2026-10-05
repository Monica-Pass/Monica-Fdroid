package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.CategoryFilter

@RunWith(AndroidJUnit4::class)
class PasswordFilterPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val selectedTypes = mutableStateOf(emptySet<PasswordPageContentType>())
    private val favorite = mutableStateOf(false)
    private val open = mutableStateOf(true)
    private val filter = mutableStateOf<CategoryFilter>(CategoryFilter.All)
    private val scale = mutableStateOf(1f)
    private val dark = mutableStateOf(false)
    private val fontScale = mutableStateOf(1f)
    private val order = mutableStateOf(listOf(PasswordListQuickFilterItem.FAVORITE,
        PasswordListQuickFilterItem.TWO_FA, PasswordListQuickFilterItem.CARD_WALLET,
        PasswordListQuickFilterItem.NOTES, PasswordListQuickFilterItem.PASSKEY))
    private var databases: List<LocalKeePassDatabase> = emptyList()
    private fun show() {
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale.value, base.fontScale * fontScale.value)) {
            MonicaTheme(darkTheme = dark.value) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Box(Modifier.size(48.dp)) {
                PasswordFilterPanel(open.value, { open.value = false }) {
                PasswordListCategoryChipMenu(
                    currentFilter = filter.value,
                    keepassDatabases = databases,
                    mdbxDatabases = emptyList(),
                    bitwardenVaults = emptyList(),
                    configuredQuickFilterItems = order.value,
                    quickFilterFavorite = favorite.value,
                    onQuickFilterFavoriteChange = { favorite.value = it },
                    quickFilter2fa = false,
                    onQuickFilter2faChange = {},
                    quickFilterNotes = false,
                    onQuickFilterNotesChange = {},
                    quickFilterPasskey = false,
                    onQuickFilterPasskeyChange = {},
                    quickFilterBoundNote = false,
                    onQuickFilterBoundNoteChange = {},
                    quickFilterAttachments = false,
                    onQuickFilterAttachmentsChange = {},
                    quickFilterUncategorized = false,
                    onQuickFilterUncategorizedChange = {},
                    quickFilterLocalOnly = false,
                    onQuickFilterLocalOnlyChange = {},
                    quickFilterManualStackOnly = false,
                    onQuickFilterManualStackOnlyChange = {},
                    quickFilterNeverStack = false,
                    onQuickFilterNeverStackChange = {},
                    quickFilterUnstacked = false,
                    onQuickFilterUnstackedChange = {},
                    aggregateSelectedTypes = selectedTypes.value,
                    aggregateVisibleTypes = emptyList(),
                    onToggleAggregateType = { type -> selectedTypes.value = if (type in selectedTypes.value) selectedTypes.value - type else selectedTypes.value + type },
                    quickFolderShortcuts = listOf(
                        PasswordQuickFolderShortcut("work", "Work", "", false, CategoryFilter.Custom(81), null),
                        PasswordQuickFolderShortcut("personal", "Personal", "", false, CategoryFilter.Custom(82), null),
                    ),
                    topModulesOrder = listOf(PasswordListTopModule.QUICK_FOLDERS, PasswordListTopModule.QUICK_FILTERS),
                    onTopModulesOrderChange = {},
                    onQuickFilterItemsOrderChange = { order.value = it },
                    launchAnchorBounds = null,
                    onDismiss = { open.value = false },
                    onSelectFilter = { filter.value = it },
                    categories = listOf(Category(id = 81, name = "Work")),
                    onRenameCategory = { _, _ -> },
                    onCreateCategory = {},
                )
                }
                }
                }
            }
            }
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("quick_filter_FAVORITE").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText(context.getString(R.string.category_selection_menu_quick_filters)).performClick()
        }
    }
    private fun snapshot(name: String) {
        val directory = File(context.filesDir, "filter-panel-316").apply { mkdirs() }
        compose.onNodeWithTag("password_filter_panel").captureToImage().asAndroidBitmap().compress(
            Bitmap.CompressFormat.PNG, 100, File(directory, "$name.png").outputStream())
    }
    @Test fun credentialFiltersAreDistinctSelectableAndWrap() {
        order.value = PasswordListQuickFilterItem.DEFAULT_ORDER + listOf(
            PasswordListQuickFilterItem.API_KEY, PasswordListQuickFilterItem.API_TOKEN, PasswordListQuickFilterItem.GPG_KEY)
        show()
        for ((item, type) in listOf(PasswordListQuickFilterItem.API_KEY to PasswordPageContentType.API_KEY,
            PasswordListQuickFilterItem.API_TOKEN to PasswordPageContentType.API_TOKEN,
            PasswordListQuickFilterItem.GPG_KEY to PasswordPageContentType.GPG_KEY)) {
            compose.onNodeWithTag("quick_filter_${item.name}").performScrollTo().performTouchInput { click() }
            compose.runOnIdle { assertTrue(type in selectedTypes.value) }
        }
        snapshot("credential-filters")
        compose.onNodeWithTag("quick_filter_API_KEY").performScrollTo().performTouchInput { click() }
        compose.runOnIdle {
            assertFalse(PasswordPageContentType.API_KEY in selectedTypes.value)
            assertTrue(PasswordPageContentType.API_TOKEN in selectedTypes.value)
        }
    }
    @Test fun editKeepsGeometryAndDragDoesNotToggleFilter() {
        show()
        val item = compose.onNodeWithTag("quick_filter_FAVORITE")
        item.performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertTrue(favorite.value) }
        assertEquals(item.fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithTag("quick_filter_TWO_FA").fetchSemanticsNode().boundsInRoot.top, 1f)
        val before = item.fetchSemanticsNode().boundsInRoot.size
        snapshot("normal")
        compose.onNodeWithText(context.getString(R.string.edit)).performClick()
        val after = item.fetchSemanticsNode().boundsInRoot.size
        assertEquals(before, after)
        item.performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertTrue(favorite.value) }
        val target = compose.onNodeWithTag("quick_filter_TWO_FA").fetchSemanticsNode().boundsInRoot.center
        val origin = item.fetchSemanticsNode().boundsInRoot.topLeft
        item.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(center)
            moveTo(target - origin, delayMillis = 400)
            advanceEventTime(350)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(PasswordListQuickFilterItem.TWO_FA, order.value.first())
            assertEquals(PasswordListQuickFilterItem.CARD_WALLET, order.value[2])
            assertTrue(favorite.value)
        }
        snapshot("edited")
        val beforeCancel = item.fetchSemanticsNode().boundsInRoot.topLeft
        val cancelTarget = compose.onNodeWithTag("quick_filter_TWO_FA").fetchSemanticsNode().boundsInRoot.center
        item.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(center)
            moveTo(cancelTarget - beforeCancel, delayMillis = 400)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(beforeCancel, item.fetchSemanticsNode().boundsInRoot.topLeft)
        compose.runOnIdle { assertEquals(PasswordListQuickFilterItem.TWO_FA, order.value.first()) }

        compose.onNodeWithText(context.getString(R.string.filter_panel_done)).performClick()
        item.performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertFalse(favorite.value) }
    }
    @Test fun scaleAndHeightLimitKeepActionsReachable() {
        show()
        val oldHeight = compose.onNodeWithTag("quick_filter_FAVORITE").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { scale.value = 1.3f; fontScale.value = 1.3f; dark.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("quick_filter_FAVORITE").performScrollTo()
        val newHeight = compose.onNodeWithTag("quick_filter_FAVORITE").fetchSemanticsNode().boundsInRoot.height
        assertTrue("App density must cross modal boundary", newHeight > oldHeight * 1.2f)
        compose.onNodeWithContentDescription(context.getString(R.string.close)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.filter_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.edit)).assertIsDisplayed()
        snapshot("dark-scaled")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("password_filter_panel").assertDoesNotExist()
    }
    @Test fun databaseExpansionAndCollapseHaveIntermediateFramesAndCanReverse() {
        databases = (1..6).map { LocalKeePassDatabase(id = it.toLong(), name = "Vault $it", filePath = "test-$it") }
        show()
        fun height() = compose.onNodeWithTag("database_chip_viewport").fetchSemanticsNode().boundsInRoot.height
        fun toggle() { compose.onNodeWithTag("database_expand_toggle").performClick(); compose.mainClock.advanceTimeByFrame() }
        try {
            for (largeFont in listOf(false, true)) {
                compose.runOnIdle { fontScale.value = if (largeFont) 1.5f else 1f; dark.value = largeFont }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val collapsed = height()
                toggle()
                compose.mainClock.advanceTimeBy(80)
                val expanding = height()
                snapshot("database-expanding-$largeFont")
                compose.mainClock.advanceTimeBy(400)
                val expanded = height()
                assertTrue("Expansion must include an intermediate height: $collapsed < $expanding < $expanded",
                    expanding > collapsed + 1 && expanding < expanded - 1)
                toggle()
                compose.mainClock.advanceTimeBy(80)
                val collapsing = height()
                assertTrue("Collapse must include an intermediate height", collapsing > collapsed + 1 && collapsing < expanded - 1)
                compose.mainClock.advanceTimeBy(400)
                assertEquals(collapsed, height(), 1f)
                toggle()
                compose.mainClock.advanceTimeBy(64)
                toggle()
                compose.mainClock.advanceTimeBy(400)
                assertEquals("Rapid reversal must finish collapsed", collapsed, height(), 1f)
                compose.mainClock.autoAdvance = true
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun databaseRowExpandsWithinHeightLimitAndExitAnimates() {
        databases = (1..16).map { LocalKeePassDatabase(id = it.toLong(), name = "Vault $it", filePath = "test-$it") }
        show()
        val row = compose.onNodeWithTag("database_row")
        compose.onNodeWithTag("database_filter_keepass:16").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CategoryFilter.KeePassDatabase(16L), filter.value) }
        compose.onNodeWithTag("database_expand_toggle").performClick()
        compose.onNodeWithTag("database_expanded").assertExists()
        val maxPixels = 460f * context.resources.displayMetrics.density
        val expandedHeight = compose.onNodeWithTag("password_filter_panel").fetchSemanticsNode().boundsInRoot.height
        assertTrue(expandedHeight <= maxPixels)
        assertTrue("Use the taller wallet-sized viewport", expandedHeight > 400f * context.resources.displayMetrics.density)
        compose.onNodeWithText(context.getString(R.string.add_category)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.edit)).assertIsDisplayed()
        compose.onNodeWithTag("database_filter_keepass:16").performScrollTo().performClick()
        compose.onNodeWithTag("database_expand_toggle").performScrollTo().performClick()
        row.assertExists()
        snapshot("compact-databases")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("password_filter_panel").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("password_filter_panel").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
    @Test fun lowDensityContentFillsPopupAndDatabaseViewport() {
        databases = (1..16).map { LocalKeePassDatabase(id = it.toLong(), name = "Vault $it", filePath = "test-$it") }
        scale.value = 0.65f
        show()
        for (value in listOf(0.65f, 0.85f, 1f)) {
            compose.runOnIdle { scale.value = value }
            compose.waitForIdle()
            val frame = compose.onNodeWithTag("password_filter_menu_frame").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("password_filter_panel").fetchSemanticsNode().boundsInRoot
            val row = compose.onNodeWithTag("database_row").fetchSemanticsNode().boundsInRoot
            val inset = 12f * context.resources.displayMetrics.density * value
            assertEquals("Content must reach the menu's right edge", frame.right, content.right, 1f)
            assertEquals("No second, narrower content width", frame.width, content.width, 1f)
            assertEquals("Database scroll viewport uses the whole content width", content.right - inset, row.right, 2f)
            assertEquals(content.left + inset, row.left, 2f)
            snapshot("density-${(value * 100).toInt()}")
            compose.onNodeWithTag("database_filter_keepass:16").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(CategoryFilter.KeePassDatabase(16L), filter.value) }
        }
    }
    @Test fun databaseHeaderPressStaysInsideRoundedCorners() {
        show()
        val header = compose.onNodeWithTag("database_expand_toggle")
        val before = header.captureToImage().toPixelMap()
        header.performTouchInput { down(center); advanceEventTime(500) }
        compose.mainClock.advanceTimeBy(500)
        val pressed = header.captureToImage().toPixelMap()
        assertEquals("Rounded corner must not receive the rectangular press highlight", before[1, 1], pressed[1, 1])
        assertNotEquals("Press feedback must remain visible inside the header", before[before.width / 2, before.height / 2], pressed[pressed.width / 2, pressed.height / 2])
        snapshot("database-header-pressed")
        header.performTouchInput { cancel() }
    }
    @Test fun browsingLayoutChangesDoNotTranslateChips() {
        show()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.runOnIdle { filter.value = if (it % 2 == 0) CategoryFilter.Local else CategoryFilter.All }
            compose.mainClock.advanceTimeByFrame()
            val slot = compose.onNodeWithTag("quick_filter_FAVORITE").fetchSemanticsNode().boundsInRoot
            val visual = compose.onNodeWithTag("quick_filter_visual_FAVORITE", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(slot.topLeft, visual.topLeft)
        }
        compose.mainClock.autoAdvance = true
    }
    @Test fun databaseSelectionIsAppliedAndPanelStaysOpen() {
        show()
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_local_database)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_local_database)).performClick()
        compose.runOnIdle { assertEquals(CategoryFilter.Local, filter.value) }
        compose.onNodeWithTag("password_filter_panel").assertIsDisplayed()
    }
}
