package takagi.ru.monica.localization

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Date patterns are executable formatter input, not ordinary translated text. */
class TimelineDatePatternTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val sample = GregorianCalendar(utc).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 17, 12, 0)
    }.time

    @Test fun frenchHistoryDatesKeepTheDayAndMonth() {
        val pattern = patterns().single { it.first == "values-fr" }.second
        assertEquals("17/09", formatter(pattern).format(sample))
    }

    @Test fun everyShippedTimelinePatternFormatsAndParsesTheOriginalMonthAndDay() {
        val patterns = patterns()
        assertTrue("No date resources found", patterns.isNotEmpty())
        patterns.forEach { (directory, pattern) ->
            try {
                val format = formatter(pattern)
                val rendered = format.format(sample)
                val parsed = GregorianCalendar(utc).apply { time = requireNotNull(format.parse(rendered)) }
                assertEquals("$directory: $rendered (month)", Calendar.SEPTEMBER, parsed.get(Calendar.MONTH))
                assertEquals("$directory: $rendered (day)", 17, parsed.get(Calendar.DAY_OF_MONTH))
            } catch (error: Exception) {
                throw AssertionError("Invalid timeline date pattern in $directory: $pattern", error)
            }
        }
    }

    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply {
        timeZone = utc
        isLenient = false
    }

    private fun patterns(): List<Pair<String, String>> {
        val resources = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "app/src/main/res") }.first { it.isDirectory }
        return resources.listFiles().orEmpty().filter { it.name.startsWith("values") }.flatMap { directory ->
            directory.listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file).getElementsByTagName("string")
                (0 until nodes.length).mapNotNull { index ->
                    val node = nodes.item(index) as Element
                    if (node.getAttribute("name") != "timeline_date_month_day") null
                    // AAPT removes surrounding XML string quotes, e.g. in the Italian resource.
                    else directory.name to node.textContent.trim().removeSurrounding("\"")
                }
            }
        }
    }
}
