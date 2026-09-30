package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmbeddedWalletPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val security by lazy { SecurityManager(context) }
    private var chosen: SecureItem? = null
    private var shown by mutableStateOf(true)
    private fun card(id: Long, tail: String, bank: String, remote: Boolean = false) = SecureItem(
        id = id, itemType = ItemType.BANK_CARD, title = "Everyday card", mdbxDatabaseId = if (remote) 72 else null,
        itemData = """{"cardNumber":"411111111111$tail","bankName":"$bank","cardholderName":"ALICE","expiryMonth":"09","expiryYear":"2030","cvv":"987","pin":"0123","future":{"keep":true},"cardFace":{"imageAttachmentName":"face.png"}}""",
        notes = "private note")
    private fun show(items: List<SecureItem>, dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides security,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    if (shown) EmbeddedWalletPicker(items, context.getString(R.string.embedded_copy_card),
                        onSelect = { chosen = it; shown = false }, onDismiss = { shown = false },
                        mdbxDatabases = listOf(LocalMdbxDatabase(72, "Family MDBX", "picker-test.mdbx")))
                }
            }
        }
        waitForResults()
    }
    private fun waitForResults() = compose.waitUntil(15000) {
        compose.onAllNodesWithTag("overview_pin_results").fetchSemanticsNodes().isNotEmpty()
    }
    private fun search(text: String) {
        compose.onNodeWithTag("overview_pin_search").performTextReplacement(text)
        compose.onNodeWithTag("overview_pin_search").performImeAction()
        waitForResults()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        File(context.getExternalFilesDir("copy-card-picker"), "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun sameTitleCardsCanBeFoundByBankTailAndSourceAndReturnTheWholeOriginal() {
        val first = card(901, "1234", "Local bank")
        val raw = card(902, "5678", "Travel bank", true)
        val second = raw.copy(itemData = security.encryptData(raw.itemData))
        show(listOf(first, second, card(903, "9999", "Deleted").copy(isDeleted = true)))
        compose.onNodeWithTag("overview_pin_footer").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_recommend").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_row_bank_card:901").assertTextContains("Local bank · ALICE").assertTextContains("•••• 1234")
        compose.onNodeWithTag("overview_pin_row_bank_card:902").assertTextContains("Travel bank · ALICE").assertTextContains("•••• 5678")
        compose.onAllNodesWithText("4111111111115678").assertCountEquals(0)
        compose.onNodeWithTag("overview_pin_row_bank_card:903").assertDoesNotExist()
        screenshot("android-copy-light")
        search("Travel")
        compose.onNodeWithTag("overview_pin_row_bank_card:901").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_row_bank_card:902").assertIsDisplayed()
        search("no-match")
        compose.onNodeWithText(context.getString(R.string.no_results)).assertIsDisplayed()
        search("5678")
        compose.onNodeWithTag("overview_pin_row_bank_card:902").assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_clear").performClick()
        compose.onNodeWithTag("overview_pin_scope").performClick()
        compose.onNode(hasText("Family MDBX") and hasAnyAncestor(hasTestTag("overview_pin_database_menu"))).performClick()
        waitForResults()
        compose.onNodeWithTag("overview_pin_row_bank_card:901").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_row_bank_card:902").performClick()
        compose.onNodeWithTag("embedded_wallet_picker").assertDoesNotExist()
        assertSame(second, chosen)
        assertEquals(raw.itemData, security.decryptDataIfMonicaCiphertext(chosen!!.itemData))
        assertEquals("private note", chosen!!.notes)
    }
    @Test fun largeFontDarkSheetKeepsTailVisibleAndCancelDoesNotCopy() {
        show(listOf(card(901, "1234", "A very long bank name"), card(902, "5678", "Travel bank", true)), dark = true, fontScale = 1.6f)
        compose.onNodeWithText("•••• 1234", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("•••• 5678", useUnmergedTree = true).assertIsDisplayed()
        screenshot("android-copy-dark-large")
        compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        compose.onNodeWithTag("embedded_wallet_picker").assertDoesNotExist()
        assertNull(chosen)
    }
    @Test fun notesKeepTheirOriginalPayloadAndDoNotSearchPrivateBody() {
        val note = SecureItem(id = 905, itemType = ItemType.NOTE, title = "Travel note", itemData = """{"content":"private-body","future":7}""")
        show(listOf(note))
        search("private-body")
        compose.onNodeWithTag("overview_pin_row_note:905").assertDoesNotExist()
        search("Travel")
        compose.onNodeWithTag("overview_pin_row_note:905").performClick()
        assertSame(note, chosen)
    }
}
