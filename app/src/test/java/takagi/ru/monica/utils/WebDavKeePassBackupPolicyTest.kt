package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavKeePassBackupPolicyTest {

    @Test
    fun parsesKeepassEntriesRegardlessOfZipDirectoryPrefix() {
        assertEquals(
            KeePassBackupEntryPolicy.Entry(42L, KeePassBackupEntryPolicy.Kind.METADATA),
            KeePassBackupEntryPolicy.parse("backup\\keepass\\keepass_42_meta.json"),
        )
        assertEquals(
            KeePassBackupEntryPolicy.Entry(42L, KeePassBackupEntryPolicy.Kind.KEY_FILE),
            KeePassBackupEntryPolicy.parse("keepass/keepass_42.key"),
        )
        assertEquals(
            KeePassBackupEntryPolicy.Entry(42L, KeePassBackupEntryPolicy.Kind.DATABASE),
            KeePassBackupEntryPolicy.parse("prefix/keepass/keepass_42.kdbx"),
        )
    }

    @Test
    fun rejectsMalformedOrOverflowingEntryNames() {
        assertNull(KeePassBackupEntryPolicy.parse("keepass/keepass_42_extra.key"))
        assertNull(KeePassBackupEntryPolicy.parse("keepass/nested/keepass_42.key"))
        assertNull(KeePassBackupEntryPolicy.parse("keepass/keepass_999999999999999999999.key"))
    }

    @Test
    fun internalKeyCopyRequiresEncryptedBackup() {
        assertTrue(KeePassBackupEntryPolicy.requiresEncryptedBackup(true, false))
        assertFalse(KeePassBackupEntryPolicy.requiresEncryptedBackup(true, true))
        assertFalse(KeePassBackupEntryPolicy.requiresEncryptedBackup(false, false))
    }

    @Test
    fun legacyDatabaseWithoutKeyMetadataRemainsRestorable() {
        val result = KeePassBackupEntryPolicy.validateRestoreSet(
            databaseId = 7L,
            hasMetadata = true,
            hasDatabase = true,
            declaredKeyEntryName = null,
            hasKeyFile = false,
            backupEncrypted = false,
        )

        assertTrue(result.canRestore)
        assertFalse(result.useKeyFile)
        assertNull(result.warning)
    }

    @Test
    fun declaredKeyRequiresMatchingEncryptedEntry() {
        val mismatched = KeePassBackupEntryPolicy.validateRestoreSet(
            databaseId = 7L,
            hasMetadata = true,
            hasDatabase = true,
            declaredKeyEntryName = "keepass/keepass_8.key",
            hasKeyFile = true,
            backupEncrypted = true,
        )
        assertFalse(mismatched.canRestore)

        val missing = KeePassBackupEntryPolicy.validateRestoreSet(
            databaseId = 7L,
            hasMetadata = true,
            hasDatabase = true,
            declaredKeyEntryName = "keepass/keepass_7.key",
            hasKeyFile = false,
            backupEncrypted = true,
        )
        assertFalse(missing.canRestore)

        val plaintext = KeePassBackupEntryPolicy.validateRestoreSet(
            databaseId = 7L,
            hasMetadata = true,
            hasDatabase = true,
            declaredKeyEntryName = "keepass/keepass_7.key",
            hasKeyFile = true,
            backupEncrypted = false,
        )
        assertFalse(plaintext.canRestore)

        val ready = KeePassBackupEntryPolicy.validateRestoreSet(
            databaseId = 7L,
            hasMetadata = true,
            hasDatabase = true,
            declaredKeyEntryName = "keepass/keepass_7.key",
            hasKeyFile = true,
            backupEncrypted = true,
        )
        assertTrue(ready.canRestore)
        assertTrue(ready.useKeyFile)
    }

    @Test
    fun undeclaredKeyIsIgnoredInsteadOfBindingAnUnknownCredential() {
        val result = KeePassBackupEntryPolicy.validateRestoreSet(
            databaseId = 7L,
            hasMetadata = true,
            hasDatabase = true,
            declaredKeyEntryName = null,
            hasKeyFile = true,
            backupEncrypted = true,
        )

        assertTrue(result.canRestore)
        assertFalse(result.useKeyFile)
        assertTrue(result.warning.orEmpty().contains("忽略"))
    }
}
