package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.ui.PasswordDatabaseFiltersSection
import takagi.ru.monica.ui.PasswordDatabaseFiltersSectionParams
import takagi.ru.monica.viewmodel.CategoryFilter

@RunWith(AndroidJUnit4::class)
class DatabaseFilterChipMenuTest {
    @get:Rule val compose = createComposeRule()
    private val databases = List(512) { LocalKeePassDatabase(it.toLong() + 1, "Database $it", "test/$it") }

    @Test fun collapsedPopupCanSelectLastDatabaseWithoutComposingEveryChip() {
        val open = mutableStateOf(true)
        var selection: CategoryFilter? = null
        compose.setContent {
            MaterialTheme {
                Box {
                    UnifiedCategoryFilterChipMenuDropdown(open.value, { open.value = false }) {
                        PasswordDatabaseFiltersSection(PasswordDatabaseFiltersSectionParams(
                            CategoryFilter.Local, databases, emptyList(), emptyList(),
                            { selection = it; open.value = false },
                        ))
                    }
                }
            }
        }
        compose.onNodeWithText("Database 511").assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasText("Database ", substring = true)).fetchSemanticsNodes().size < 50)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(513)
        compose.onNodeWithText("Database 511").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(CategoryFilter.KeePassDatabase(512), selection) }
        compose.onNodeWithText("Database 511").assertDoesNotExist()
    }

    @Test fun expandedPopupScrollsThroughEveryRowAndRetainsDatabaseIdentity() {
        val open = mutableStateOf(true)
        var selection: UnifiedCategoryFilterSelection? = null
        compose.setContent {
            MaterialTheme {
                Box {
                    UnifiedCategoryFilterChipMenuDropdown(open.value, { open.value = false }) {
                        UnifiedDatabaseFilterChipMenu(
                            selected = UnifiedCategoryFilterSelection.Local,
                            onSelect = { selection = it; open.value = false },
                            keepassDatabases = databases,
                            bitwardenVaults = emptyList(),
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Database 511").assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasText("Database ", substring = true)).fetchSemanticsNodes().size < 50)
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.onNodeWithText("Database 511").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UnifiedCategoryFilterSelection.KeePassDatabaseFilter(512), selection) }
        compose.onNodeWithText("Database 511").assertDoesNotExist()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun virtualRowsMatchExistingChipsAcrossLanguagesFontScalesAndDirections() {
        val names = listOf("家", "Work", "Zażółć gęślą", "日本語の金庫", "🔐", "Long database name for truncation", "中文 KeePass", "🗝️ Secret", "خزنة")
        val items = List(100) { index ->
            DatabaseFilterChipItem("db:$index", "${names[index % names.size]} $index", Icons.Default.Key, index,
                if (index % 3 == 0) Color.Green else null)
        }
        val profiles = listOf(LayoutDirection.Ltr to 1f, LayoutDirection.Ltr to 1.6f, LayoutDirection.Rtl to 1.3f)
        val profile = mutableStateOf(profiles.first())
        val virtual = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(density, profile.value.second),
                    LocalLayoutDirection provides profile.value.first,
                ) {
                    Box {
                        if (virtual.value) {
                            DatabaseFilterChipContent(items, true, { it == 4 }, {})
                        } else {
                            FlowRow(
                                Modifier.width(rememberUnifiedCategoryFilterChipMenuWidth() - 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items.forEach { item ->
                                    MonicaExpressiveFilterChip(item.value == 4, {}, item.label,
                                        leadingIcon = item.icon, statusDotColor = item.statusDotColor, animated = false)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (current in profiles) {
            compose.runOnIdle { profile.value = current; virtual.value = false }
            val original = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().associate {
                it.config[SemanticsProperties.Text].single().text to it.boundsInRoot
            }
            compose.runOnIdle { virtual.value = true }
            compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
            val visible = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }
            assertTrue(visible.isNotEmpty() && visible.size < items.size)
            for (node in visible) {
                val label = node.config[SemanticsProperties.Text].single().text
                val expected = original.getValue(label)
                assertEquals("$current $label x", expected.left, node.boundsInRoot.left, 1f)
                assertEquals("$current $label y", expected.top, node.boundsInRoot.top, 1f)
                assertEquals("$current $label width", expected.width, node.boundsInRoot.width, 1f)
            }
        }
    }
}
