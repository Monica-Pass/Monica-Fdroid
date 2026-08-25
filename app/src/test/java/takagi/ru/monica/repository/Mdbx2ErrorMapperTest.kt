package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uniffi.mdbx_ffi.MdbxFfiException

class Mdbx2ErrorMapperTest {
    @Test
    fun authenticationFailureIsSanitized() {
        val error = Mdbx2ErrorMapper.openFailure(
            MdbxFfiException.Storage(
                "crypto error: authentication failed: data has been tampered with or key is wrong"
            )
        )

        assertEquals(Mdbx2FailureKind.INVALID_CREDENTIAL, error.kind)
        assertEquals("Unable to unlock MDBX2 database", error.message)
        assertFalse(error.message.orEmpty().contains("key is wrong"))
    }

    @Test
    fun rustIncorrectCredentialFailureIsSanitized() {
        val error = Mdbx2ErrorMapper.openFailure(
            MdbxFfiException.Storage("validation error: incorrect credential")
        )

        assertEquals(Mdbx2FailureKind.INVALID_CREDENTIAL, error.kind)
        assertEquals("Unable to unlock MDBX2 database", error.message)
        assertFalse(error.message.orEmpty().contains("incorrect credential"))
    }

    @Test
    fun malformedDatabaseFailureIsSanitized() {
        val error = Mdbx2ErrorMapper.openFailure(
            MdbxFfiException.Storage("database error: file is not a database: /private/path")
        )

        assertEquals(Mdbx2FailureKind.CORRUPT_VAULT, error.kind)
        assertEquals("MDBX2 database file is damaged or incompatible", error.message)
        assertFalse(error.message.orEmpty().contains("/private/path"))
    }

    @Test
    fun unknownStorageFailureDoesNotExposeDetail() {
        val error = Mdbx2ErrorMapper.openFailure(
            MdbxFfiException.Storage("filesystem error: secret internal detail")
        )

        assertEquals(Mdbx2FailureKind.STORAGE_FAILURE, error.kind)
        assertEquals("Unable to open MDBX2 database", error.message)
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun createFailureDoesNotExposePathOrNativeDetail() {
        val error = Mdbx2ErrorMapper.createFailure(
            MdbxFfiException.Storage("cannot create /private/vault.mdbx: secret native detail")
        )

        assertEquals(Mdbx2FailureKind.STORAGE_FAILURE, error.kind)
        assertEquals("Unable to create MDBX2 database", error.message)
        assertFalse(error.message.orEmpty().contains("/private"))
        assertFalse(error.message.orEmpty().contains("secret"))
    }
}
