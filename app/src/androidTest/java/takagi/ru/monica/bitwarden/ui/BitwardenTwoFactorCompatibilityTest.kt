package takagi.ru.monica.bitwarden.ui

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.*
import java.lang.reflect.Proxy

class BitwardenTwoFactorCompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = "https://vaultwarden.example.test"
    private fun service(handler: (String, Array<out Any?>) -> Any): BitwardenAuthService {
        val api = Proxy.newProxyInstance(BitwardenIdentityApi::class.java.classLoader,
            arrayOf(BitwardenIdentityApi::class.java)) { _, method, args -> handler(method.name, args.orEmpty()) } as BitwardenIdentityApi
        val manager = BitwardenApiManager()
        @Suppress("UNCHECKED_CAST")
        val cache = BitwardenApiManager::class.java.getDeclaredField("identityApiCache").apply { isAccessible = true }
            .get(manager) as MutableMap<String, BitwardenIdentityApi>
        val urls = BitwardenApiFactory.inferServerUrls(server)
        BitwardenApiFactory.HeaderProfile.values().forEach { profile ->
            cache["${urls.identity.trimEnd('/')}|${urls.vault.trim()}|${profile.name}|"] = api
        }
        val vaultApi = Proxy.newProxyInstance(BitwardenVaultApi::class.java.classLoader,
            arrayOf(BitwardenVaultApi::class.java)) { _, method, args -> handler(method.name, args.orEmpty()) } as BitwardenVaultApi
        @Suppress("UNCHECKED_CAST")
        val vaultCache = BitwardenApiManager::class.java.getDeclaredField("vaultApiCache").apply { isAccessible = true }
            .get(manager) as MutableMap<String, BitwardenVaultApi>
        BitwardenApiFactory.HeaderProfile.values().forEach { profile ->
            vaultCache["${urls.api.trimEnd('/')}|${urls.vault.trim()}|${profile.name}|"] = vaultApi
        }
        return BitwardenAuthService(context, manager)
    }
    private fun error(body: String): Response<TokenResponse> = Response.error(400, body.toResponseBody("application/json".toMediaType()))

    @Test fun providerFormatsPreserveSupportedAndUnsupportedMethodsWithoutInventingABypass() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val cases = listOf(
            """{"TwoFactorProviders":[0,1,7]}""",
            """{"twoFactorProviders":["0","1","7"]}""",
            """{"TwoFactorProviders2":{"0":null,"1":{"Email":"f***@example.test"},"7":{}}}""",
            """{"twoFactorProviders2":{"0":null,"1":{},"7":{}}}""",
        )
        cases.forEach { assertEquals(listOf(0,1,7), BitwardenTwoFactorPolicy.providers(json.decodeFromString<TokenResponse>(it))) }
        assertEquals(listOf(0,1,99), BitwardenTwoFactorPolicy.providers(json.decodeFromString<TokenResponse>(
            """{"TwoFactorProviders":["0","0","bad","-1","5"],"TwoFactorProviders2":{"1":{},"99":{}}}""")))
        assertEquals(0, BitwardenTwoFactorPolicy.preferred(listOf(7,0,1)))
        assertEquals(3, BitwardenTwoFactorPolicy.preferred(listOf(7,3)))
        assertFalse(BitwardenTwoFactorPolicy.supportsCode(7))
        assertFalse(BitwardenTwoFactorPolicy.supportsCode(99))
    }

    @Test fun modernChallengeKeepsMasterKeyAndAllowsRetryThenDecryptsVaultKey() = runBlocking {
        lateinit var state: LoginResult.TwoFactorRequired
        val vaultKey = ByteArray(64) { (it + 1).toByte() }
        var submits = 0
        val auth = service { method, args ->
            when (method) {
                "preLogin" -> Response.success(PreLoginResponse(kdf = 0, kdfIterations = 5000))
                "login" -> error("""{"error":"invalid_grant","TwoFactorProviders2":{"0":null,"1":{},"7":{}}}""")
                "loginTwoFactor" -> {
                    submits++
                    assertEquals(0, args[15])
                    assertEquals(state.passwordHash, args[7])
                    if (args[14] == "000000") error("""{"error":"invalid_grant","error_description":"Invalid two-step login code."}""")
                    else {
                        assertEquals("123456", args[14])
                        Response.success(TokenResponse(accessToken = "fixture-token", key = BitwardenCrypto.encrypt(vaultKey, state.tempStretchedKey)))
                    }
                }
                else -> kotlin.error("Unexpected API call: $method")
            }
        }
        state = auth.login("fixture@example.test", "fixture-password", server).getOrThrow() as LoginResult.TwoFactorRequired
        try {
            assertEquals(listOf(0,1,7), state.providers)
            assertTrue(auth.loginTwoFactor(state, "000000", 0, serverUrl = server).isFailure)
            assertTrue(state.tempMasterKey.any { it != 0.toByte() })
            assertTrue(auth.loginTwoFactor(state, "opaque", 7, serverUrl = server).isFailure)
            assertEquals(1, submits)
            val success = auth.loginTwoFactor(state, " 123 456\n", 0, serverUrl = server).getOrThrow() as LoginResult.Success
            assertArrayEquals(vaultKey.copyOfRange(0,32), success.symmetricKey.encKey)
            assertArrayEquals(vaultKey.copyOfRange(32,64), success.symmetricKey.macKey)
            success.symmetricKey.clear()
            assertEquals(2, submits)
        } finally { state.clear() }
    }

    @Test fun successfulHttpResponseWithModernMfaStillRequiresSecondFactor() = runBlocking {
        val auth = service { method, _ -> when (method) {
            "preLogin" -> Response.success(PreLoginResponse(kdf = 0, kdfIterations = 5000))
            "login" -> Response.success(Json.decodeFromString<TokenResponse>("""{"TwoFactorProviders2":{"1":{}}}"""))
            else -> kotlin.error("Unexpected API call: $method")
        } }
        val result = auth.login("fixture@example.test", "fixture-password", server).getOrThrow()
        assertTrue(result is LoginResult.TwoFactorRequired)
        (result as LoginResult.TwoFactorRequired).clear()
    }

    @Test fun fallbackChallengeSupportsEmailRequestAndProviderOne() = runBlocking {
        lateinit var state: LoginResult.TwoFactorRequired
        var logins = 0
        var emails = 0
        val auth = service { method, args -> when(method) {
            "preLogin" -> Response.success(PreLoginResponse(kdf = 0, kdfIterations = 5000))
            "login" -> {
                logins++
                if (logins == 1) error("""{"error":"invalid_grant","error_description":"invalid_username_or_password"}""")
                else error("""{"error":"invalid_grant","twoFactorProviders2":{"1":{"Email":"f***@example.test"}}}""")
            }
            "sendTwoFactorEmailLogin" -> {
                val request = args[0] as SendEmailLoginRequest
                assertEquals(state.email,request.email)
                assertEquals(state.passwordHash,request.masterPasswordHash)
                emails++
                Response.success(Unit)
            }
            "loginTwoFactor" -> {
                assertEquals(1,args[15])
                assertEquals("123456",args[14])
                Response.success(TokenResponse(accessToken="fixture-token", key=BitwardenCrypto.encrypt(ByteArray(64){42},state.tempStretchedKey)))
            }
            else -> kotlin.error("Unexpected API call: $method")
        } }
        state = auth.login("fixture@example.test","fixture-password",server).getOrThrow() as LoginResult.TwoFactorRequired
        try {
            assertEquals(2,logins)
            assertEquals(listOf(1),state.providers)
            auth.sendTwoFactorEmailLogin(state,server).getOrThrow()
            assertEquals(1,emails)
            val result = auth.loginTwoFactor(state,"123 456",1,serverUrl=server).getOrThrow() as LoginResult.Success
            assertArrayEquals(ByteArray(32){42},result.symmetricKey.encKey)
            result.symmetricKey.clear()
        } finally { state.clear() }
    }
}
