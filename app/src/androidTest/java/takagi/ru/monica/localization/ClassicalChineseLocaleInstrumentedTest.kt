package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.autofill_ng.builder.AutofillDatasetBuilder
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

@RunWith(AndroidJUnit4::class)
class ClassicalChineseLocaleInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language

    @Before
    fun saveLanguage() = runBlocking {
        originalLocale = Locale.getDefault()
        originalLanguage = settings.settingsFlow.first().language
    }

    @After
    fun restoreLanguage() = runBlocking {
        settings.updateLanguage(originalLanguage)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun languageCodeResolvesItsResourcesAndPlatformFallback() {
        val classical = LocaleHelper.setLocale(context, Language.CLASSICAL_CHINESE)
        assertEquals("lzh", Locale.getDefault().toLanguageTag())
        assertEquals(Language.CLASSICAL_CHINESE, LocaleHelper.getCurrentLanguage(classical))
        assertEquals("lzh,zh-CN,en", classical.resources.configuration.locales.toLanguageTags())
        assertEquals("罢", classical.getString(R.string.cancel))
        assertEquals("总钥", classical.getString(R.string.master_password))
        assertEquals("通行之钥", classical.getString(R.string.passkey))
        assertEquals("1 项", classical.resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, 1, 1))
        assertEquals("8 项", classical.resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, 8, 8))

        val chinese = context.withLocale(Locale.SIMPLIFIED_CHINESE)
        val english = context.withLocale(Locale.ENGLISH)
        // Android 12 can resolve framework strings through its English default even
        // with zh-CN second in the list. Both are declared platform fallbacks.
        listOf(android.R.string.cancel, android.R.string.ok).forEach { id ->
            assertTrue(classical.getString(id) in setOf(chinese.getString(id), english.getString(id)))
        }
        assertSame(classical, LocaleHelper.setLocale(classical, Language.CLASSICAL_CHINESE))
    }

    @Test
    fun aContextWithOnlyLzhReceivesTheFallbackListAndCanSwitchBack() {
        val onlyLzh = context.withLocale(Locale.forLanguageTag("lzh"))
        val classical = LocaleHelper.setLocale(onlyLzh, Language.CLASSICAL_CHINESE)
        assertEquals("lzh,zh-CN,en", classical.resources.configuration.locales.toLanguageTags())
        val english = LocaleHelper.setLocale(classical, Language.ENGLISH)
        assertEquals("Cancel", english.getString(R.string.cancel))
        assertEquals(Language.ENGLISH, LocaleHelper.getCurrentLanguage(english))
        val chinese = LocaleHelper.setLocale(english, Language.CHINESE)
        assertEquals("取消", chinese.getString(R.string.cancel))
        assertEquals(Language.CHINESE, LocaleHelper.getCurrentLanguage(chinese))
    }

    @Test
    fun savedLanguageSurvivesAColdStartupCacheRead() = runBlocking {
        settings.updateLanguage(Language.CLASSICAL_CHINESE)
        withTimeout(10_000) { settings.settingsFlow.first { it.language == Language.CLASSICAL_CHINESE } }
        assertEquals(Language.CLASSICAL_CHINESE, SettingsManager(context).settingsFlow.first().language)
        val preferences = context.getSharedPreferences("monica_startup_language", Context.MODE_PRIVATE)
        assertEquals("CLASSICAL_CHINESE", preferences.getString("language", null))

        // Emulate a fresh process cache without changing production startup behavior.
        StartupLanguageCache::class.java.getDeclaredField("processLanguage").apply {
            isAccessible = true
            set(null, null)
        }
        assertEquals(Language.CLASSICAL_CHINESE, StartupLanguageCache.read(context))
        assertEquals(Language.CLASSICAL_CHINESE, StartupLanguageCache.languageFlow(context).value)
        val coldContext = LocaleHelper.setLocale(context, StartupLanguageCache.read(context))
        assertEquals("密钥", coldContext.getString(R.string.password))
    }

    @Test
    fun applicationStringResolverInvalidatesItsCacheAfterSwitchingLanguage() {
        val strings = AppLocaleStringResolver(context)
        val biometric = BiometricAuthHelper(context)
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
        val englishBiometricStatus = biometric.getBiometricStatusMessage()
        LocaleHelper.setLocale(context, Language.CLASSICAL_CHINESE)
        assertEquals("密钥", strings.get(R.string.password))
        assertEquals("邮箱验码已寄，请察收件箱及垃圾邮件。", strings.get(R.string.legacy_ui_email_code_sent))
        val platformFallbacks = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)
            .map { context.withLocale(it).getString(android.R.string.cancel) }
        assertTrue(strings.get(android.R.string.cancel) in platformFallbacks)
        assertFalse("A retained biometric helper must refresh its status text",
            englishBiometricStatus == biometric.getBiometricStatusMessage())
        LocaleHelper.setLocale(context, Language.CHINESE)
        assertEquals("密码", strings.get(R.string.password))
    }

    @Test
    fun autofillPresentationUsesTheAppLanguageWithAnApplicationContext() {
        LocaleHelper.setLocale(context, Language.CLASSICAL_CHINESE)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val appContext = context.applicationContext
            val parent = FrameLayout(context)
            val manual = AutofillDatasetBuilder.RemoteViewsFactory.createManualSelection(appContext)
                .apply(context, parent)
            assertEquals("Monica 自填", manual.findViewById<TextView>(R.id.text_title).text.toString())
            val suggestion = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordSuggestion(appContext)
                .apply(context, parent)
            assertEquals("造坚钥", suggestion.findViewById<TextView>(R.id.text_title).text.toString())
            assertEquals("Monica 将为君造一坚钥。",
                suggestion.findViewById<TextView>(R.id.text_username).text.toString())
        }
    }

    @Test
    fun androidCanReadAndFormatEveryPackagedString() {
        val classical = LocaleHelper.setLocale(context, Language.CLASSICAL_CHINESE)
        val fields = R.string::class.java.fields
        assertTrue("The app resources must be present", fields.size > 5_000)
        fields.forEach { field ->
            val id = field.getInt(null)
            val template = classical.getString(id)
            val arguments = mutableMapOf<Int, Any>()
            var nextArgument = 0
            var previousArgument = 0
            FORMAT.findAll(template).forEach { match ->
                val conversion = match.groupValues[3]
                if (conversion == "%" || conversion == "n") return@forEach
                val explicit = match.groupValues[1].toIntOrNull()?.minus(1)
                val index = explicit ?: if ('<' in match.groupValues[2]) previousArgument else nextArgument++
                previousArgument = index
                arguments[index] = when (conversion.lowercase(Locale.ROOT)) {
                    "d", "o", "x" -> 7
                    "e", "f", "g", "a" -> 1.25
                    "c" -> 'A'
                    "b" -> true
                    else -> if (conversion.startsWith('t', true)) 0L else "sample"
                }
            }
            if (arguments.isNotEmpty()) {
                val values = Array(arguments.keys.max() + 1) { arguments[it] ?: "sample" }
                try {
                    classical.getString(id, *values)
                } catch (error: RuntimeException) {
                    throw AssertionError("Cannot format ${field.name}: $template", error)
                }
            }
            assertFalse("${field.name} contains an invalid replacement character", template.contains('\uFFFD'))
        }
    }

    private fun Context.withLocale(locale: Locale) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(locale) }
    )

    private companion object {
        val FORMAT = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])""")
    }
}
