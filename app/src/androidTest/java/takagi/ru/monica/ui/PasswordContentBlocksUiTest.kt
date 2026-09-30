package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.components.PasswordContentBlockCard
import takagi.ru.monica.ui.components.PasswordContentBlockDetail
import takagi.ru.monica.ui.components.EntryContentActions
import takagi.ru.monica.ui.components.DirectDragState
import takagi.ru.monica.ui.components.rememberDirectReorderScroller
import takagi.ru.monica.ui.components.reorderableContentItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File

class PasswordContentBlocksUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var editId by mutableStateOf<Long?>(null)
    private var savedId: Long? = null
    private var showDetail by mutableStateOf(false)
    private val gpgFixture by lazy { takagi.ru.monica.utils.GpgKeyGenerator.generate("Fixture", "fixture@example.org") }
    private fun fieldFixture(index: Int, kind: PasswordContentBlocks.Kind, field: String): String = when {
        field == "url" || field == "api_base" -> "https://api.example.org/$index"
        kind == PasswordContentBlocks.Kind.GPG_KEY -> when (field) {
            "publicKey" -> gpgFixture.publicKey
            "privateKey" -> gpgFixture.privateKey
            "fingerprint" -> gpgFixture.fingerprint
            "userId" -> gpgFixture.userId
            else -> "synthetic-$index-$field"
        }
        else -> "synthetic-$index-$field"
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }

    @Test fun distinctKindsRepeatSaveReopenAndDisplayWithoutExposingSecrets() {
        show()
        val expected = PasswordContentBlocks.Kind.entries + PasswordContentBlocks.Kind.API_KEY
        expected.forEachIndexed { index, kind ->
            add(kind)
            compose.onNodeWithTag("block_title").performTextReplacement("Fixture $index")
            PasswordContentBlocks.editableKeys(kind).forEach { field ->
                compose.onNodeWithTag("block_field_$field").performScrollTo().performTextReplacement(fieldFixture(index, kind, field))
            }
            closeFocusedKeyboard()
            compose.onNodeWithTag("block_save").assertIsDisplayed().performClick()
        }
        scroll("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_NOTES").performScrollTo().performClick()
        compose.onNodeWithTag("password_content_notes").performScrollTo().performTextReplacement("Mixed content note")
        closeFocusedKeyboard()
        pressFocusedBack()
        capture("editor")
        save()
        assertEquals("Mixed content note", runBlocking { db.passwordEntryDao().getPasswordEntryById(savedId!!)!!.notes })
        val blocks = stored().map { it.block!! }
        assertEquals(expected, blocks.map { it.kind })
        blocks.forEachIndexed { index, block -> PasswordContentBlocks.editableKeys(block.kind).forEach { key ->
            assertEquals(fieldFixture(index, block.kind, key), block.value(key))
        } }
        compose.runOnIdle { editId = savedId; savedId = null }
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Blocks fixture") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val first = blocks.first()
        scroll("block_card_${PasswordContentBlocks.token(first.id)}").performClick()
        compose.onNodeWithTag("block_field_key").assertTextContains("synthetic-0-key")
        compose.onNodeWithTag("block_field_key").performTextReplacement("discarded edit")
        closeFocusedKeyboard()
        pressFocusedBack()
        save()
        assertEquals("synthetic-0-key", stored().first().block!!.value("key"))
        compose.runOnIdle { showDetail = true }
        val card = "block_card_${PasswordContentBlocks.token(first.id)}"
        val details = compose.onNode(hasScrollAction())
        details.performScrollToNode(hasTestTag(card))
        compose.bringAboveFloatingActions(compose.onNodeWithTag(card), details)
        compose.onNodeWithTag(card).performClick()
        compose.onNodeWithTag("block_detail_key").assertIsDisplayed()
        compose.onNodeWithText("synthetic-0-key").assertDoesNotExist()
        capture("detail-hidden", "block_detail_key")
        compose.onNodeWithTag("block_detail_key").performClick()
        compose.onNodeWithText(context.getString(R.string.show_password)).performClick()
        compose.onNodeWithText("synthetic-0-key").assertIsDisplayed()
    }

    @Test fun cancellingNewBlockDoesNotCreatePlaceholder() {
        show(); add(PasswordContentBlocks.Kind.API_TOKEN)
        compose.onNodeWithTag("block_title").performTextReplacement("Cancelled")
        closeFocusedKeyboard()
        pressFocusedBack()
        save(); assertTrue(stored().isEmpty())
    }

    @Test fun apiKeyWithPlainEndpointSavesReopensAndEditsWithoutLosingContent() {
        show(); add(PasswordContentBlocks.Kind.API_KEY)
        compose.onNodeWithTag("block_title").performTextReplacement("API fixture")
        compose.onNodeWithTag("block_field_key").performTextReplacement("合成密钥-test")
        compose.onNodeWithTag("block_field_url").performTextReplacement("djdjdmd")
        compose.onNodeWithTag("block_field_notes").performScrollTo().performTextReplacement("合成备注")
        closeFocusedKeyboard()
        compose.onNodeWithTag("block_save").performClick()
        compose.onNodeWithTag("block_save").assertDoesNotExist()
        save()
        val first = stored().single().block!!
        assertEquals("djdjdmd", first.value("url"))
        assertEquals("合成密钥-test", first.value("key"))
        assertEquals("合成备注", first.value("notes"))
        compose.runOnIdle { editId = savedId; savedId = null }
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Blocks fixture") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        scroll("block_card_${PasswordContentBlocks.token(first.id)}").performClick()
        compose.onNodeWithTag("block_field_url").assertTextContains("djdjdmd")
        compose.onNodeWithTag("block_field_key").assertTextContains("合成密钥-test")
        compose.onNodeWithTag("block_field_url").performTextReplacement("localhost:11434/v2")
        closeFocusedKeyboard()
        compose.onNodeWithTag("block_save").performClick()
        save()
        val edited = stored().single().block!!
        assertEquals(first.id, edited.id)
        assertEquals("localhost:11434/v2", edited.value("url"))
        assertEquals(first.value("key"), edited.value("key"))
        assertEquals(first.value("notes"), edited.value("notes"))
    }

    @Test fun missingApiKeyShowsRequiredFieldAndKeepsDraft() {
        show(); add(PasswordContentBlocks.Kind.API_KEY)
        compose.onNodeWithTag("block_title").performTextReplacement("Incomplete API fixture")
        compose.onNodeWithTag("block_field_url").performTextReplacement("local-service")
        closeFocusedKeyboard()
        compose.onNodeWithTag("block_save").performClick()
        compose.onNodeWithTag("block_save").assertExists()
        compose.onNodeWithTag("block_field_key").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.api_key_required)).assertExists()
        compose.onNodeWithText(context.getString(R.string.content_block_save_error)).assertDoesNotExist()
        compose.onNodeWithTag("block_title").assertTextContains("Incomplete API fixture")
        compose.onNodeWithTag("block_field_url").assertTextContains("local-service")
    }

    @Test fun longPressDragAndConfirmedDeleteActOnTheWholeBlock() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_TOKEN).edited("Drag fixture", mapOf("token" to "synthetic"))
        val stored = PasswordContentBlocks.Stored(PasswordContentBlocks.token(block.id), block)
        val neighbourBlock = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY).edited("Neighbour", mapOf("key" to "unchanged"))
        val neighbour = PasswordContentBlocks.Stored(PasswordContentBlocks.token(neighbourBlock.id), neighbourBlock)
        var order by mutableStateOf(listOf(stored, neighbour))
        var deletes = 0
        compose.setContent { MaterialTheme {
            val list = rememberLazyListState()
            val dragState = remember { DirectDragState() }
            val reorder = rememberReorderableLazyListState(list, scroller = rememberDirectReorderScroller(list)) { from, to ->
                val sourceIndex = order.indexOfFirst { it.token == from.key }
                val targetIndex = order.indexOfFirst { it.token == to.key }
                if (sourceIndex >= 0 && targetIndex >= 0) order = order.toMutableList().apply { add(targetIndex, removeAt(sourceIndex)) }
            }
            // Production cards receive their drag handle from the surrounding reorderable item.
            // EntryContentActions are accessibility/menu actions, not the pointer gesture owner.
            LazyColumn(Modifier.width(360.dp).height(400.dp).padding(12.dp), state = list,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                order.forEachIndexed { index, item ->
                    reorderableContentItem(item.token, reorder, true, dragState, order.map { it.token }) {
                        PasswordContentBlockCard(item, EntryContentActions(groupIndex = index, groupCount = order.size,
                            remove = { deletes++; order = order.filterNot { it.token == item.token } }), onClick = {})
                    }
                }
            }
        } }
        val card = compose.onNodeWithTag("block_card_${stored.token}")
        val distance = compose.onNodeWithTag("block_card_${neighbour.token}").fetchSemanticsNode().boundsInRoot.center.y -
            card.fetchSemanticsNode().boundsInRoot.center.y
        card.performTouchInput {
            down(centerLeft + Offset(35f, 0f))
            advanceEventTime(650)
            moveBy(Offset(0f, distance), 500)
        }
        compose.mainClock.advanceTimeBy(300)
        card.performTouchInput { up() }
        compose.waitUntil(5000) { order.map { it.token } == listOf(neighbour.token, stored.token) }
        compose.runOnIdle {
            assertEquals(block.raw, order.last().block!!.raw)
            assertEquals(neighbourBlock.raw, order.first().block!!.raw)
        }
        val more = compose.onNode(hasContentDescription(context.getString(R.string.more_options)) and
            hasAnyAncestor(hasTestTag("block_card_${stored.token}")))
        more.performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        more.performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(listOf(neighbour.token), order.map { it.token }) }
    }

    @Test fun oversizedQrRemainsReadableAndShowsAnErrorInsteadOfLoadingForever() {
        val content = "测试二维码内容".repeat(1500)
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("Large QR", mapOf("content" to content))
        compose.setContent { MaterialTheme {
            PasswordContentBlockDetail(PasswordContentBlocks.Stored(PasswordContentBlocks.token(block.id), block), onDismiss = {})
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(context.getString(R.string.content_block_qr_error)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("qr_content_save_image").assertIsNotEnabled()
        compose.onNodeWithTag("block_detail_content").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.show_password)).performClick()
        compose.onNodeWithText(content).assertExists()
    }

    private fun show() = compose.setContent {
        key(editId, showDetail) {
            CompositionLocalProvider(LocalUiSecurityManager provides security) {
                MaterialTheme {
                    if (showDetail) takagi.ru.monica.ui.screens.PasswordDetailScreen(model, passwordId = requireNotNull(savedId),
                        biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
                    else AddEditPasswordScreen(viewModel = model, passwordId = editId, initialStorageExplicit = true,
                        initialDraft = if (editId == null) AddEditPasswordInitialDraft(title = "Blocks fixture", username = "alice", password = "synthetic-password") else null,
                        onSaveCompleted = { savedId = it }, onNavigateBack = {})
                }
            }
        }
    }
    private fun add(kind: PasswordContentBlocks.Kind) {
        scroll("password_content_add").performClick()
        if (kind == PasswordContentBlocks.Kind.GPG_KEY) compose.onNodeWithText(context.getString(R.string.content_block_more)).performScrollTo().performClick()
        try {
            compose.onNodeWithTag("block_add_${kind.name}").performScrollTo().performClick()
        } catch (failure: AssertionError) {
            capture("failed-add-${kind.name}")
            throw failure
        }
    }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        val editor = compose.onNode(hasTestTag("password_classic_editor") or hasTestTag("password_content_editor"))
        editor.performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).also { compose.bringAboveFloatingActions(it, editor) }
    }
    private fun save() {
        closeFocusedKeyboard()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20000) { savedId != null }
    }
    private fun stored() = PasswordContentBlocks.read(runBlocking {
        db.customFieldDao().getFieldsByEntryIdSync(requireNotNull(savedId)).map {
            CustomFieldDraft(id = it.id, title = it.title, value = security.decryptDataIfMonicaCiphertext(it.value), isProtected = it.isProtected)
        }
    })
    private fun capture(name: String, descendantTag: String? = null) {
        val dir = File(context.getExternalFilesDir(null), "credential-blocks-315").apply { mkdirs() }
        val root = if (descendantTag == null) compose.onAllNodes(isRoot()).onLast()
            else compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(descendantTag)))
        File(dir, "$name.png").outputStream().use { root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
