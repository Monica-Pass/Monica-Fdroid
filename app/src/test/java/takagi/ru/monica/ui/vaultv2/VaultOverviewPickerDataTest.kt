package takagi.ru.monica.ui.vaultv2

import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardBrand
import takagi.ru.monica.data.model.CardWalletDataCodec

class VaultOverviewPickerDataTest {
    private val sources = listOf(VaultOverviewSource("local", "Personal", "Monica"),
        VaultOverviewSource("bitwarden:2", "Work", "Bitwarden"),
        VaultOverviewSource("keepass:3", "Locked", "KeePass", locked = true))

    private fun card(id: Long, bank: String, number: String, vault: Long? = null): VaultV2Item {
        val item = SecureItem(id = id, title = "Everyday", itemType = ItemType.BANK_CARD, createdAt = Date(10),
            bitwardenVaultId = vault, itemData = CardWalletDataCodec.encodeBankCardData(BankCardData(
                cardNumber = number, cardholderName = "Private holder", bankName = bank, cvv = "private-cvv",
                expiryMonth = "01", expiryYear = "2030", pin = "private-pin")))
        return VaultV2Item("bank_card:$id", VaultV2ItemType.BANK_CARD, item.title, "private notes", false, "$id",
            listOf("private searchable value"), secureItem = item)
    }

    private fun password(id: Long, username: String, vault: Long? = null) = buildVaultV2PasswordItems(listOf(
        PasswordEntry(id = id, title = "GitHub", username = username, password = "private-password", website = "",
            notes = "private-notes", bitwardenVaultId = vault, createdAt = Date(10)),
    )).single()

    @Test fun bankMetadataAndAccountSearchDistinguishDuplicateTitlesWithoutIndexingSecrets() {
        val cards = listOf(card(1, "招商银行", "6222021234561234"), card(2, "Work Bank", "4111111111114321", vault = 2))
        prepareOverviewPicker(cards, sources, true, openNative = { null }).use { picker ->
            assertEquals("招商银行", picker.rows[0].detail)
            assertEquals("1234", picker.rows[0].cardLast4)
            assertEquals(CardBrand.UNIONPAY, picker.rows[0].cardBrand)
            assertEquals(CardBrand.VISA, picker.rows[1].cardBrand)
            assertEquals(listOf(cards[0]), picker.filter("招商", "all").map { it.item })
            assertEquals(listOf(cards[1]), picker.filter(" 4321 ", "bitwarden:2").map { it.item })
            assertTrue(picker.filter("4321", "local").isEmpty())
            val frame = encodeOverviewPickerMetadata(picker.rows).toString(Charsets.UTF_8)
            listOf("6222021234561234", "4111111111114321", "private-cvv", "private-pin", "Private holder", "private notes")
                .forEach { assertFalse("Unexpected field: $it", frame.contains(it, ignoreCase = true)) }
        }
        val passwords = listOf(password(1, "Alice@example.test"), password(2, "MÜNCHEN.АЛИСА", 2))
        prepareOverviewPicker(passwords, sources, false, openNative = { null }).use { picker ->
            assertEquals("Alice@example.test", picker.rows.first().detail)
            assertEquals(listOf(passwords[0]), picker.filter("ALICE@", "all").map { it.item })
            assertEquals(listOf(passwords[1]), picker.filter("münchen.алиса", "all").map { it.item })
            assertTrue(picker.filter("private", "all").isEmpty())
        }
    }

    @Test fun preparationSeparatesWalletTypesAndSkipsLockedUnknownAndDuplicateItems() {
        val password = password(1, "alice")
        val locked = password(2, "locked").let { it.copy(passwordEntry = it.passwordEntry!!.copy(keepassDatabaseId = 3)) }
        val unknown = password(3, "unknown", vault = 99)
        val bank = card(4, "Bank", "4111111111111234")
        val document = bank.copy(key = "document:5", type = VaultV2ItemType.DOCUMENT,
            secureItem = bank.secureItem!!.copy(id = 5, itemType = ItemType.DOCUMENT))
        val address = bank.copy(key = "billing_address:6", type = VaultV2ItemType.BILLING_ADDRESS,
            secureItem = bank.secureItem!!.copy(id = 6, itemType = ItemType.BILLING_ADDRESS))
        val note = bank.copy(key = "note:7", title = "A note", type = VaultV2ItemType.NOTE, subtitle = "private-note-body",
            secureItem = bank.secureItem!!.copy(id = 7, itemType = ItemType.NOTE))
        val items = listOf(password, bank, locked, unknown, document, address, note, bank)
        prepareOverviewPicker(items, sources, true, openNative = { null }).use {
            assertEquals(listOf(bank, document, address), it.rows.map(OverviewPickerEntry::item))
            assertTrue(it.filter("", "keepass:3").isEmpty())
            assertTrue(it.filter("", "missing").isEmpty())
        }
        prepareOverviewPicker(items, sources, false, openNative = { null }).use {
            assertEquals(listOf(password, note), it.rows.map(OverviewPickerEntry::item))
            assertTrue(it.filter("private-note-body", "all").isEmpty())
            assertEquals(listOf(note), it.filter("a note", "all").map(OverviewPickerEntry::item))
        }
    }

    @Test fun damagedCardsStillAppearAndInvalidNativeIndicesCannotSelectAnotherDatabase() {
        val good = card(1, "Bank", "4111111111111234")
        val damaged = good.copy(key = "bank_card:2", secureItem = good.secureItem!!.copy(id = 2, itemData = "damaged"))
        val remote = card(3, "Work", "4111111111119876", vault = 2)
        prepareOverviewPicker(listOf(good, damaged, remote), sources, true, openNative = { null }).use {
            assertEquals(3, it.rows.size)
            assertEquals(CardBrand.UNKNOWN, it.rows[1].cardBrand)
            assertTrue(validOverviewPickerIndices(intArrayOf(0, 1), it.rows, 0))
            listOf(intArrayOf(-1), intArrayOf(3), intArrayOf(1, 0), intArrayOf(0, 0), intArrayOf(2)).forEach { bad ->
                assertFalse(validOverviewPickerIndices(bad, it.rows, 0))
            }
        }
    }

    @Test fun overviewSearchIncludesEveryItemTypeWithoutLeakingLockedDatabasesOrSecrets() {
        val login = password(1, "alice@example.test")
        val bank = card(2, "Alice Bank", "4111111111111234")
        val work = password(3, "alice-work", vault = 2)
        val locked = password(4, "alice-locked").let {
            it.copy(passwordEntry = it.passwordEntry!!.copy(keepassDatabaseId = 3))
        }
        prepareOverviewPicker(listOf(login, bank, work, locked, bank), sources, cards = null,
            openNative = { null }).use { index ->
            assertEquals(listOf(login, bank, work), index.filter("alice", "all").map { it.item })
            assertEquals(listOf(login, bank), index.filter("alice", "local").map { it.item })
            assertEquals(listOf(work), index.filter("alice", "bitwarden:2").map { it.item })
            assertTrue(index.filter("alice", "keepass:3").isEmpty())
            assertTrue(index.filter("private", "all").isEmpty())
            assertTrue(index.filter("4111111111111234", "all").isEmpty())
        }
    }

    @Test fun cachedPreparationSurvivesRepeatedQueriesAndNativeUnavailability() {
        var opens = 0
        var decodes = 0
        val card = card(1, "Bank", "4111111111111234")
        prepareOverviewPicker(listOf(card), sources, true, decrypt = { decodes++; it }, nativeThreshold = 0,
            openNative = { opens++; null }).use {
            repeat(10) { _ ->
                assertEquals(1, it.filter("BANK", "all").size)
                assertEquals(0, it.filter("missing", "local").size)
            }
            assertEquals(1, opens)
            assertEquals(1, decodes)
            assertSame(it.rows[0], it.filter("1234", "local").single())
        }
    }

    @Test fun existingFrequentEntriesLeadBothPickersInOrderAcrossSearchAndDatabaseFilters() {
        val groups = listOf(
            false to listOf(password(1, "alice"), password(2, "bob"), password(3, "alice-work", vault = 2), password(4, "alice-other")),
            true to listOf(card(1, "Bank", "4111111111110001"), card(2, "Other", "4111111111110002"),
                card(3, "Work Bank", "4111111111110003", vault = 2), card(4, "Second Bank", "4111111111110004")),
        )
        for ((wallet, items) in groups) {
            val pinned = items[2].overviewIdentity()
            val recommended = items[1].overviewIdentity()
            val priorities = listOf(pinned, "missing", recommended, pinned)
            var opens = 0
            prepareOverviewPicker(items + items[2], sources, wallet, priorityIdentities = priorities,
                nativeThreshold = 0, openNative = { opens++; null }).use { picker ->
                assertEquals(listOf(items[2], items[1], items[0], items[3]), picker.rows.map { it.item })
                assertEquals(listOf(items[1], items[0], items[3]), picker.filter("", "local").map { it.item })
                assertEquals(listOf(items[2]), picker.filter("", "bitwarden:2").map { it.item })
                repeat(3) {
                    assertEquals(listOf(items[2], items[0], items[3]),
                        picker.filter(if (wallet) "BANK" else "ALICE", "all").map { it.item })
                }
                assertTrue(picker.filter("", "keepass:3").isEmpty())
                assertEquals(1, opens)
            }
        }
    }
}
