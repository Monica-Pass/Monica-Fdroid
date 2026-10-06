package takagi.ru.monica.passkey

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.PasskeyEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PasskeyGetRequestPolicyTest {
    private val id = "sPRk4p1Z8GKaHlkk_iwpSRZ5B9HeqPZNWg5optdhDfI"
    private fun request(allow: String = "") = """{"rpId":"example.com","challenge":"Y2hhbGxlbmdl"$allow}"""
    private fun row() = PasskeyEntry(credentialId = "b64.$id", rpId = "example.com",
        rpName = "Example", userId = "dXNlcg", userName = "account", userDisplayName = "Account",
        publicKey = "unchanged-public-key", privateKeyAlias = "unchanged-private-key", notes = "private note")

    @Test fun allowedOldNonDiscoverableCredentialStillWorks() {
        val policy = PasskeyGetRequestPolicy.parse(request(""", "allowCredentials":[{"type":"public-key","id":"$id"}]"""))
        assertTrue(policy.allows(row().copy(isDiscoverable = false)))
        assertFalse(policy.allows(row().copy(credentialId = "AA")))
        assertFalse(policy.allows(row().copy(rpId = "evil.example.com")))
    }

    @Test fun absentAndEmptyAllowListsKeepExistingRpDiscoveryCompatibility() {
        for (allow in listOf("", """, "allowCredentials":[]""")) {
            val policy = PasskeyGetRequestPolicy.parse(request(allow))
            assertTrue(policy.allows(row()))
            assertFalse(policy.allows(row().copy(rpId = "other.test")))
        }
    }

    @Test fun unknownAllowListNeverFallsBackToAnotherCredential() {
        val policy = PasskeyGetRequestPolicy.parse(request(""", "allowCredentials":[{"type":"public-key","id":"AA"}]"""))
        repeat(3) { assertFalse(policy.allows(row())) }
    }

    @Test fun malformedNonemptyAllowListsAreRejectedRatherThanBecomingDiscovery() {
        for (value in listOf("null", "{}", "false", "[null]", "[{}]", """[{"type":"password","id":"AA"}]""",
            """[{"type":"public-key","id":"b64..AA"}]""", """[{"type":"public-key","id":23}]""",
            """[{"type":"public-key","id":"AA"},{}]""")) {
            assertThrows(IllegalArgumentException::class.java) { PasskeyGetRequestPolicy.parse(request(""", "allowCredentials":$value""")) }
        }
    }

    @Test fun finalPlatformRequestMustMatchCeremonyAndIndependentlyAllowSelection() {
        val original = PasskeyGetRequestPolicy.parse(request())
        assertFalse(original.isSameCeremony(PasskeyGetRequestPolicy.parse(request().replace("Y2hhbGxlbmdl", "bmV3"))))
        assertFalse(original.isSameCeremony(PasskeyGetRequestPolicy.parse(request().replace("example.com", "other.test"))))
        val restricted = PasskeyGetRequestPolicy.parse(request(""", "allowCredentials":[{"type":"public-key","id":"AA"}]"""))
        assertTrue(original.isSameCeremony(restricted))
        assertFalse(restricted.allows(row()))
    }

    @Test fun authenticationTitleKeepsNotesPrivateAndManagementTitleUnchanged() {
        val passkey = row()
        assertEquals("Account", passkey.authenticationTitle())
        assertEquals("private note", passkey.displayTitle())
        assertEquals("account", passkey.copy(userDisplayName = "").authenticationTitle())
        assertEquals("Example", passkey.copy(userDisplayName = "", userName = "").authenticationTitle())
    }
}
