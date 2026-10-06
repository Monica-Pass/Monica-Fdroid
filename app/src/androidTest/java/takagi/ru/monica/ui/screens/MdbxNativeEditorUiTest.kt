package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.repository.MdbxNativeAttachment
import takagi.ru.monica.repository.MdbxNativeObjectDetail
import takagi.ru.monica.repository.MdbxNativeObjectSummary
import takagi.ru.monica.ui.theme.MonicaTheme

class MdbxNativeEditorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginFieldsProduceDirectNativePayloadAndSaveStaysReachable() {
        var saved: String? = null
        compose.setContent { MonicaTheme {
            MdbxNativeObjectEditor(null, emptyList(), false, null, onPickAttachments = {}, onRemoveUpload = {}, onBack = {},
                onSave = { title, type, payload, uploads, removed ->
                    assertEquals("Account", title)
                    assertEquals("login", type)
                    assertTrue(uploads.isEmpty())
                    assertTrue(removed.isEmpty())
                    saved = payload
                })
        } }
        compose.onNodeWithTag("native_editor_save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("native_editor_title").performScrollTo().performTextInput("Account")
        compose.onNodeWithTag("native_editor_username").performScrollTo().performTextInput("alice")
        compose.onNodeWithTag("native_editor_password").performScrollTo().performTextInput("test-only-secret")
        compose.onNodeWithTag("native_editor_title").performScrollTo()
        // Synthetic draft only: this verifies rendering, not hardware authentication.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "native-editor-fixture.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.onNodeWithTag("native_editor_save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val result = Json.parseToJsonElement(requireNotNull(saved)).jsonObject
            assertEquals("alice", result["username"]?.jsonPrimitive?.content)
            assertEquals("test-only-secret", result["password_plain"]?.jsonPrimitive?.content)
        }
    }

    @Test fun malformedGenericPayloadCannotBeSaved() {
        val original = MdbxNativeObjectDetail(
            MdbxNativeObjectSummary("id", "folder", "future.type", "Opaque", 1u, "revision"),
            """{"nested":{"keep":true}}""", emptyList())
        compose.setContent { MonicaTheme {
            MdbxNativeObjectEditor(original, emptyList(), false, null, onPickAttachments = {}, onRemoveUpload = {}, onBack = {},
                onSave = { _, _, _, _, _ -> fail("Invalid JSON must not reach save") })
        } }
        compose.onNodeWithTag("native_editor_json").performScrollTo().performTextReplacement("{invalid")
        compose.onNodeWithTag("native_editor_save").assertIsNotEnabled()
    }

    @Test fun attachmentBytesAreWipedAfterTextPreview() {
        val bytes = "preview fixture".toByteArray()
        compose.setContent { MonicaTheme {
            MdbxNativeAttachmentPreview(MdbxNativeAttachment("id", "note.txt", "text/plain", bytes.size.toLong(), "fixture"),
                load = { bytes }, onDismiss = {})
        } }
        compose.waitUntil(5_000) { bytes.all { it == 0.toByte() } }
        compose.onNodeWithText("preview fixture").assertIsDisplayed()
        compose.runOnIdle { assertTrue(bytes.all { it == 0.toByte() }) }
    }
}
