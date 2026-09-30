package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.EmbeddedWalletAccess
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.ui.components.EmbeddedWalletSavedContent
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeApiTokenAssetsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeWalletRendersRealFaceAndFileRowsWithoutARoomParent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(32, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val fileBytes = "Synthetic native attachment".toByteArray()
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "Native copied card",
            itemData = """{"cardNumber":"4242424242424242","cardholderName":"TEST CARD","expiryMonth":"09","expiryYear":"2030","cardFace":{"imageAttachmentName":"wallet-face","displayMode":"ALL"}}"""))
            .withAssets(listOf(
                EmbeddedWalletContent.Asset("wallet-face", "face.png", "image/png", EmbeddedWalletContent.AssetRole.CARD_FACE,
                    bytes.size.toLong(), NativeApiTokenAssets.digest(bytes)),
                EmbeddedWalletContent.Asset("wallet-file", "Statement.txt", "text/plain", EmbeddedWalletContent.AssetRole.ATTACHMENT,
                    fileBytes.size.toLong(), NativeApiTokenAssets.digest(fileBytes))))
        val reads = AtomicInteger()
        val access = EmbeddedWalletAccess.openNative(context, snapshot) { name ->
            reads.incrementAndGet()
            when (name) { "wallet-face" -> bytes.copyOf(); "wallet-file" -> fileBytes.copyOf(); else -> error("Unknown asset") }
        }
        assertNull(access.owner)
        val visible = mutableStateOf(true)
        try {
            compose.setContent { if (visible.value) MonicaTheme { EmbeddedWalletSavedContent(snapshot, nativeAccess = access, initiallyOpen = true) } }
            compose.waitUntil(10_000) { reads.get() > 0 }
            compose.onNodeWithTag("embedded_wallet_detail").assertExists()
            compose.onNodeWithText("Statement.txt").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("native_token_attachments").assertExists()
            val output = ByteArrayOutputStream()
            kotlinx.coroutines.runBlocking { access.copyTo("wallet-file", output) }
            assertArrayEquals(fileBytes, output.toByteArray())
        } finally { compose.runOnIdle { visible.value = false }; access.close(); bytes.fill(0); fileBytes.fill(0) }
    }
}
