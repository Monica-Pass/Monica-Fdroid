package takagi.ru.monica.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class CommonAccountSecurityReuseTest {
    @Test fun sharedAndStandaloneManagersReadEachOthersEncryptedPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = CommonAccountPreferences(context, SecurityManager(context))
        val standalone = CommonAccountPreferences(context)
        val previous = shared.defaultEmail.first()
        try {
            standalone.setDefaultEmail("before-reuse@example.invalid")
            assertEquals("before-reuse@example.invalid", shared.defaultEmail.first())
            shared.setDefaultEmail("after-reuse@example.invalid")
            assertEquals("after-reuse@example.invalid", standalone.defaultEmail.first())
            shared.setDefaultEmail("")
            assertEquals("", standalone.defaultEmail.first())
        } finally {
            shared.setDefaultEmail(previous)
        }
    }
}
