package takagi.ru.monica.ui.vaultv2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import kotlin.system.measureNanoTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.rustcore.RustVaultPickerCore

@RunWith(AndroidJUnit4::class)
class VaultOverviewPickerNativeTest {
    private fun rows(count: Int): List<OverviewPickerEntry> = List(count) { i ->
        val title = if (i % 7 == 0) "日常卡 招商银行 München АЛИСА" else "Account $i"
        OverviewPickerEntry(
            VaultV2Item("test:$i", VaultV2ItemType.PASSWORD, title, "", false, "$i", emptyList()),
            "test:$i", "db:${i % 4}", i % 4, "user$i@example.test", "", null,
            normalizePickerQuery("$title\u0000user$i@example.test\u0000•••• ${i % 10000}"),
        )
    }

    private val sources = (0..3).associate { "db:$it" to it }

    @Test fun realJniMatchesFallbackForUnicodeAccountsAndEveryDatabaseAndReleasesItsIndex() {
        for (size in listOf(0, 1, 64, 512, 4096)) {
            val rows = rows(size)
            val frame = encodeOverviewPickerMetadata(rows)
            val handle = requireNotNull(RustVaultPickerCore.open(frame))
            PreparedOverviewPicker(rows, sources, handle).use { picker ->
                assertTrue(picker.usesNative)
                for (query in listOf("", "   ", "  USER1@  ", "招商", "MÜNCHEN", "АЛИСА", "•••• 12", "not-found")) {
                    for (scope in listOf("all", "db:0", "db:1", "db:2", "db:3", "missing")) {
                        assertEquals("$size/$query/$scope", picker.filterKotlin(query, scope), picker.filter(query, scope))
                    }
                }
                picker.close()
                picker.close()
                assertNull(RustVaultPickerCore.filter(handle, "user", -1))
                assertEquals(picker.filterKotlin("user", "all"), picker.filter("user", "all"))
            }
            assertNull(RustVaultPickerCore.open(frame.copyOf(frame.size - 1)))
        }
    }

    @Test fun indexCreationAndSearchAreRefusedOnTheAnimationThread() {
        val frame = encodeOverviewPickerMetadata(rows(512))
        val handle = requireNotNull(RustVaultPickerCore.open(frame))
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertNull(RustVaultPickerCore.open(frame))
                assertNull(RustVaultPickerCore.filter(handle, "account", -1))
            }
        } finally {
            RustVaultPickerCore.close(handle)
        }
    }

    @Test fun priorityPrefixKeepsNativeSearchAndDatabaseMappingCorrect() {
        val items = buildVaultV2PasswordItems((1L..1024L).map { id -> PasswordEntry(
            id = id, title = "Account $id", username = "user$id@example.test", password = "", website = "",
            bitwardenVaultId = if (id % 2 == 0L) 2 else null, createdAt = Date(10),
        ) })
        val frequent = listOf(items[1023], items[1018], items[511])
        prepareOverviewPicker(items, listOf(VaultOverviewSource("local", "Personal", "Monica"),
            VaultOverviewSource("bitwarden:2", "Work", "Bitwarden")), cards = false,
            priorityIdentities = frequent.map { it.overviewIdentity() }).use { picker ->
            assertTrue(picker.usesNative)
            assertEquals(frequent, picker.filter("ACCOUNT", "all").take(3).map { it.item })
            for (query in listOf("", "  ACCOUNT  ", "user1024@", "101", "missing")) {
                for (scope in listOf("all", "local", "bitwarden:2", "missing")) {
                    assertEquals("$query/$scope", picker.filterKotlin(query, scope), picker.filter(query, scope))
                }
            }
            assertEquals(listOf(items.last()), picker.filter("user1024@", "bitwarden:2").map { it.item })
            assertTrue(picker.filter("user1024@", "local").isEmpty())
        }
    }

    @Test fun measuresCachedSearchIncludingJniValidationAndResultMapping() {
        val results = JSONArray()
        for (size in listOf(64, 512, 1000, 10_000, 50_000)) {
            val rows = rows(size)
            var handle: Long? = null
            val buildMs = measureNanoTime { handle = RustVaultPickerCore.open(encodeOverviewPickerMetadata(rows)) } / 1_000_000.0
            PreparedOverviewPicker(rows, sources, requireNotNull(handle)).use { picker ->
                val queries = listOf("USER1", "招商", "missing", "account")
                fun median(native: Boolean, scope: String): Double {
                    fun search(i: Int) = if (native) picker.filter(queries[i % queries.size], scope)
                        else picker.filterKotlin(queries[i % queries.size], scope)
                    repeat(12) { search(it) }
                    return List(40) { i -> measureNanoTime { search(i) } / 1_000_000.0 }.sorted()[20]
                }
                results.put(JSONObject().put("rows", size).put("nativeIndexBuildMs", buildMs)
                    .put("kotlinAllMs", median(false, "all")).put("rustAllMs", median(true, "all"))
                    .put("kotlinScopeMs", median(false, "db:1")).put("rustScopeMs", median(true, "db:1")))
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("overview-verification"), "vault-picker-performance.json").writeText(results.toString(2))
    }

    @Test fun overviewSearchUsesTheNativeIndexForMixedItemsAndPreservesDatabaseScope() {
        val logins = buildVaultV2PasswordItems((1L..1024L).map { id -> PasswordEntry(
            id = id, title = "Account $id", username = "user$id@example.test", password = "", website = "",
            bitwardenVaultId = if (id % 2 == 0L) 2 else null,
        ) })
        val bank = SecureItem(id = 2048, title = "Everyday card", itemType = ItemType.BANK_CARD,
            itemData = CardWalletDataCodec.encodeBankCardData(BankCardData(
                cardNumber = "4111111111115678", bankName = "München Bank",
                cardholderName = "DEMO", expiryMonth = "09", expiryYear = "2030",
            )))
        val card = VaultV2Item("bank_card:2048", VaultV2ItemType.BANK_CARD, bank.title, "", false, "2048",
            emptyList(), secureItem = bank)
        prepareOverviewPicker(logins + card, listOf(VaultOverviewSource("local", "Personal", "Monica"),
            VaultOverviewSource("bitwarden:2", "Work", "Bitwarden")), cards = null).use { index ->
            assertTrue("The overview must use Rust above the native threshold", index.usesNative)
            for (query in listOf("", "USER1024@", "MÜNCHEN", "5678", "not-found")) {
                for (scope in listOf("all", "local", "bitwarden:2", "missing")) {
                    assertEquals("$query/$scope", index.filterKotlin(query, scope), index.filter(query, scope))
                }
            }
            assertEquals(listOf(card), index.filter("münchen", "local").map { it.item })
            assertTrue(index.filter("münchen", "bitwarden:2").isEmpty())
        }
    }
}
