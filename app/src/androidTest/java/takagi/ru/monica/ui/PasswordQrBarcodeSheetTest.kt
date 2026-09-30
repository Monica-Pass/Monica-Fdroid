package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

class PasswordQrBarcodeSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }

    @Test fun savedPasswordCardDirectlyOpensQrAndBarcodeWithoutChangingStoredFields() {
        val value = " 123456789012 "
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("Membership code", mapOf("content" to value))
        val id = runBlocking {
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "QR sheet fixture", username = "fixture", website = "",
                password = security.encryptData("synthetic-password")))
            // Use the actual save path: Room's protected field representation differs from
            // the encrypted native-vault transport and must not be encrypted a second time.
            model.appendPasswordQrTemplate(id, block)
            id
        }
        val before = runBlocking { db.customFieldDao().getFieldsByEntryIdSync(id) }
        compose.setContent { CompositionLocalProvider(LocalUiSecurityManager provides security) { MaterialTheme {
            PasswordDetailScreen(model, passwordId = id, biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
        } } }
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        val card = "block_card_${PasswordContentBlocks.token(block.id)}"
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag(card))
        compose.bringAboveFloatingActions(compose.onNodeWithTag(card), list)
        compose.onNodeWithTag(card).performClick()
        compose.onNodeWithTag("content_block_detail_sheet").assertExists()
        assertCode(value, FieldBarcodeFormat.QR_CODE)
        capture("qr-sheet")
        compose.onNodeWithTag("qr_content_format_CODE_128").performClick().assertIsSelected()
        assertCode(value, FieldBarcodeFormat.CODE_128)
        capture("barcode-sheet")
        compose.onNodeWithTag("qr_content_save_image").assertIsEnabled()
        repeat(2) {
            compose.onNodeWithTag("qr_content_format_QR_CODE").performClick()
            compose.onNodeWithTag("qr_content_format_CODE_128").performClick()
        }
        assertCode(value, FieldBarcodeFormat.CODE_128)
        Espresso.pressBack()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("content_block_detail_sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(before, runBlocking { db.customFieldDao().getFieldsByEntryIdSync(id) })
    }

    @Test fun unsupportedBarcodeKeepsUnicodeContentAndCanReturnToQrInDarkLargeText() {
        val value = "会员卡🎁-1234"
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("Unicode code", mapOf("content" to value))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PasswordContentBlockDetail(PasswordContentBlocks.Stored(PasswordContentBlocks.token(block.id), block), {})
            }
        } }
        assertCode(value, FieldBarcodeFormat.QR_CODE)
        compose.onNodeWithTag("qr_content_format_CODE_128").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("qr_content_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("qr_content_error").assertTextContains(context.getString(R.string.field_barcode_linear_failed))
        compose.onNodeWithTag("qr_content_save_image").assertIsNotEnabled()
        compose.onNodeWithTag("block_detail_content").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.show_password)).performClick()
        compose.onNodeWithText(value).assertExists()
        capture("unsupported-barcode-dark")
        compose.onNodeWithTag("qr_content_format_QR_CODE").performScrollTo().performClick()
        assertCode(value, FieldBarcodeFormat.QR_CODE)
        assertEquals(value, block.value("content"))
    }

    @Test fun reopeningReadsCurrentTemplateValuesAndMissingSecretsShowError() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("Dynamic code", mapOf(
            "content" to "%PASSWORD%", "mode" to "template", "templateVersion" to "1"))
        val raw = block.raw.toString()
        var secret: String? = "first-123"
        var visible by mutableStateOf(true)
        fun actions(): QrTemplateActions = QrTemplateActions(
            read = { PasswordQrTemplate.Values(mapOf("PASSWORD" to secret)) }, save = { fail("Preview must not save") }, forEntry = { actions() })
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalQrTemplateActions provides actions()) {
            Column {
                Button(onClick = { visible = true }) { Text("Open fixture") }
                if (visible) PasswordContentBlockDetail(PasswordContentBlocks.Stored(PasswordContentBlocks.token(block.id), block), { visible = false })
            }
        } } }
        assertCode("first-123", FieldBarcodeFormat.QR_CODE)
        Espresso.pressBack()
        compose.waitUntil(5000) { !visible }
        secret = "second-456"
        compose.onNodeWithText("Open fixture").performClick()
        assertCode("second-456", FieldBarcodeFormat.QR_CODE)
        compose.onNodeWithTag("qr_content_format_CODE_128").performClick()
        assertCode("second-456", FieldBarcodeFormat.CODE_128)
        Espresso.pressBack()
        compose.waitUntil(5000) { !visible }
        secret = null
        compose.onNodeWithText("Open fixture").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("qr_content_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("qr_content_error").assertTextContains(context.getString(R.string.qr_template_field_error, "PASSWORD"))
        compose.onNodeWithTag("qr_content_save_image").assertIsNotEnabled()
        assertEquals(raw, block.raw.toString())
    }

    private fun assertCode(value: String, format: FieldBarcodeFormat) {
        val description = context.getString(format.labelRes)
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithContentDescription(description).assertIsDisplayed()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(value, result.text)
        assertEquals(if (format == FieldBarcodeFormat.QR_CODE) BarcodeFormat.QR_CODE else BarcodeFormat.CODE_128, result.barcodeFormat)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val folder = java.io.File(context.filesDir, "qr-content-sheet-315").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
