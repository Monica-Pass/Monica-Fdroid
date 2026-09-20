package takagi.ru.monica.utils

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.jcajce.*
import takagi.ru.monica.data.model.GpgEntryFields
import java.io.ByteArrayOutputStream

class GpgKeyGeneratorTest {
    private val provider = BouncyCastleProvider()
    @Test fun protectedKeySignsEncryptsAndSurvivesFieldTransport() {
        val key = GpgKeyGenerator.generate("Monica Test", "test@example.org", passphrase = "test-only".toCharArray())
        val secret = PGPSecretKeyRing(PGPUtil.getDecoderStream(key.privateKey.byteInputStream()), JcaKeyFingerprintCalculator())
        val public = PGPPublicKeyRing(PGPUtil.getDecoderStream(key.publicKey.byteInputStream()), JcaKeyFingerprintCalculator())
        val decryptor = JcePBESecretKeyDecryptorBuilder().setProvider(provider).build("test-only".toCharArray())
        val signing = secret.secretKey.extractPrivateKey(decryptor)
        val data = "GPG interoperability regression".toByteArray()
        val signer = PGPSignatureGenerator(JcaPGPContentSignerBuilder(public.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(provider))
        signer.init(PGPSignature.BINARY_DOCUMENT, signing)
        signer.update(data)
        val signature = signer.generate()
        signature.init(JcaPGPContentVerifierBuilderProvider().setProvider(provider), public.publicKey)
        signature.update(data)
        assertTrue(signature.verify())
        val encryption = public.publicKeys.asSequence().first { it.isEncryptionKey }
        val encrypted = ByteArrayOutputStream()
        val encryptor = PGPEncryptedDataGenerator(JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setProvider(provider))
        encryptor.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(encryption).setProvider(provider))
        encryptor.open(encrypted, data.size.toLong()).use { it.write(data) }
        val packet = (PGPObjectFactory(encrypted.toByteArray(), JcaKeyFingerprintCalculator()).nextObject() as PGPEncryptedDataList)[0] as PGPPublicKeyEncryptedData
        val decryption = secret.getSecretKey(encryption.keyID).extractPrivateKey(decryptor)
        assertArrayEquals(data, packet.getDataStream(JcePublicKeyDataDecryptorFactoryBuilder().setProvider(provider).build(decryption)).readBytes())
        assertTrue(packet.verify())
        assertEquals(365L * 86400, public.publicKey.validSeconds)
        assertEquals(key, GpgKeyGenerator.parse(key.privateKey))
        val fields = GpgEntryFields.encode(key)
        assertTrue(fields.none { it.value.contains("PRIVATE KEY") })
        assertTrue(fields.all { it.value.length <= 2000 })
        assertEquals(key.fingerprint, GpgKeyGenerator.parse(GpgEntryFields.publicKey(fields.reversed().associate { it.title to it.value })).fingerprint)
        assertEquals(key.fingerprint, GpgKeyGenerator.parse(GpgEntryFields.publicKey(fields.associate { it.title to it.value.trim() })).fingerprint)
        val incoming = takagi.ru.monica.bitwarden.service.BitwardenPasswordCustomFieldAdapter.extractUserFields(
            fields.map { takagi.ru.monica.bitwarden.service.BitwardenPlainCustomField(it.title, it.value, 0) })
        assertEquals(key.fingerprint, GpgKeyGenerator.parse(GpgEntryFields.publicKey(incoming.associate { it.name to it.value })).fingerprint)
        assertTrue(GpgEntryFields.isGpg(incoming.associate { it.name to it.value }))
        try { secret.secretKey.extractPrivateKey(JcePBESecretKeyDecryptorBuilder().setProvider(provider).build("wrong".toCharArray())); fail("Wrong passphrase accepted") }
        catch (_: PGPException) { }
    }
    @Test fun malformedInputAndUnsupportedParametersAreRejected() {
        for (value in listOf("not a key", "x".repeat(GpgKeyGenerator.MAX_IMPORT_BYTES + 1))) {
            assertTrue(runCatching { GpgKeyGenerator.parse(value) }.isFailure)
        }
        assertTrue(runCatching { GpgKeyGenerator.generate("", "") }.isFailure)
        assertTrue(runCatching { GpgKeyGenerator.generate("name\nforged", "") }.isFailure)
        assertTrue(runCatching { GpgKeyGenerator.generate("name", "", bits = 1024) }.isFailure)
        assertTrue(runCatching { GpgEntryFields.publicKey(mapOf(GpgEntryFields.PUBLIC_PREFIX + "0001" to "missing first")) }.isFailure)
    }

    @Test fun privateKeyEntryNeverMatchesAWebsitePassword() {
        val key = takagi.ru.monica.data.PasswordEntry(id = 1, title = "example.org", website = "https://example.org",
            username = "user", password = "encrypted-private-key", loginType = GpgEntryFields.TYPE)
        val matcher = takagi.ru.monica.autofill_ng.BitwardenLikeAutofillMatcherNg()
        assertTrue(matcher.match(listOf(key), "org.example", "example.org").isEmpty())
    }
    @Test fun rsa4096BinaryAndPublicOnlyImportRoundTrip() {
        val key = GpgKeyGenerator.generate("Binary fixture", "", bits = 4096, validDays = 0)
        val secret = PGPSecretKeyRing(PGPUtil.getDecoderStream(key.privateKey.byteInputStream()), JcaKeyFingerprintCalculator())
        assertEquals(4096, secret.publicKey.bitStrength)
        assertEquals(0L, secret.publicKey.validSeconds)
        assertEquals(key, GpgKeyGenerator.parse(secret.encoded))
        val public = GpgKeyGenerator.parse(key.publicKey)
        assertEquals("", public.privateKey)
        assertEquals(key.fingerprint, public.fingerprint)
        assertEquals(key.publicKey, public.publicKey)
        assertTrue(runCatching { GpgKeyGenerator.parse(secret.encoded + secret.encoded) }.isFailure)
    }

}
