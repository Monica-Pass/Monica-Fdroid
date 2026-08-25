package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordAccountLabelRegressionGuardTest {

    @Test
    fun accountLabelStaysConsistentWhenUsernameSeparationIsDisabled() {
        val addEditSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val detailSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/PasswordDetailScreen.kt"
        ).readText()
        val basicInfoBody = detailSource
            .substringAfter("private fun BasicInfoCard(")
            .substringBefore("private fun SsoLoginCard(")
        val separationDisabledBranch = basicInfoBody
            .substringAfter("} else {")
            .substringBefore("}\n            }")

        assertTrue(
            "The add/edit page must label the primary account identifier as Account.",
            addEditSource.contains("label = { Text(stringResource(R.string.field_account)) }")
        )
        assertTrue(
            "The detail page must use the same Account label when username separation is disabled.",
            separationDisabledBranch.contains("label = stringResource(R.string.field_account)")
        )
        assertFalse(
            "The disabled-separation branch must not rename the same value to Username.",
            separationDisabledBranch.contains("label = stringResource(R.string.username)")
        )
        assertTrue(
            "When separation is enabled, the detail page must still expose both Account and Username labels.",
            basicInfoBody.contains("label = stringResource(R.string.field_account)") &&
                basicInfoBody.contains("label = stringResource(R.string.autofill_username)")
        )
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
