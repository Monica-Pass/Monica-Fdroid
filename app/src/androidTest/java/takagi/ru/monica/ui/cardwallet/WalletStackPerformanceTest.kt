package takagi.ru.monica.ui.cardwallet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.BankCardData

@RunWith(AndroidJUnit4::class)
class WalletStackPerformanceTest {
    @get:Rule val compose = createComposeRule()
    private var result: List<WalletStackListEntry> = emptyList()

    private fun cards(count: Int) = List(count) { index ->
        val id = index.toLong() + 1
        WalletListItem(id, WalletListItemType.BANK_CARD,
            SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "{}"),
            bankCardData = BankCardData(cardNumber = "411111111111${id.toString().padStart(4, '0')}",
                cardholderName = "MONICA DEMO", expiryMonth = "09", expiryYear = "2030", bankName = "Demo bank"))
    }

    private fun save(name: String, json: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("wallet-performance"), name).writeText(json.toString(2))
    }

    @Test
    fun compareCompleteKotlinAndNativeProjections() {
        val measurements = JSONArray()
        for (size in listOf(64, 256, 1_000, 10_000)) {
            val rows = cards(size).shuffled(Random(42))
            for (shape in listOf("many", "single")) {
                val stacks = if (shape == "many") (0 until size / 20).map { group ->
                    WalletStack("group-$group", (1L..16L).map { group * 20L + it }.reversed())
                } else listOf(WalletStack("large", (1L..size * 4L / 5).toList().reversed()))
                for (selection in listOf(false, true)) {
                    val kotlin = { result = projectWalletStacksKotlin(rows, stacks, selectionMode = selection) }
                    val native = { result = requireNotNull(projectWalletStacksNative(rows, stacks, selection)) }
                    assertEquals(projectWalletStacksKotlin(rows, stacks, selectionMode = selection),
                        requireNotNull(projectWalletStacksNative(rows, stacks, selection)))
                    repeat(10) { kotlin(); native() }
                    val kotlinTimes = mutableListOf<Double>()
                    val nativeTimes = mutableListOf<Double>()
                    repeat(21) { iteration ->
                        if (iteration % 2 == 0) {
                            kotlinTimes += measureNanoTime(kotlin) / 1_000_000.0
                            nativeTimes += measureNanoTime(native) / 1_000_000.0
                        } else {
                            nativeTimes += measureNanoTime(native) / 1_000_000.0
                            kotlinTimes += measureNanoTime(kotlin) / 1_000_000.0
                        }
                    }
                    assertTrue(result.isNotEmpty())
                    measurements.put(JSONObject().put("cards", size).put("shape", shape).put("selection", selection)
                        .put("kotlinMedianMs", kotlinTimes.sorted()[10]).put("nativeMedianMs", nativeTimes.sorted()[10])
                        .put("kotlinP95Ms", kotlinTimes.sorted()[19]).put("nativeP95Ms", nativeTimes.sorted()[19])
                        .put("kotlinSamplesMs", JSONArray(kotlinTimes)).put("nativeSamplesMs", JSONArray(nativeTimes)))
                }
            }
        }
        save("projection-comparison.json", JSONObject().put("warmupPairs", 10).put("measuredPairs", 21)
            .put("measurements", measurements))
    }

    @Test
    fun benchmarkBrowserMemberReads() {
        val backing = cards(5_000)
        val reads = AtomicInteger()
        val countedCards = object : AbstractList<WalletListItem>() {
            override val size: Int get() = backing.size
            override fun get(index: Int): WalletListItem {
                reads.incrementAndGet()
                return backing[index]
            }
        }
        val entry = WalletStackListEntry.Stack(WalletStack("large", backing.map { it.id }), countedCards)
        var focused by mutableLongStateOf(1L)
        compose.setContent {
            MaterialTheme {
                WalletStackOverlayHost {
                    WalletStackBrowser(entry, null, focused, false,
                        onOpened = {}, onFocusedCardChanged = { focused = it },
                        onCollapseStart = {}, onRevealCover = {}, onDismiss = {},
                        onOpenCard = {}, onManage = {})
                }
            }
        }
        compose.waitForIdle()
        val before = reads.get()
        repeat(3) {
            compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertTrue(focused > 1)
        save("browser-optimized.json", JSONObject().put("cards", backing.size)
            .put("swipes", 3).put("memberReads", reads.get() - before).put("focusedCard", focused))
        assertTrue("Swiping must not repeatedly scan all 5,000 members", reads.get() - before < 2_000)
    }
}
