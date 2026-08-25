package takagi.ru.monica.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import takagi.ru.monica.utils.KEEPASS_KEY_FILE_PERMISSION_FLAGS

class KeePassKeyFilePermissionPolicyTest {

    @Test
    fun keyFilePersistsReadPermissionWithoutRequiringWriteAccess() {
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            KEEPASS_KEY_FILE_PERMISSION_FLAGS
        )
        assertFalse(
            KEEPASS_KEY_FILE_PERMISSION_FLAGS and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        )
    }
}
