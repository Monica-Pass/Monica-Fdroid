package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.R

class CompactCredentialFieldTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun contentMenuFollowsTouchAndCopyStillUsesCallback() {
        var copies = 0
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalCompactCredentialFields provides true) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 80.dp, end = 16.dp)) {
                DetailField("Account", "alice", context, onCopy = { copies++ })
            }
        } } }
        val row = compose.onNodeWithTag("compact_credential_field")
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height <= 64 * context.resources.displayMetrics.density)
        compose.onNodeWithContentDescription(context.getString(R.string.field_action_more)).assertDoesNotExist()
        fun tap(x: Float): android.graphics.Rect {
            row.performTouchInput { click(Offset(x, centerY)) }
            compose.waitForIdle()
            compose.onNodeWithTag("password_field_action_popup").assertIsDisplayed()
            val node = compose.onNodeWithTag("password_field_action_popup").fetchSemanticsNode()
            val origin = node.positionOnScreen
            return android.graphics.Rect(origin.x.toInt(), origin.y.toInt(),
                origin.x.toInt() + node.size.width, origin.y.toInt() + node.size.height)
        }
        val left = tap(12f)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("password_field_action_popup").fetchSemanticsNodes().isEmpty() }
        val right = tap(120f)
        assertTrue("Menu must track the touch instead of a fixed button", right.left > left.left + 30)
        assertTrue(right.right <= context.resources.displayMetrics.widthPixels && right.bottom <= context.resources.displayMetrics.heightPixels)
        compose.onNodeWithText(context.getString(R.string.copy), useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, copies) }
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).performClick()
        compose.runOnIdle { assertEquals(2, copies) }
    }

    @Test fun largeTextKeepsVisibilityAndCopyAccessible() {
        var visible by mutableStateOf(false)
        var copies = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f), LocalCompactCredentialFields provides true) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(280.dp).padding(12.dp)) {
                        DetailField("Work account password", "long-password-1234567890-abcdefghijklmnop", context,
                            displayValue = if (visible) "long-password-1234567890-abcdefghijklmnop" else "••••••••",
                            protected = true, visible = visible, onToggleVisibility = { visible = !visible }, onCopy = { copies++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.show)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithText("long-password-1234567890-abcdefghijklmnop").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, copies) }
        compose.onNodeWithContentDescription(context.getString(R.string.hide)).performClick()
        compose.runOnIdle { assertFalse(visible) }
    }
}
