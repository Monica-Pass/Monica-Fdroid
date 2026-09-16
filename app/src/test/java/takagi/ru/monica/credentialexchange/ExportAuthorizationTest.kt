package takagi.ru.monica.credentialexchange

import org.junit.Assert.*
import org.junit.Test

class ExportAuthorizationTest {
    @Test fun exportRequiresFreshAuthenticationAndCannotReuseIt() {
        val gate = ExportAuthorization()
        assertFalse(gate.consume("LOCAL:0", 100))
        gate.grant("LOCAL:0", 200)
        assertTrue(gate.consume("LOCAL:0", 201))
        assertFalse(gate.consume("LOCAL:0", 202))
    }
    @Test fun changingDestinationInvalidatesTheAuthorization() {
        val gate = ExportAuthorization()
        gate.grant("KEEPASS:1", 100)
        assertFalse(gate.consume("KEEPASS:2", 101))
        assertFalse(gate.consume("KEEPASS:1", 102))
    }
    @Test fun backgroundAndExpiredSessionsRequireReauthentication() {
        val gate = ExportAuthorization()
        gate.grant("LOCAL:0", 100)
        gate.clear()
        assertFalse(gate.consume("LOCAL:0", 101))
        gate.grant("LOCAL:0", 100)
        assertFalse(gate.consume("LOCAL:0", 120_101))
        gate.grant("LOCAL:0", 100)
        assertFalse(gate.consume("LOCAL:0", 99))
    }
}
