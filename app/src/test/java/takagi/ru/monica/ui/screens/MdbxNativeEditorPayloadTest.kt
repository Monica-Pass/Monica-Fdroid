package takagi.ru.monica.ui.screens

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MdbxNativeEditorPayloadTest {
    @Test fun editsKnownLoginFieldsWithoutLosingOpaqueContent() {
        val original = """{"username":"old","password_plain":"old secret","website":"https://old.test","notes":"","future":{"flags":[1,true,null]},"schema_extension":7}"""
        val result = Json.parseToJsonElement(mdbxNativeLoginPayload(original, "alice", "new secret", "https://new.test", "备注")).jsonObject
        val before = Json.parseToJsonElement(original).jsonObject
        assertEquals(before["future"], result["future"])
        assertEquals(before["schema_extension"], result["schema_extension"])
        assertEquals("alice", result["username"]?.jsonPrimitive?.content)
        assertEquals("new secret", result["password_plain"]?.jsonPrimitive?.content)
        assertEquals("plaintext-v1", result["monica_password_encoding"]?.jsonPrimitive?.content)
        assertEquals("备注", result["notes"]?.jsonPrimitive?.content)
    }

    @Test fun newLoginRetainsUnicodeAndLiteralJsonCharacters() {
        val password = "秘密\\quoted\"\nline"
        val result = Json.parseToJsonElement(mdbxNativeLoginPayload(null, "用戶", password, "", "")).jsonObject
        assertEquals(password, result["password_plain"]?.jsonPrimitive?.content)
        assertEquals("用戶", result["username"]?.jsonPrimitive?.content)
        assertEquals(5, result.size)
    }
}
