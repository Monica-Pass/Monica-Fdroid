package takagi.ru.monica.bitwarden.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.bitwarden.service.BitwardenTwoFactorPolicy
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.viewmodel.ParsedTotpItem
import takagi.ru.monica.util.TotpGenerator

class BitwardenTwoFactorDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var busy by mutableStateOf(false)
    private var code by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var submitted = 0
    private var emailRequests = 0
    private var sendingEmail by mutableStateOf(false)
    private var nowSeconds = 59L
    private val totp = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = "Vaultwarden", accountName = "fixture@example.test")
    private fun show(methods: List<Int>, large: Boolean = false) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(config) }
        compose.setContent {
            var selected by remember { mutableIntStateOf(methods.singleOrNull() ?: -1) }
            var picker by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                MonicaTheme(darkTheme = large) {
                    if (picker) BitwardenTotpPickerDialog(listOf(
                        ParsedTotpItem(SecureItem(id=1,itemType=ItemType.TOTP,title="Vaultwarden fixture",itemData=""),totp),
                        ParsedTotpItem(SecureItem(id=2,itemType=ItemType.TOTP,title="Counter fixture",itemData=""),totp.copy(otpType=OtpType.HOTP)),
                    ), onCodeSelected = { code = it; picker = false }, onDismiss = { picker = false }, currentSeconds = { nowSeconds })
                    else TwoFactorDialog(methods, selected, { selected = it; code = "" }, code, { code = it },
                        onPickFromMonica = { picker = true },
                        statusMessage = status, onSendEmailCode = { emailRequests++; sendingEmail = true },
                        onConfirm = { submitted++; busy = true }, onDismiss = {}, busy = busy, sendingEmail = sendingEmail)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onNodeWithTag("bitwarden_two_factor_dialog").isDisplayed() }
    }
    private fun capture(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.filesDir,"bitwarden-2fa-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun codeCanBeRetriedAndBusyStatePreventsDuplicateSubmission() {
        show(listOf(7,0,1))
        compose.onNodeWithTag("bitwarden_two_factor_method_7").assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_two_factor_code").assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_two_factor_method_0").performClick()
        compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("000000")
        compose.onNodeWithTag("bitwarden_two_factor_submit").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("bitwarden_two_factor_code").assertIsNotEnabled()
        assertEquals(1,submitted)
        compose.runOnIdle { busy = false; status = "验证码错误，请重试" }
        compose.onNodeWithTag("bitwarden_two_factor_code").performTextReplacement("123456")
        compose.onNodeWithTag("bitwarden_two_factor_submit").assertIsEnabled()
        capture("retry")
        compose.onNodeWithTag("bitwarden_two_factor_submit").performClick()
        assertEquals(2,submitted)
    }
    @Test fun onlyInteractiveMethodsCannotSendAnArbitraryCode() {
        code = "123456"
        show(listOf(2,7))
        compose.onNodeWithTag("bitwarden_two_factor_method_2").assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_two_factor_method_7").assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_two_factor_code").assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_two_factor_submit").assertDoesNotExist()
        assertEquals(0,submitted)
        capture("unsupported")
    }
    @Test fun manyMethodsAtLargeTextRemainScrollableAndCanSwitchToEmail() {
        show(listOf(0,1,2,3,4,6,7,99), large=true)
        capture("methods")
        compose.onNodeWithTag("bitwarden_two_factor_method_1").performScrollTo().performClick()
        assertEquals(0,emailRequests)
        compose.onNodeWithTag("bitwarden_two_factor_send_email").performClick().assertIsNotEnabled()
        assertEquals(1,emailRequests)
        compose.runOnIdle { sendingEmail=false; status="验证码已发送" }
        compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("bitwarden_two_factor_change").assertIsDisplayed()
        compose.onNodeWithTag("bitwarden_two_factor_submit").assertIsDisplayed().assertIsEnabled()
        capture("large-dark")
    }

    @Test fun monicaPickerSearchesAndGeneratesFreshCodeOnlyAtSelection() {
        show(listOf(0,1))
        compose.onNodeWithTag("bitwarden_two_factor_method_0").performClick()
        compose.onNodeWithTag("bitwarden_two_factor_pick_totp").performClick()
        compose.onNodeWithTag("bitwarden_totp_2").assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_totp_search").performTextInput("fixture@example.test")
        capture("picker")
        compose.runOnIdle { nowSeconds=90L }
        compose.onNodeWithTag("bitwarden_totp_1").performClick()
        compose.onNodeWithTag("bitwarden_two_factor_code").assertTextContains(TotpGenerator.generateOtp(totp,currentSeconds=90L))
        assertEquals(0,submitted)
        capture("picked-totp")
    }
}
