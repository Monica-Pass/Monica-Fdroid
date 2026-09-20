package takagi.ru.monica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.ui.screens.ResultCard
import takagi.ru.monica.ui.screens.SshKeyResult

class GeneratorResultMenuTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyTextResultCopiesWholeValueAndOffersBothDraftFields() {
        val result = androidx.compose.runtime.mutableStateOf("symbols-%42-".repeat(16))
        var copied = ""
        var username = ""
        var password = ""
        compose.setContent { MaterialTheme {
            ResultCard(result.value, onCopy = { copied = it },
                onCreateUsername = { username = it }, onCreatePassword = { password = it })
        } }
        for (value in listOf(result.value, "word-example", "a long phrase with spaces", "004291")) {
            compose.runOnIdle { result.value = value }
            compose.onNodeWithTag("generator_result").performClick()
            compose.onNode(isPopup()).assertExists()
            if (value == "004291") captureMenu("pin-menu.png")
            compose.onAllNodesWithText(context.getString(R.string.copy)).filterToOne(hasAnyAncestor(isPopup())).performClick()
            assertEquals(value, copied)
            compose.onNodeWithTag("generator_result").performClick()
            compose.onNodeWithText(context.getString(R.string.generator_create_username)).performClick()
            assertEquals(value, username)
            compose.onNodeWithTag("generator_result").performClick()
            compose.onNodeWithText(context.getString(R.string.generator_create_password)).performClick()
            assertEquals(value, password)
        }
    }

    @Test fun sshCardAndOverflowCopyDistinctCompleteMaterials() {
        val key = SshKeyData(algorithm = "ED25519", publicKeyOpenSsh = "ssh-ed25519 AAAA-test comment",
            privateKeyOpenSsh = "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture\n-----END OPENSSH PRIVATE KEY-----",
            fingerprintSha256 = "SHA256:fixture")
        var copied: Pair<String, Boolean>? = null
        compose.setContent { MaterialTheme { SshKeyResult(key) { text, secret -> copied = text to secret } } }
        for ((label, value, secret) in listOf(
            Triple(R.string.ssh_key_copy_public, key.publicKeyOpenSsh, false),
            Triple(R.string.ssh_key_copy_fingerprint, key.fingerprintSha256, false),
            Triple(R.string.ssh_key_copy_private, key.privateKeyOpenSsh, true)
        )) {
            compose.onNodeWithTag("ssh_result").performClick()
            compose.onNodeWithText(key.privateKeyOpenSsh).assertDoesNotExist()
            compose.onNodeWithText(context.getString(label)).performClick()
            assertEquals(value to secret, copied)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.ssh_key_copy_public)).assertIsDisplayed()
        captureMenu("ssh-menu.png")
    }

    private fun captureMenu(name: String) {
        val output = File(context.getExternalFilesDir(null), "generator-menus/$name")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNode(isPopup()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
