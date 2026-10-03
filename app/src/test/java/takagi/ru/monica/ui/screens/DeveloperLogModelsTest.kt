package takagi.ru.monica.ui.screens

import org.junit.Assert.*
import org.junit.Test

class DeveloperLogModelsTest {
    @Test fun lazyTextBlocksPreserveLongStacksAndUnicodeWithoutTruncation() {
        val text = "x".repeat(3999) + "\uD83D\uDD10" + "\n    at Fixture.save(Fixture.kt:42)".repeat(2000)
        val event = DeveloperLogEvent("long", DeveloperLogSource.MDBX, DeveloperLogLevel.ERROR, "", "", "", text)
        val blocks = developerLogTextBlocks(listOf(event))
        assertEquals(text, blocks.joinToString("") { it.text })
        assertEquals(blocks.size, blocks.map { it.id }.distinct().size)
        assertTrue(blocks.all { it.text.length <= 4000 && !it.text.last().isHighSurrogate() })
        assertTrue(blocks.all { it.event === event })
        assertTrue(developerLogTextBlocks(emptyList()).isEmpty())
    }

    @Test fun logcatStackRemainsOneSearchableEvent() {
        val raw = """10-02 11:20:00.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main
10-02 11:20:00.001  123  123 E AndroidRuntime: Process: takagi.ru.monica, PID: 123
10-02 11:20:00.002  123  123 E AndroidRuntime: java.lang.IllegalStateException: fixture
10-02 11:20:00.003  123  123 E AndroidRuntime:     at Fixture.save(Fixture.kt:42)
10-02 11:20:00.004  123  123 E AndroidRuntime: Caused by: java.io.IOException: retry
10-02 11:20:01.000  123  123 I Fixture: recovered"""
        val events = parseDeveloperLogEvents(raw, DeveloperLogSource.SYSTEM)
        assertEquals(2, events.size)
        val found = filterDeveloperLogEvents(events, "Fixture.kt:42", DeveloperLogFilter.ERROR, null).single()
        assertTrue(found.text.contains("FATAL EXCEPTION"))
        assertTrue(found.text.contains("Caused by:"))
        assertEquals("10-02 11:20:00.000", found.timestamp)
        assertEquals(raw.substringBeforeLast("\n"), found.text)
    }
    @Test fun persistedStackRetainsTagAndLevel() {
        val raw = "11:20:00.000 [ERROR] [Sync] upload failed\n    at Fixture.upload(Fixture.kt:42)\nCaused by: java.io.IOException: network\n11:20:01.000 [INFO] [Sync] retrying"
        val events = parseDeveloperLogEvents(raw, DeveloperLogSource.AUTOFILL)
        assertEquals(2, events.size)
        assertEquals("Sync", events[0].tag)
        assertEquals(DeveloperLogLevel.ERROR, events[0].level)
        assertTrue(events[0].text.contains("Caused by:"))
    }
    @Test fun errorWordsInsideInfoOrRawPayloadDoNotBecomeErrors() {
        val events = parseDeveloperLogEvents("11:20:00.000 [INFO] [Sync] error count=0, [ERROR] is a label\n{\"error\":false}", DeveloperLogSource.MDBX)
        assertTrue(filterDeveloperLogEvents(events, "", DeveloperLogFilter.ERROR, null).isEmpty())
        assertEquals(DeveloperLogLevel.INFO, events.first().level)
    }
    @Test fun sourceAndSeverityFiltersDoNotHideOtherSourcesPermanently() {
        val events = DeveloperLogSource.entries.flatMap { source ->
            parseDeveloperLogEvents("11:20:00 [WARN] [Fixture] retry", source)
        }
        assertEquals(8, events.map { it.id }.distinct().size)
        assertEquals(1, filterDeveloperLogEvents(events, " RETRY ", DeveloperLogFilter.WARNING, DeveloperLogSource.MDBX).size)
        assertEquals(8, filterDeveloperLogEvents(events, "", DeveloperLogFilter.ALL, null).size)
    }
    @Test fun noMatchesNeverFallsBackToAnUnfilteredReport() {
        val events = parseDeveloperLogEvents("[INFO] [Fixture] ready", DeveloperLogSource.BITWARDEN)
        assertTrue(filterDeveloperLogEvents(events, "absent", DeveloperLogFilter.ALL, null).isEmpty())
        assertTrue(filterDeveloperLogEvents(events, "ready", DeveloperLogFilter.ERROR, null).isEmpty())
    }
    @Test fun largeMultilineEventIsNotTruncatedInStorageOrCopy() {
        val raw = "[ERROR] [Fixture] failure\n" + "    at Fixture.call(Fixture.kt:42)\n".repeat(4000)
        val event = parseDeveloperLogEvents(raw, DeveloperLogSource.SECURITY).single()
        assertEquals(raw.trimEnd(), event.text)
        assertTrue(event.text.length > 12000)
    }
    @Test fun blankInputAndUnstructuredReportsRemainSafe() {
        assertTrue(parseDeveloperLogEvents("\n \n", DeveloperLogSource.PASSKEY).isEmpty())
        val events = parseDeveloperLogEvents("=== Environment ===\napi=32\n  detail=available", DeveloperLogSource.PASSKEY)
        assertEquals(2, events.size)
        assertTrue(events.all { it.level == DeveloperLogLevel.OTHER })
        assertTrue(events.last().text.contains("detail=available"))
    }
    @Test fun separateProcessesAndNewFatalEventsAreNotMerged() {
        val events = parseDeveloperLogEvents("""10-02 11:20:00.000 1 1 E AndroidRuntime: FATAL EXCEPTION: main
10-02 11:20:00.001 2 2 E AndroidRuntime: java.lang.IllegalStateException: other process
10-02 11:20:00.002 2 2 E AndroidRuntime: FATAL EXCEPTION: next""", DeveloperLogSource.SYSTEM)
        assertEquals(3, events.size)
    }
    @Test fun fullDatesAndWarningSpellingAreRecognized() {
        val event = parseDeveloperLogEvents("2026-10-02 11:20:00 [WARNING] [Fixture] retry", DeveloperLogSource.STEAM).single()
        assertEquals("2026-10-02 11:20:00", event.timestamp)
        assertEquals(DeveloperLogLevel.WARN, event.level)
    }
    @Test fun overlappingAutofillSourcesPreserveRepeatedEventsAndUniqueIds() {
        val line = "11:20:00 [ERROR] [Fixture] retry"
        val persisted = parseDeveloperLogEvents(line + "\n" + line, DeveloperLogSource.AUTOFILL)
        val memory = parseDeveloperLogEvents(line + "\n" + line + "\n" + line, DeveloperLogSource.AUTOFILL)
        val result = mergeDeveloperAutofillEvents(persisted, memory)
        assertEquals(3, result.size)
        assertEquals(3, result.map { it.id }.distinct().size)
        assertEquals(2, mergeDeveloperAutofillEvents(persisted, emptyList()).size)
    }
}
