package takagi.ru.monica.keepass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassProjectionIndexGateTest {
    @Test
    fun `unchanged indexed revision skips duplicate feature projection work`() {
        val gate = KeePassProjectionIndexGate()

        assertTrue(gate.needsRefresh(1L, "revision-a", KeePassProjectionKind.PASSWORD))
        gate.markIndexed(1L, "revision-a", setOf(KeePassProjectionKind.PASSWORD))
        assertFalse(gate.needsRefresh(1L, "revision-a", KeePassProjectionKind.PASSWORD))
        assertTrue(gate.needsRefresh(1L, "revision-a", KeePassProjectionKind.NOTE))
    }

    @Test
    fun `new revision and explicit invalidation require projection refresh`() {
        val gate = KeePassProjectionIndexGate()
        gate.markIndexed(
            databaseId = 2L,
            revisionToken = "revision-a",
            kinds = setOf(KeePassProjectionKind.PASSWORD, KeePassProjectionKind.TOTP)
        )

        assertTrue(gate.needsRefresh(2L, "revision-b", KeePassProjectionKind.PASSWORD))
        gate.invalidate(2L)
        assertTrue(gate.needsRefresh(2L, "revision-a", KeePassProjectionKind.TOTP))
    }
}
