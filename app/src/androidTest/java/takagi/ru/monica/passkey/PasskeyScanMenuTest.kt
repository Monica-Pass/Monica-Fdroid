package takagi.ru.monica.passkey

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.PasskeyListScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

class PasskeyScanMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scanIsReachableWithBottomNavigationAndDoesNotCoverSearch(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val model = PasskeyViewModel(PasskeyRepository(database.passkeyDao()), strings = AppLocaleStringResolver(context))
        val passwordModel = PasswordViewModel(
            PasswordRepository(database.passwordEntryDao()), SecurityManager(context),
            SecureItemRepository(database.secureItemDao()), strings = AppLocaleStringResolver(context),
        )
        val visible = mutableStateOf(true)
        var scans = 0
        var authenticatorOpens = 0
        try {
            compose.setContent {
                if (visible.value) MonicaTheme {
                    PasskeyListScreen(viewModel = model, passwordViewModel = passwordModel, showStandaloneSettingsEntry = false,
                        onScanFidoQr = { scans++ }, onNavigateToAuthenticator = { authenticatorOpens++ })
                }
            }
            val search = compose.onNodeWithContentDescription(context.getString(R.string.search))
            val more = compose.onNodeWithContentDescription(context.getString(R.string.more_options))
            val searchBounds = search.fetchSemanticsNode().boundsInRoot
            val moreBounds = more.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Search and menu buttons must not overlap", searchBounds.right <= moreBounds.left)
            compose.onNodeWithContentDescription(context.getString(R.string.authenticator)).assertDoesNotExist()
            val screen = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.filesDir, "passkey-scan-passkey-header.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screen.recycle()
            more.performClick()
            compose.onNodeWithText(context.getString(R.string.passkey_scan_qr_menu_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.nav_settings)).assertDoesNotExist()
            val bitmap = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            File(context.filesDir, "passkey-scan-passkey.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            compose.onNodeWithText(context.getString(R.string.passkey_scan_qr_menu_title)).performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, scans) }
            more.performClick()
            compose.onNodeWithText(context.getString(R.string.authenticator)).performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, authenticatorOpens); assertEquals(1, scans) }
            search.performClick()
            compose.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
        } finally {
            compose.runOnIdle { visible.value = false }
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            passwordModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }
}
