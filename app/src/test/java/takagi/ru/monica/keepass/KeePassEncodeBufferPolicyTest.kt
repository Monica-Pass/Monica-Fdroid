package takagi.ru.monica.keepass

import org.junit.Assert.assertEquals
import org.junit.Test

class KeePassEncodeBufferPolicyTest {

    @Test
    fun `uses a small bounded default when prior database size is unavailable`() {
        assertEquals(32 * 1024, KeePassEncodeBufferPolicy.initialCapacity(null))
        assertEquals(32 * 1024, KeePassEncodeBufferPolicy.initialCapacity(0))
    }

    @Test
    fun `reuses a realistic prior size without exceeding the memory cap`() {
        assertEquals(2 * 1024 * 1024, KeePassEncodeBufferPolicy.initialCapacity(2L * 1024 * 1024))
        assertEquals(16 * 1024 * 1024, KeePassEncodeBufferPolicy.initialCapacity(80L * 1024 * 1024))
        assertEquals(16 * 1024 * 1024, KeePassEncodeBufferPolicy.initialCapacity(Long.MAX_VALUE))
    }
}
