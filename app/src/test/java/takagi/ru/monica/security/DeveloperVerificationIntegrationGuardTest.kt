package takagi.ru.monica.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperVerificationIntegrationGuardTest {
    private fun projectFile(path: String): File {
        val workingDirectory = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(workingDirectory) { it.parentFile }
            .flatMap { directory ->
                sequenceOf(
                    File(directory, path),
                    File(directory, "Monica for Android/$path"),
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve project file '$path' from $workingDirectory")
    }

    @Test
    fun `autofill and ime entry points apply developer bypass policy`() {
        val files = listOf(
            "app/src/main/java/takagi/ru/monica/autofill_ng/MonicaAutofillServiceNg.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillUnlockActivity.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillCipherCallbackActivity.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/BiometricAuthActivity.kt",
            "app/src/main/java/takagi/ru/monica/ime/ImeUnlockActivity.kt",
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt",
        )

        files.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue("Missing developer verification policy in $path", source.contains("DeveloperVerificationPolicy"))
        }
    }

    @Test
    fun `shared confirmation dialog can omit identity controls`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/M3IdentityVerifyDialog.kt"
        ).readText()

        assertTrue(source.contains("requireIdentityVerification: Boolean = true"))
        assertTrue(source.contains("if (requireIdentityVerification)"))
    }
}
