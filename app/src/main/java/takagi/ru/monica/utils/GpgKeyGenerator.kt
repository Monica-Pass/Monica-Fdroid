package takagi.ru.monica.utils

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.jcajce.*
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date

/** OpenPGP v4 RSA signing primary + separate encryption subkey. No global provider changes. */
object GpgKeyGenerator {
    data class Key(val publicKey: String, val privateKey: String, val fingerprint: String, val userId: String)
    private val provider = BouncyCastleProvider()
    const val MAX_IMPORT_BYTES = 1024 * 1024

    fun isValidName(value: String): Boolean = value.isNotBlank() && value.length <= 128 &&
        value.none { it.isISOControl() || it in "<>" }

    fun isValidEmail(value: String): Boolean = value.isEmpty() ||
        (value.length <= 254 && value.none { it.isWhitespace() || it in "<>" || it.isISOControl() } &&
            Regex("[^@]+@[^@]+\\.[^@]+").matches(value))

    fun generate(name: String, email: String, bits: Int = 3072, validDays: Int = 365, passphrase: CharArray = charArrayOf()): Key {
        require(bits in setOf(3072, 4096))
        require(validDays in setOf(0, 365, 730))
        require(isValidName(name))
        require(isValidEmail(email))
        val userId = name.trim() + if (email.isBlank()) "" else " <${email.trim()}>"
        val random = SecureRandom()
        val generator = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(bits, random) }
        val now = Date()
        val primary = JcaPGPKeyPair(PGPPublicKey.RSA_SIGN, generator.generateKeyPair(), now)
        val encryption = JcaPGPKeyPair(PGPPublicKey.RSA_ENCRYPT, generator.generateKeyPair(), now)
        val digests = JcaPGPDigestCalculatorProviderBuilder().setProvider(provider).build()
        val signatures = JcaPGPContentSignerBuilder(PGPPublicKey.RSA_SIGN, HashAlgorithmTags.SHA256).setProvider(provider)
        fun packets(flags: Int) = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, flags)
            if (validDays > 0) setKeyExpirationTime(false, validDays * 86400L)
            setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA512))
            setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128))
        }.generate()
        val encryptor = if (passphrase.isEmpty()) null else JcePBESecretKeyEncryptorBuilder(
            SymmetricKeyAlgorithmTags.AES_256, digests.get(HashAlgorithmTags.SHA256), 0xc0
        ).setProvider(provider).setSecureRandom(random).build(passphrase)
        val ring = PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, primary, userId,
            digests.get(HashAlgorithmTags.SHA1), packets(KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA), null, signatures, encryptor)
        ring.addSubKey(encryption, packets(KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE), null)
        val publicRing = ring.generatePublicKeyRing()
        return Key(armor(publicRing.encoded), armor(ring.generateSecretKeyRing().encoded),
            fingerprint(publicRing.publicKey), userId)
    }

    /** Import one complete key ring; derive the public certificate from a private ring. */
    fun parse(text: String): Key = parse(text.toByteArray(Charsets.UTF_8))

    fun parse(bytes: ByteArray): Key {
        require(bytes.size <= MAX_IMPORT_BYTES) { "Key file is too large" }
        val factory = PGPObjectFactory(PGPUtil.getDecoderStream(bytes.inputStream()), JcaKeyFingerprintCalculator())
        val objects = generateSequence { factory.nextObject() }.filterNot { it is PGPMarker }.toList()
        require(objects.size == 1) { "Select a file containing one OpenPGP key" }
        val secret = objects.single() as? PGPSecretKeyRing
        val publicRing = if (secret != null) PGPPublicKeyRing(secret.publicKeys.asSequence().toList())
            else objects.single() as? PGPPublicKeyRing ?: error("Not an OpenPGP key ring")
        val primary = publicRing.publicKey
        require(primary.isMasterKey)
        return Key(armor(publicRing.encoded), secret?.let { armor(it.encoded) }.orEmpty(),
            fingerprint(primary), primary.userIDs.asSequence().firstOrNull().orEmpty())
    }

    private fun fingerprint(key: PGPPublicKey) = key.fingerprint.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun armor(bytes: ByteArray): String = ByteArrayOutputStream().also { out ->
        ArmoredOutputStream(out).use { it.write(bytes) }
    }.toString(Charsets.UTF_8.name())
}
