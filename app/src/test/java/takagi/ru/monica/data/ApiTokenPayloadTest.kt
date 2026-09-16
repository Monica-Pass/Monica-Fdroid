package takagi.ru.monica.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ApiTokenPayloadTest {
    private val fixture = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://gitlab.example.test/api/v4/","note":"Nested category","token":"synthetic-cli-token-123456","extension":{"scopes":["api"],"expires":null}}"""

    @Test fun cliPayloadAndUnknownExtensionsSurviveEditing() {
        assertTrue(ApiTokenPayload.isValid(fixture))
        val edited = ApiTokenPayload.update(fixture, "note", "New context")
        val original = Json.parseToJsonElement(fixture).jsonObject
        val fields = ApiTokenPayload.decode(edited)!!
        assertEquals(original["extension"], fields["extension"])
        assertEquals(original["token"], fields["token"])
        assertEquals("New context", ApiTokenPayload.text(fields, "note"))
    }

    @Test fun malformedAndForeignSchemasCannotBeEditedAsCliCredentials() {
        listOf("{}", "[]", "invalid", fixture.replace("credential.v1", "credential.v2"),
            fixture.replace("\"synthetic-cli-token-123456\"", "123"),
            fixture.replace("\"Nested category\"", "null")
        ).forEach { assertNull(ApiTokenPayload.decode(it)) }
    }

    @Test fun endpointAndProviderValidationMatchesCliContract() {
        listOf("http://gitlab.example.test/api/v4/", "https://user:pass@gitlab.example.test/",
            "https://gitlab.example.test/?secret=x", "https://gitlab.example.test/#fragment", "https:///api/v4/")
            .forEach { assertFalse(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "api_base", it))) }
        assertFalse(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "provider", "unknown")))
        val github = ApiTokenPayload.update(ApiTokenPayload.update(fixture, "provider", "github"), "api_base", "https://api.github.com/")
        assertTrue(ApiTokenPayload.isValid(github))
        assertTrue(ApiTokenPayload.isValid(ApiTokenPayload.update(github, "api_base", "https://github.example.test/api/v3/")))
        assertFalse(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "api_base", "https://gitlab.example.test/")))
    }

    @Test fun tokenAndUtf8LimitsMatchCliContract() {
        listOf("short", "synthetic token spaces", "synthetic\ncontroltoken", "密钥".repeat(16), "x".repeat(4097))
            .forEach { assertFalse(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "token", it))) }
        assertTrue(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "token", "x".repeat(16))))
        assertTrue(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "token", "x".repeat(4096))))
        assertTrue(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "note", "中".repeat(341))))
        assertFalse(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "note", "中".repeat(342))))
        assertNull(ApiTokenPayload.decode(fixture + " ".repeat(ApiTokenPayload.MAX_BYTES)))
    }

    @Test fun diagnosticsDoNotPrintTokenPayload() {
        val token = NativeApiToken(NativeApiTokenSummary(1, "entry", "category", "category", "title"), fixture)
        assertFalse(token.toString().contains("synthetic-cli-token"))
    }

    @Test fun correctingOversizedInputPreservesTheOtherFields() {
        val oversized = ApiTokenPayload.update(fixture, "note", "x".repeat(ApiTokenPayload.MAX_BYTES))
        assertNull(ApiTokenPayload.decode(oversized))
        val corrected = ApiTokenPayload.update(oversized, "note", "Corrected")
        val original = ApiTokenPayload.decode(fixture)!!
        val fields = ApiTokenPayload.decode(corrected)!!
        assertEquals(original["extension"], fields["extension"])
        assertEquals(original["token"], fields["token"])
        assertTrue(ApiTokenPayload.isValid(corrected))
        val foreign = fixture.replace("credential.v1", "credential.v2")
        assertEquals(foreign, ApiTokenPayload.update(foreign, "note", "Must not overwrite"))
    }

    @Test fun cliNamesAndNotesRejectIncompatibleMetadata() {
        assertTrue(ApiTokenPayload.isValidName("gitlab_work-1"))
        listOf("", "with spaces", "中文", "a".repeat(65)).forEach { assertFalse(ApiTokenPayload.isValidName(it)) }
        listOf("line\nbreak", "hidden\u202ebidi", "synthetic-cli-token-123456").forEach {
            assertFalse(ApiTokenPayload.isValid(ApiTokenPayload.update(fixture, "note", it)))
        }
    }

    @Test fun customProvidersAndNamesUseTheGeneralSchemaWithoutChangingTheSecretOrExtensions() {
        var payload = ApiTokenPayload.update(fixture, "provider", "My custom service")
        payload = ApiTokenPayload.update(payload, "api_base", "https://api.example.test/custom/v2/resources")
        assertTrue(ApiTokenPayload.isValidForStorage(payload))
        assertTrue(ApiTokenPayload.isValidStorageName("工作 API 令牌"))
        val stored = ApiTokenPayload.forStorage(payload, "工作 API 令牌")
        assertEquals(ApiTokenPayload.APP_SCHEMA, ApiTokenPayload.text(ApiTokenPayload.decode(stored), "schema"))
        assertEquals(ApiTokenPayload.decode(fixture)?.get("token"), ApiTokenPayload.decode(stored)?.get("token"))
        assertEquals(ApiTokenPayload.decode(fixture)?.get("extension"), ApiTokenPayload.decode(stored)?.get("extension"))
        assertTrue(ApiTokenPayload.isValidForStorage(ApiTokenPayload.update(payload, "api_base", "")))
        assertEquals(fixture, ApiTokenPayload.forStorage(fixture, "gitlab-work"))
        assertFalse(ApiTokenPayload.isValidForStorage(ApiTokenPayload.update(payload, "api_base", "https://user:secret@example.test/")))
    }

    @Test fun displayIdentityIsStableAndIsolatedFromRoomPasswords() {
        val first = NativeApiTokenSummary(1, "uuid", "folder", "Work", "same title", updatedAt = 1234L)
        assertTrue(first.displayId < 0)
        assertEquals(first.displayId, first.copy(title = "renamed", isFavorite = true).displayId)
        assertNotEquals(first.displayId, first.copy(databaseId = 2).displayId)
        val display = first.asPasswordCard("API token")
        assertEquals(display, first.asPasswordCard("API token"))
        assertEquals("", display.password)
        assertEquals("API_TOKEN", display.loginType)
    }
}
