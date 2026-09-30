package takagi.ru.monica.ui

import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.*
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.utils.WifiConnectLauncher

class WifiUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun entry(raw: String) = PasswordEntry(title = "Fixture network", username = "", password = "", website = "", loginType = "WIFI", wifiMetadata = raw)

    @Test fun detailShowsSsidAndSupportsConnectAndQrFromTheNetworkMenu() {
        var launched: Intent? = null
        val intercepted = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        compose.setContent { CompositionLocalProvider(LocalContext provides intercepted) { MaterialTheme {
            WifiDetailContent(entry("""{"ssid":"Fixture;网络","security":"WPA2_WPA3","hiddenNetwork":true}"""), "synthetic-password")
        } } }
        compose.onNode(hasText("Fixture;网络") and hasAnyAncestor(hasTestTag("wifi_detail_ssid")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("wifi_detail_connect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(Settings.ACTION_WIFI_SETTINGS, launched?.action) }
        compose.onNodeWithTag("wifi_detail_ssid").performClick()
        compose.onAllNodesWithText(context.getString(R.string.wifi_qr_button)).onLast().performClick()
        compose.onNodeWithText(context.getString(R.string.wifi_qr_dialog_title, "Fixture;网络")).assertExists()
    }

    @Test fun legacyEmptyMetadataShowsTitleAndUnavailablePasswordCannotGenerateQr() {
        compose.setContent { MaterialTheme { WifiDetailContent(entry(""), null) } }
        compose.onNode(hasText("Fixture network") and hasAnyAncestor(hasTestTag("wifi_detail_ssid")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("wifi_detail_qr").assertIsNotEnabled()
        compose.onNodeWithTag("wifi_detail_connect").assertIsNotEnabled()
    }

    @Test fun wifiSecurityUsesLocalizedChoicesAndRetainsUnknownTypes() {
        var draft by mutableStateOf(TemplateCredentialDraft("WIFI", mapOf("ssid" to "Fixture", "security" to "FUTURE_SECURITY")))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalFilledEntryForm provides true) { TemplateCredentialFields(draft, { draft = it }, ssidInIdentity = true) }
        } }
        compose.onNodeWithTag("wifi_security").assertTextContains("FUTURE_SECURITY").performClick()
        compose.onNodeWithText(context.getString(R.string.wifi_security_wpa3)).performClick()
        compose.runOnIdle { assertEquals("WPA3", draft.value("security")) }
        compose.onNodeWithTag("wifi_security").assertTextContains(context.getString(R.string.wifi_security_wpa3))
    }

    @Test fun settingsDispatchFailureReturnsFailureInsteadOfReportingSuccess() {
        val failing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw android.content.ActivityNotFoundException("synthetic") }
        }
        assertEquals(WifiConnectLauncher.Result.Failed, WifiConnectLauncher.launch(failing, WifiData(ssid = "Fixture", security = WifiSecurity.NONE), ""))
    }
}
