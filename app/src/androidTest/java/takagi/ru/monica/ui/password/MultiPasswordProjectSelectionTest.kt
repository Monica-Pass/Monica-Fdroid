package takagi.ru.monica.ui.password

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import java.io.File
import android.graphics.Bitmap

class MultiPasswordProjectSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        PasswordEntry(id = 801, title = "Project", username = "alice", password = "one", website = "", passwordGroupId = "explicit-project"),
        PasswordEntry(id = 802, title = "Project", username = "alice", password = "two", website = "", passwordGroupId = "explicit-project"),
        PasswordEntry(id = 803, title = "Project", username = "alice", password = "three", website = ""))

    @Test fun selectionUsesOneCardWithoutPasswordChipsAndMovesWholeProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected by mutableStateOf(emptySet<String>())
        compose.setContent {
            MaterialTheme {
                MultiPasswordEntryCard(rows.take(2), isSelectionMode = true,
                    selectedPasswords = selectedPasswordIds(selected), onClick = { entry ->
                        val group = expandPasswordProjectSelection(setOf(passwordSelectionKey(entry.id)), rows)
                        selected = if (group.all { it in selected }) selected - group else selected + group
                    })
            }
        }
        compose.onNodeWithText(context.getString(R.string.password_item_title, 1)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.password_item_title, 2)).assertDoesNotExist()
        compose.onNodeWithTag("password_project_card").performClick()
        compose.runOnIdle {
            assertEquals(setOf(801L, 802L), selectedPasswordIds(selected))
            assertEquals(1, selectedPasswordProjectCount(selected, rows))
        }
        val dir = File(context.getExternalFilesDir(null), "multi-password-ui").apply { mkdirs() }
        File(dir, "whole-project.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("password_project_card").performClick()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }
}
