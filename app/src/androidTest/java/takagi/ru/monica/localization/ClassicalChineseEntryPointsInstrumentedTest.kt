package takagi.ru.monica.localization

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.autofill_ng.PasswordSuggestionActivity
import takagi.ru.monica.data.Language
import takagi.ru.monica.passkey.PasskeySettingsActivity
import takagi.ru.monica.utils.SettingsManager

@RunWith(AndroidJUnit4::class)
class ClassicalChineseEntryPointsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language

    @Before
    fun prepare() = runBlocking {
        originalLocale = Locale.getDefault()
        originalLanguage = settings.settingsFlow.first().language
        settings.updateLanguage(Language.CLASSICAL_CHINESE)
        // These entry points must apply the saved choice without visiting MainActivity.
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restore() = runBlocking {
        settings.updateLanguage(originalLanguage)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun systemPasskeySettingsEntryUsesTheSavedLanguage() {
        ActivityScenario.launch(PasskeySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("lzh", activity.resources.configuration.locales[0].language)
            }
            compose.onNodeWithText("通行之钥之设").assertIsDisplayed()
        }
    }

    @Test
    fun autofillPasswordSuggestionEntryUsesTheSavedLanguage() {
        val intent = Intent(context, PasswordSuggestionActivity::class.java)
            .putExtra(PasswordSuggestionActivity.EXTRA_USERNAME, "locale-test")
            .putExtra(PasswordSuggestionActivity.EXTRA_GENERATED_PASSWORD, "Locale-Test-42!")
            .putExtra(PasswordSuggestionActivity.EXTRA_PACKAGE_NAME, "example.locale")
            .putExtra(PasswordSuggestionActivity.EXTRA_WEB_DOMAIN, "example.test")
        ActivityScenario.launch<PasswordSuggestionActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("lzh", activity.resources.configuration.locales[0].language)
            }
            compose.onNodeWithText("造坚钥").assertIsDisplayed()
        }
    }
}
