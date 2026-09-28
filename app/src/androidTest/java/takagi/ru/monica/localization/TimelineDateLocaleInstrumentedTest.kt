package takagi.ru.monica.localization

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.ui.screens.TimelineDisplayItem
import takagi.ru.monica.ui.screens.TimelineGroup
import takagi.ru.monica.ui.screens.groupAndAggregateEvents
import takagi.ru.monica.utils.LocaleHelper

@RunWith(AndroidJUnit4::class)
class TimelineDateLocaleInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalLocale: Locale

    @Before fun saveLocale() { originalLocale = Locale.getDefault() }
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun everyCompiledLanguagePatternKeepsTheActualDayAndMonth() {
        val utc = TimeZone.getTimeZone("UTC")
        val date = GregorianCalendar(utc).apply { clear(); set(2026, Calendar.SEPTEMBER, 17, 12, 0) }.time
        Language.entries.forEach { language ->
            val localized = LocaleHelper.setLocale(context, language)
            val pattern = localized.getString(R.string.timeline_date_month_day)
            val formatter = SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = utc; isLenient = false }
            val rendered = formatter.format(date)
            val parsed = GregorianCalendar(utc).apply { time = requireNotNull(formatter.parse(rendered)) }
            assertEquals(language.name, Calendar.SEPTEMBER, parsed.get(Calendar.MONTH))
            assertEquals(language.name, 17, parsed.get(Calendar.DAY_OF_MONTH))
            if (language == Language.FRENCH) assertEquals("17/09", rendered)
        }
    }

    @Test fun frenchTimelineGroupsOldHistoryAlongsideTodayAndYesterday() {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        val old = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -30) }
        val events = listOf(event("today", today.timeInMillis), event("yesterday", yesterday.timeInMillis), event("old", old.timeInMillis))
        val localized = LocaleHelper.setLocale(context, Language.FRENCH)
        val expectedOldDate = SimpleDateFormat("dd/MM", Locale.FRENCH).format(old.time)
        var groups: List<TimelineGroup> = emptyList()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MaterialTheme {
                    groups = groupAndAggregateEvents(events)
                    Column { groups.forEach { Text(it.dateLabel) } }
                }
            }
        }
        compose.onNodeWithText(localized.getString(R.string.timeline_date_today)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.timeline_date_yesterday)).assertIsDisplayed()
        compose.onNodeWithText(expectedOldDate).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("today", "yesterday", "old"), groups.flatMap { group ->
                group.items.map { (it as TimelineDisplayItem.Single).event.id }
            })
        }
    }

    @Test fun oldTimelineDatesFollowAppLanguageEvenWhenSystemLocaleDiffers() {
        val old = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }.time
        val events = listOf(event("old", old.time))
        var language by mutableStateOf(Language.FRENCH)
        // The app may use French while the device default remains English.
        Locale.setDefault(Locale.US)
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(when (language) {
                    Language.FRENCH -> Locale.FRENCH
                    Language.CHINESE -> Locale.SIMPLIFIED_CHINESE
                    else -> Locale.ENGLISH
                })
            }
            val localized = context.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                MaterialTheme { Column { groupAndAggregateEvents(events).forEach { Text(it.dateLabel) } } }
            }
        }
        listOf(Language.FRENCH to "dd/MM", Language.ENGLISH to "MM-dd", Language.CHINESE to "MM月dd日").forEach { (next, pattern) ->
            compose.runOnIdle { language = next }
            compose.onNodeWithText(SimpleDateFormat(pattern, Locale.US).format(Date(old.time))).assertIsDisplayed()
        }
    }

    private fun event(id: String, timestamp: Long) = TimelineEvent.StandardLog(
        id = id, timestamp = timestamp, deviceId = "issue-144-test", summary = "Synthetic $id",
        itemType = "PASSWORD", operationType = "UPDATE",
    )
}
