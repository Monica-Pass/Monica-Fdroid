package takagi.ru.monica.bitwarden.ui

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.BitwardenAuthService
import takagi.ru.monica.bitwarden.service.LoginResult
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.util.TotpGenerator
import java.security.SecureRandom
import java.util.UUID

/** Opt-in test against disposable local Vaultwarden and Mailpit; never uses saved accounts. */
class BitwardenLiveLoginTest {
    private val http = OkHttpClient()
    private val server = "http://127.0.0.1:18080"
    private val mail = "http://127.0.0.1:18025"
    private fun request(url: String, body: JSONObject? = null, token: String? = null, method: String = "POST"): JSONObject {
        val builder = Request.Builder().url(url).header("Device-Type", "0")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body == null) builder.get() else builder.method(method, body.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Fixture endpoint ${url.substringAfterLast('/')} HTTP ${it.code}" }
            val text = it.body!!.string()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
    private suspend fun emailCode(email: String, excluded: Set<String>): String {
        repeat(40) {
            val messages = request("$mail/api/v1/messages").getJSONArray("messages")
            for (i in 0 until messages.length()) {
                val message = messages.getJSONObject(i)
                if (message.getString("ID") in excluded || !message.getJSONArray("To").toString().contains(email)) continue
                val text = request("$mail/api/v1/message/${message.getString("ID")}").getString("Text")
                Regex("(?<![0-9])[0-9]{6}(?![0-9])").find(text)?.let { return it.value }
            }
            delay(250)
        }
        error("No verification email received by local Mailpit")
    }
    private fun mailIds(): Set<String> {
        val items=request("$mail/api/v1/messages").getJSONArray("messages")
        return (0 until items.length()).map { items.getJSONObject(it).getString("ID") }.toSet()
    }
    @Test fun realPasswordEmailAndTotpLoginDecryptTheRegisteredVaultKey() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("monicaLiveMfa") == "true")
        val email="monica-${UUID.randomUUID()}@example.test"
        val password=UUID.randomUUID().toString()+UUID.randomUUID().toString()
        val key=ByteArray(64).also { SecureRandom().nextBytes(it) }
        val master=BitwardenCrypto.deriveMasterKeyPbkdf2(password,email,600000)
        val stretched=BitwardenCrypto.stretchMasterKey(master)
        val hash=BitwardenCrypto.deriveMasterPasswordHash(master,password)
        val auth=BitwardenAuthService(InstrumentationRegistry.getInstrumentation().targetContext)
        fun checked(result: LoginResult): LoginResult.Success {
            assertTrue("Expected successful real-server login",result is LoginResult.Success)
            return (result as LoginResult.Success).also {
                assertArrayEquals(key.copyOfRange(0,32),it.symmetricKey.encKey)
                assertArrayEquals(key.copyOfRange(32,64),it.symmetricKey.macKey)
            }
        }
        try {
            request("$server/identity/accounts/register",JSONObject().put("email",email).put("name","Disposable Monica MFA test")
                .put("masterPasswordHash",hash).put("key",BitwardenCrypto.encrypt(key,stretched)).put("kdf",0).put("kdfIterations",600000))
            assertTrue(auth.login(email,password+"wrong",server).isFailure)
            val initial=checked(auth.login(email,password,server).getOrThrow())
            val bearer=initial.accessToken
            initial.clearKeys()
            println("LIVE MFA: password login and wrong-password rejection passed")
            val ids=mailIds()
            request("$server/api/two-factor/send-email",JSONObject().put("email",email).put("masterPasswordHash",hash),bearer)
            request("$server/api/two-factor/email",JSONObject().put("email",email).put("token",emailCode(email,ids)).put("masterPasswordHash",hash),bearer,"PUT")
            val emailState=auth.login(email,password,server).getOrThrow() as LoginResult.TwoFactorRequired
            try {
                assertEquals(listOf(1),emailState.providers)
                val before=mailIds()
                auth.sendTwoFactorEmailLogin(emailState,server).getOrThrow()
                val code=emailCode(email,before)
                assertTrue(auth.loginTwoFactor(emailState,"invalid",1,serverUrl=server).isFailure)
                checked(auth.loginTwoFactor(emailState,code,1,serverUrl=server).getOrThrow()).clearKeys()
            } finally { emailState.clear() }
            println("LIVE MFA: email challenge, SMTP delivery, wrong-code retry and key decryption passed")
            val secret=request("$server/api/two-factor/get-authenticator",JSONObject().put("masterPasswordHash",hash),bearer).getString("key")
            val totp=TotpData(secret=secret)
            request("$server/api/two-factor/authenticator",JSONObject().put("masterPasswordHash",hash).put("key",secret)
                .put("token",TotpGenerator.generateOtp(totp)),bearer)
            // Activation consumes this time step on Vaultwarden. Wait for the next code, without weakening replay protection.
            delay((30-System.currentTimeMillis()/1000%30)*1000+1100)
            val state=auth.login(email,password,server).getOrThrow() as LoginResult.TwoFactorRequired
            try {
                assertEquals(setOf(0,1),state.providers.toSet())
                assertTrue(auth.loginTwoFactor(state,"invalid",0,serverUrl=server).isFailure)
                checked(auth.loginTwoFactor(state,TotpGenerator.generateOtp(totp),0,serverUrl=server).getOrThrow()).clearKeys()
            } finally { state.clear() }
            println("LIVE MFA: multiple methods, TOTP wrong-code retry and key decryption passed")
        } finally { key.fill(0); master.fill(0); stretched.clear() }
    }
}
