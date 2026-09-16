package takagi.ru.monica.credentialexchange

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.util.CsvPasswordData
import takagi.ru.monica.util.CsvRecords

class CsvPreservationTest {
    @Test fun csvAndIntermediateFormatPreserveSpacesDelimitersUnicodeAndBothLineEndings() {
        val expected = listOf("  Alice;admin:1  ", " ;password:x,\"Y\"\r\n行二\n🔑  ", "https://example.com/a;b?q=x:y", "")
        val csv = expected.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + "\r\nnext,row\r\n"
        val input = StringReader(csv).buffered()
        val actual = CsvRecords.fields(CsvRecords.read(input)!!)
        assertEquals(expected, actual)
        assertEquals(listOf("next", "row"), CsvRecords.fields(CsvRecords.read(input)!!))
        assertNull(CsvRecords.read(input))
        val fields = CsvPasswordData.decode(CsvPasswordData.encode(actual[0], actual[1], actual[2]))
        assertEquals(expected[0], fields["username"])
        assertEquals(expected[1], fields["password"])
        assertEquals(expected[2], fields["website"])
    }

    @Test fun whitespaceOnlyPasswordAndTrailingEmptyFieldsAreKept() {
        assertEquals(listOf("a", "  ", "", ""), CsvRecords.fields("a,  ,,"))
        assertEquals("  ", CsvPasswordData.decode(CsvPasswordData.encode("", "  ", ""))["password"])
    }

    @Test fun brokenQuoteNeverBecomesAChangedPassword() {
        assertTrue(runCatching { CsvRecords.read(StringReader("a,\"broken\r\npassword").buffered()) }.isFailure)
        assertTrue(runCatching { CsvRecords.fields("a,\"password\"junk") }.isFailure)
        assertTrue(runCatching { CsvRecords.fields("a,pass\"word") }.isFailure)
    }

    @Test fun legacyMonicaFieldsKeepColonInUrlAndPassword() {
        val parsed = CsvPasswordData.decode("username: Alice ;password: secret:with:colon ;website:https://example.com")
        assertEquals(" Alice ", parsed["username"])
        assertEquals(" secret:with:colon ", parsed["password"])
        assertEquals("https://example.com", parsed["website"])
    }

    @Test fun malformedStructuredCredentialIsNotSilentlyTreatedAsLegacy() {
        assertTrue(runCatching { CsvPasswordData.decode("{\"password\":[]}") }.isFailure)
        assertTrue(runCatching { CsvPasswordData.decode("{\"password\":") }.isFailure)
    }
}
