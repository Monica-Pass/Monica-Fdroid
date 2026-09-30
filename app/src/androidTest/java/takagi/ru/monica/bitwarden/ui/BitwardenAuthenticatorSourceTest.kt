package takagi.ru.monica.bitwarden.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.TotpViewModel

class BitwardenAuthenticatorSourceTest {
    @Test fun pickerIncludesStandaloneAndPasswordOtpDespiteAuthenticatorSearch() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,PasswordDatabase::class.java).build()
        val model=TotpViewModel(SecureItemRepository(db.secureItemDao()),PasswordRepository(db.passwordEntryDao()),strings=AppLocaleStringResolver(context))
        try {
            db.secureItemDao().insertItem(SecureItem(itemType=ItemType.TOTP,title="Independent fixture",
                itemData=Json.encodeToString(TotpData(secret="JBSWY3DPEHPK3PXP",issuer="Independent"))))
            val passwordId=db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Password fixture",website="",username="fixture",password="",
                authenticatorKey="otpauth://totp/Password:fixture?secret=KRUGS4ZANFZSAYJA&issuer=Password"))
            model.updateSearchQuery("no-match-for-picker-test")
            val entries=withTimeout(10000){model.allParsedTotpItems.first{it.size==2}}
            assertEquals(setOf("Independent fixture","Password fixture"),entries.map{it.item.title}.toSet())
            assertTrue(entries.any{it.item.id == -passwordId})
            val filtered=withTimeout(10000){model.parsedTotpState.first{it.isReady}}
            assertTrue(filtered.items.isEmpty())
        } finally {
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }
}
