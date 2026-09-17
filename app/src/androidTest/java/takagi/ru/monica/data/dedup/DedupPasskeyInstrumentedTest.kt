package takagi.ru.monica.data.dedup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver

@RunWith(AndroidJUnit4::class)
class DedupPasskeyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var repository: PasskeyRepository
    private lateinit var service: DedupMergeService
    private val nativeAliases = mutableListOf<String>()
    private val sources = setOf("keepass:1", "bitwarden:1")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        repository = PasskeyRepository(db.passkeyDao(), context = context)
        db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = 1, name = "Synthetic KeePass", filePath = "/synthetic/unused.kdbx"))
        db.bitwardenVaultDao().insert(BitwardenVault(id = 1, email = "synthetic@example.invalid"))
        service = DedupMergeService(PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()),
            repository, CustomFieldRepository(db.customFieldDao()), db.localKeePassDatabaseDao(),
            db.localMdbxDatabaseDao(), db.bitwardenVaultDao(), security, AppLocaleStringResolver(context))
    }

    @After fun tearDown() = runBlocking {
        repository.deleteAllPasskeys()
        db.close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store -> nativeAliases.forEach(store::deleteEntry) }
    }

    @Test fun identicalCredentialsMergeAndSourceStillSignsAfterDeletingTheCopy() = runBlocking {
        val pair = keyPair()
        val first = credential(pair)
        repository.savePasskey(first)
        repository.savePasskey(bitwarden(first).copy(passkeyMode = PasskeyEntry.MODE_BW_COMPAT, useCount = 10, lastUsedAt = 99,
            credentialId = checkNotNull(takagi.ru.monica.passkey.PasskeyCredentialIdCodec.toWebAuthnId(first.credentialId))))
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(1, plan.writableItems)
        assertEquals(1, plan.consolidatedCopies)
        val result = service.executePlan(plan)
        assertEquals(result.failures.toString(), 1, result.insertedPasskeys)
        val copied = repository.getAllPasskeysSync().single { it.isLocalOnlyPasskey() }
        assertEquals(takagi.ru.monica.passkey.PasskeyCredentialIdCodec.normalize(first.credentialId),
            takagi.ru.monica.passkey.PasskeyCredentialIdCodec.normalize(copied.credentialId))
        assertEquals(0L, copied.signCount)
        assertSigns(copied, pair)
        assertEquals(before, repository.getAllPasskeysSync().filterNot { it.isLocalOnlyPasskey() })
        assertEquals(0, service.executePlan(plan).insertedItems)
        repository.deletePasskey(copied)
        before.forEach { assertSigns(it, pair) }
        repository.deletePasskey(before.first())
        assertSigns(repository.getAllPasskeysSync().single(), pair)
    }

    @Test fun differentCredentialsForTheSameAccountAreBothKept() = runBlocking {
        repository.savePasskey(credential(keyPair()))
        repository.savePasskey(bitwarden(credential(keyPair())))
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(2, plan.writableItems)
        assertEquals(0, plan.consolidatedCopies)
        assertEquals(2, service.executePlan(plan).insertedPasskeys)
    }

    @Test fun sameCredentialIdWithDifferentKeysOrMetadataIsNotGuessed() = runBlocking {
        val first = credential(keyPair())
        repository.savePasskey(first)
        repository.savePasskey(bitwarden(credential(keyPair())).copy(credentialId = first.credentialId))
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(0, plan.writableItems)
        assertEquals(2, plan.unsupportedSourcePasskeys)
        assertTrue(plan.previewPasskeys.all { it.skipReason == DedupPasskeySkipReason.CREDENTIAL_CONFLICT })
        assertEquals(0, service.executePlan(plan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
    }

    @Test fun nonzeroBoundAndReferenceCredentialsStayUntouched() = runBlocking {
        val first = credential(keyPair())
        repository.savePasskey(first.copy(signCount = 9))
        repository.savePasskey(credential(keyPair()).copy(boundPasswordId = 123))
        repository.savePasskey(credential(keyPair()).copy(syncStatus = "REFERENCE", privateKeyAlias = ""))
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(setOf(DedupPasskeySkipReason.NONZERO_COUNTER, DedupPasskeySkipReason.BOUND_PASSWORD,
            DedupPasskeySkipReason.REFERENCE_OR_MISSING_KEY), plan.previewPasskeys.map { it.skipReason }.toSet())
        assertEquals(0, service.executePlan(plan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
    }

    @Test fun targetCredentialCollisionNeverOverwritesItsKey() = runBlocking {
        val first = credential(keyPair())
        repository.savePasskey(first)
        repository.savePasskey(credential(keyPair()).copy(credentialId = first.credentialId, keepassDatabaseId = null))
        val before = repository.getAllPasskeysSync()
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(DedupPasskeySkipReason.CREDENTIAL_CONFLICT, plan.previewPasskeys.single().skipReason)
        assertEquals(0, service.executePlan(plan).insertedItems)
        assertEquals(before, repository.getAllPasskeysSync())
    }

    @Test fun deviceKeyCanStayLocalButIsNotPretendedToBePortable() = runBlocking {
        val alias = "monica-dedup-test-${UUID.randomUUID()}".also(nativeAliases::add)
        val pair = KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        repository.savePasskey(credential(pair, alias))
        val mdbxPlan = service.buildPlan(sources, DedupMergeTarget.MdbxDatabase(7, "Synthetic target"))
        assertEquals(DedupPasskeySkipReason.DEVICE_KEY, mdbxPlan.previewPasskeys.single().skipReason)
        val localPlan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(1, service.executePlan(localPlan).insertedPasskeys)
        val copy = repository.getAllPasskeysSync().single { it.isLocalOnlyPasskey() }
        repository.deletePasskey(copy)
        val signer = PasskeyPrivateKeySupport.createSignature(pair.private, -7)
        val challenge = byteArrayOf(1, 2, 3)
        signer.update(challenge)
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signer.sign()))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun credential(pair: KeyPair, alias: String = Base64.getEncoder().encodeToString(pair.private.encoded)): PasskeyEntry {
        val public = pair.public as ECPublicKey
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val cose = byteArrayOf(0xa5.toByte(), 1, 2, 3, 0x26, 0x20, 1, 0x21, 0x58, 0x20) +
            coordinate(public.w.affineX) + byteArrayOf(0x22, 0x58, 0x20) + coordinate(public.w.affineY)
        return PasskeyEntry(credentialId = UUID.randomUUID().toString(), rpId = "example.invalid", rpName = "Example",
            userId = "AQID", userName = "alice@example.invalid", userDisplayName = "Alice",
            publicKey = Base64.getEncoder().encodeToString(cose), privateKeyAlias = alias,
            keepassDatabaseId = 1, passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT, createdAt = 1, lastUsedAt = 1)
    }

    private fun bitwarden(source: PasskeyEntry) = source.copy(keepassDatabaseId = null,
        bitwardenVaultId = 1, bitwardenCipherId = UUID.randomUUID().toString(), syncStatus = "SYNCED")

    private fun assertSigns(entry: PasskeyEntry, pair: KeyPair) {
        val material = checkNotNull(PasskeyPrivateKeyStore.resolve(security, entry.privateKeyAlias))
        val key = checkNotNull(PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(material)).privateKey
        val challenge = UUID.randomUUID().toString().toByteArray()
        val signer = PasskeyPrivateKeySupport.createSignature(key, entry.publicKeyAlgorithm).apply { update(challenge) }
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }.verify(signer.sign()))
    }
}
