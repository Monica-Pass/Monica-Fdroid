package takagi.ru.monica.attachments

import androidx.compose.material.icons.filled.Home
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.*
import takagi.ru.monica.attachments.ui.*
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.ui.bringAboveFloatingActions

class PasswordDetailM3eTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String, tag: String = "detail_test") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "password-detail-m3e/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun file(id: Long, state: AttachmentDownloadState = AttachmentDownloadState.DOWNLOADED) = Attachment(
        id = id, parentPasswordId = 1, source = "LOCAL", fileName = "说明-$id.pdf", mimeType = "application/pdf",
        sizeBytes = 2048, downloadState = state.name, createdAt = 0, updatedAt = 0)

    @Test fun connectedFieldsRemainReadableAndCopyMenuWorks() {
        compose.setContent { MaterialTheme { Surface {
            val context = LocalContext.current
            Column(Modifier.fillMaxSize().testTag("detail_test").verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicInfoCard(PasswordEntry(title = "示例账户", username = "example@domain.test", password = "", website = ""),
                    context, true, "Monica Example", null)
                WebsiteCard(listOf("https://domain.test", "https://accounts.domain.test/recovery"), context)
                NotesCard("恢复说明\n这里保留多行文本，内容自然撑开，不截断。")
                TimeInfoCard("2026/09/30 10:30", "2026/09/30 11:20")
            }
        } } }
        compose.onNodeWithText("example@domain.test").assertIsDisplayed()
        screenshot("fields-large-light")
        compose.onNodeWithText("example@domain.test").performClick()
        compose.onAllNodesWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.copy))
            .onFirst().assertExists()
    }

    @Test fun carouselScrollRetryAndBusyStates() {
        var opened = 0L
        var items by mutableStateOf(listOf(file(1, AttachmentDownloadState.FAILED), file(2), file(3, AttachmentDownloadState.DOWNLOADING)))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            Box(Modifier.fillMaxWidth().padding(12.dp).testTag("detail_test")) {
                AttachmentCarousel(items, emptyMap(), { error("Documents must not load image bytes") }, { opened = it.id })
            }
        } } }
        screenshot("carousel-large-dark")
        compose.onNodeWithTag("attachment_tile_1").performClick()
        compose.runOnIdle { assertEquals(1L, opened) }
        compose.onNodeWithTag("attachment_carousel_track").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        screenshot("carousel-after-swipe-dark")
        compose.onNodeWithText("3 / 3").assertExists()
        compose.onNodeWithTag("attachment_tile_3").assertIsNotEnabled()
        compose.runOnIdle { items = listOf(file(4)) }
        compose.onNodeWithTag("attachment_tile_4").performClick()
        compose.runOnIdle { assertEquals(4L, opened) }
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithTag("attachment_carousel").assertDoesNotExist()
    }

    @Test fun encryptedImageThumbnailAndRealPreview() {
        val fixture = TransferFixture()
        val facade = AttachmentContainer.facade(fixture.context)
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(80, 70, 150)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val ownerId = runBlocking { fixture.db.passwordEntryDao().insertPasswordEntry(
            PasswordEntry(title = fixture.prefix, username = "", password = "", website = "")) }
        val owner = AttachmentOwner.password(ownerId)
        try {
            val saved = runBlocking { facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                owner, AttachmentSource.LOCAL, "synthetic.png", "image/png", bytes, true)) }
            assertArrayEquals(bytes, runBlocking { facade.readCachedImageBytes(saved.id) })
            assertNotNull(saved.wrappedCek)
            compose.setContent { MaterialTheme { Surface {
                Box(Modifier.fillMaxWidth().padding(12.dp).testTag("detail_test")) {
                    AttachmentsDetailSection(owner = owner, displayFileNames = mapOf("synthetic.png" to "设备照片.png"))
                }
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("attachment_tile_${saved.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            screenshot("image-large-light")
            compose.onNodeWithTag("attachment_tile_${saved.id}").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(fixture.context.getString(R.string.attachment_save_to_device)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("设备照片.png").onFirst().assertExists()
            compose.onNodeWithContentDescription(fixture.context.getString(R.string.close)).performClick()
        } finally {
            runBlocking { fixture.close() }
        }
    }

    @Test fun pendingRemoteImageDoesNotDownloadForThumbnail() {
        val fixture = TransferFixture()
        val facade = AttachmentContainer.facade(fixture.context)
        val ownerId = runBlocking { fixture.db.passwordEntryDao().insertPasswordEntry(
            PasswordEntry(title = fixture.prefix, username = "", password = "", website = "")) }
        try {
            val id = runBlocking { fixture.db.attachmentDao().insert(file(0, AttachmentDownloadState.PENDING).copy(
                parentPasswordId = ownerId, source = "BITWARDEN", mimeType = "image/png", fileName = "remote.png")) }
            assertNull(runBlocking { facade.readCachedImageBytes(id) })
            assertEquals(AttachmentDownloadState.PENDING, runBlocking { facade.getById(id) }!!.downloadStateEnum)
        } finally { runBlocking { fixture.close() } }
    }

    @Test fun walletStackExpandsInSavedOrderAndCollapses() {
        val parent = PasswordEntry(id = 77, title = "Parent", username = "", password = "", website = "",
            city = "上海", addressLine = "完整街道地址", creditCardNumber = "4242424242424242")
        val address = takagi.ru.monica.data.model.PasswordWalletProjection.address(parent, emptyList(), "地址信息")
        val payment = takagi.ru.monica.data.model.PasswordWalletProjection.payment(parent, emptyList(), "支付信息")
        compose.setContent { MaterialTheme { Surface {
            Column(Modifier.fillMaxWidth().padding(12.dp).testTag("detail_test")) {
                takagi.ru.monica.ui.components.DetailWalletStack(listOf(address, payment), parent, "卡片信息")
            }
        } } }
        screenshot("wallet-stack-large")
        compose.onNodeWithTag("detail_wallet_cover").performClick()
        compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
        compose.onNodeWithTag("wallet_stack_card_1").assertContentDescriptionEquals("地址信息")
        compose.onNodeWithTag("wallet_stack_card_2").assertContentDescriptionEquals("支付信息")
        compose.onNodeWithTag("wallet_stack_collapse").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun walletInformationOpensCompleteFields() {
        val parent = PasswordEntry(id = 77, title = "Parent", username = "", password = "", website = "",
            city = "上海", addressLine = "完整街道地址", email = "contact@example.invalid")
        val address = takagi.ru.monica.data.model.PasswordWalletProjection.address(parent, emptyList(), "地址信息")
        compose.setContent { MaterialTheme { Surface {
            Column(Modifier.fillMaxWidth().padding(12.dp).testTag("detail_test")) {
                takagi.ru.monica.ui.components.DetailWalletStack(listOf(address), parent, "卡片信息")
            }
        } } }
        screenshot("wallet-summary-large")
        compose.onNodeWithTag("password_address_face").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("embedded_wallet_detail").fetchSemanticsNodes().isNotEmpty() }
        assertWalletField("完整街道地址")
        assertWalletField("contact@example.invalid")
        takagi.ru.monica.ui.pressFocusedBack()
        compose.onNodeWithTag("embedded_wallet_detail").assertDoesNotExist()
    }

    private fun assertWalletField(value: String) {
        compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag("embedded_wallet_detail")))
            .onLast().performScrollTo().assertIsDisplayed()
    }

    @Test fun realPasswordScreenKeepsWalletFieldsAccessible() {
        val fixture = TransferFixture()
        val model = takagi.ru.monica.viewmodel.PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(fixture.db.customFieldDao()),
            strings = takagi.ru.monica.utils.AppLocaleStringResolver(fixture.context))
        try {
            val id = runBlocking {
                val id = fixture.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                    title = fixture.prefix, username = "stack@example.invalid", website = "",
                    password = fixture.security.encryptData("synthetic-secret"),
                    creditCardNumber = "4242424242424242", creditCardHolder = "EXAMPLE USER",
                    email = "contact@example.invalid", phone = "13800138000", city = "上海", addressLine = "完整街道地址"))
                model.saveCustomFieldsForEntry(id, listOf(takagi.ru.monica.data.CustomFieldDraft(id = -1,
                    title = takagi.ru.monica.data.model.EntryContentFields.ORDER, value = "CONTACT,PAYMENT,ADDRESS")))
                id
            }
            compose.setContent { MaterialTheme {
                PasswordDetailScreen(model, passwordId = id, biometricEnabled = false,
                    onNavigateBack = {}, onEditPassword = {})
            } }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("stack@example.invalid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("synthetic-secret").assertDoesNotExist()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail_wallet_stack"))
            val cover = compose.onNodeWithTag("detail_wallet_cover")
            compose.bringAboveFloatingActions(cover, compose.onNode(hasScrollToIndexAction()))
            cover.performClick()
            compose.onNodeWithTag("wallet_stack_card_1").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("embedded_wallet_detail").fetchSemanticsNodes().isNotEmpty() }
            assertWalletField("contact@example.invalid")
            assertWalletField("完整街道地址")
            takagi.ru.monica.ui.pressFocusedBack()
            compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
        } finally {
            model.viewModelScope.cancel()
            runBlocking { fixture.close() }
        }
    }

}
