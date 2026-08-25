package takagi.ru.monica.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PasskeyBackupPortabilityPolicyTest {

    @Test
    fun `encrypted backup exports resolved and normalized private key material`() {
        val decision = PasskeyBackupPortabilityPolicy.prepareExport(
            encryptedBackup = true,
            storedPrivateKey = "monica-passkey-key-ref-v1:key-id",
            resolvePrivateKey = { stored ->
                assertEquals("monica-passkey-key-ref-v1:key-id", stored)
                "raw-private-key"
            },
            normalizePrivateKey = { value -> "pkcs8:$value" },
        )

        assertEquals(
            PasskeyBackupPortabilityPolicy.ExportDecision.Ready("pkcs8:raw-private-key"),
            decision,
        )
    }

    @Test
    fun `unencrypted backup rejects passkey private key export`() {
        var resolverCalled = false

        val decision = PasskeyBackupPortabilityPolicy.prepareExport(
            encryptedBackup = false,
            storedPrivateKey = "monica-passkey-key-ref-v1:key-id",
            resolvePrivateKey = {
                resolverCalled = true
                "raw-private-key"
            },
            normalizePrivateKey = { it },
        )

        assertEquals(PasskeyBackupPortabilityPolicy.ExportDecision.EncryptionRequired, decision)
        assertFalse(resolverCalled)
    }

    @Test
    fun `encrypted backup reports missing protected private key`() {
        val decision = PasskeyBackupPortabilityPolicy.prepareExport(
            encryptedBackup = true,
            storedPrivateKey = "monica-passkey-key-ref-v1:key-id",
            resolvePrivateKey = { null },
            normalizePrivateKey = { it },
        )

        assertEquals(PasskeyBackupPortabilityPolicy.ExportDecision.PrivateKeyMissing, decision)
    }

    @Test
    fun `legacy protected reference without local key restores as unavailable`() {
        val decision = PasskeyBackupPortabilityPolicy.prepareRestore(
            storedPrivateKey = "monica-passkey-key-ref-v1:key-id",
            resolvePrivateKey = { null },
            normalizePrivateKey = { it },
        )

        assertTrue(decision.privateKeyMissing)
        assertEquals("", decision.privateKeyMaterial)
        assertEquals("REFERENCE", decision.syncStatus)
    }

    @Test
    fun `legacy protected reference can still restore on original installation`() {
        val decision = PasskeyBackupPortabilityPolicy.prepareRestore(
            storedPrivateKey = "monica-passkey-key-ref-v1:key-id",
            resolvePrivateKey = { "raw-private-key" },
            normalizePrivateKey = { value -> "pkcs8:$value" },
        )

        assertFalse(decision.privateKeyMissing)
        assertEquals("pkcs8:raw-private-key", decision.privateKeyMaterial)
        assertEquals("NONE", decision.syncStatus)
    }

    @Test
    fun `encrypted backup preserves real PKCS8 private key bytes`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val source = java.util.Base64.getEncoder().encodeToString(keyPair.private.encoded)

        val decision = PasskeyBackupPortabilityPolicy.prepareExport(
            encryptedBackup = true,
            storedPrivateKey = "monica-passkey-key-ref-v1:key-id",
            resolvePrivateKey = { source },
            normalizePrivateKey = PasskeyPrivateKeySupport::exportPkcs8Base64,
        ) as PasskeyBackupPortabilityPolicy.ExportDecision.Ready

        assertArrayEquals(
            keyPair.private.encoded,
            java.util.Base64.getDecoder().decode(decision.privateKeyMaterial),
        )
    }
}
