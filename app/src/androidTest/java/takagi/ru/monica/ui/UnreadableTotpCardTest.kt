package takagi.ru.monica.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class UnreadableTotpCardTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun standardCardProtectsUnreadableKeysAndRecoversItsActions() = verifyCard(false)
    @Test fun tileCardProtectsUnreadableKeysAndRecoversItsActions() = verifyCard(true)

    private fun verifyCard(tile: Boolean) {
        val data = mutableStateOf(TotpData(secret = "", boundPasswordId = 31))
        val title = "Unavailable OTP fixture"
        val copies = mutableListOf<String>()
        var edits = 0
        var qr = 0
        compose.setContent {
            MonicaTheme {
                TotpCodeCard(
                    item = SecureItem(id = -31, itemType = ItemType.TOTP, title = title, itemData = "C2|synthetic"),
                    parsedTotpData = data.value,
                    sharedTickSeconds = 1_700_000_010,
                    appSettings = AppSettings(iconCardsEnabled = false, validatorVibrationEnabled = false,
                        authenticatorCardHideCodeByDefault = false),
                    compactTile = tile,
                    uniformAuthenticatorLayout = true,
                    onCopyCode = { copies += it }, onEdit = { edits++ }, onShowQrCode = { qr++ }, onDelete = {}
                )
            }
        }
        compose.onNode(hasText(title) and hasClickAction()).assertIsDisplayed().performClick()
        compose.onAllNodesWithText("000 000", substring = true).assertCountEquals(0)
        openAction(R.string.edit)
        openAction(R.string.show_qr_code)
        compose.runOnIdle {
            assertEquals(emptyList<String>(), copies)
            assertEquals(0, edits)
            assertEquals(0, qr)
            data.value = TotpData(secret = "JBSWY3DPEHPK3PXP", boundPasswordId = 31)
        }
        compose.onNode(hasText(title) and hasClickAction()).performClick()
        openAction(R.string.edit)
        openAction(R.string.show_qr_code)
        compose.runOnIdle {
            assertEquals(1, copies.size)
            assertEquals(6, copies.single().length)
            assertEquals(1, edits)
            assertEquals(1, qr)
        }
    }

    private fun openAction(id: Int) {
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.onNodeWithText(context.getString(id)).performClick()
    }
}
