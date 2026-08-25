package takagi.ru.monica.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordTotpCrossDatabaseBindingGuardTest {

    @Test
    fun passwordEditorDoesNotAddAuthenticatorSourceDatabaseToSaveTargets() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val targetResolutionBody = source
            .substringAfter("fun buildStorageTargetsForSave(")
            .substringBefore("fun normalizeCommonTemplateType(")

        assertTrue(
            "Password save targets must still be normalized from the storage sources explicitly selected for the password.",
            targetResolutionBody.contains("selectedStorageTargets.toList().normalizedStorageTargets()")
        )
        assertFalse(
            "Selecting an existing authenticator must not add its database to password save targets; doing so creates an unintended password replica.",
            targetResolutionBody.contains("selectedExistingTotpStorageTarget") ||
                targetResolutionBody.contains("currentTargets + authenticatorTarget")
        )
    }

    @Test
    fun passwordSaveReturnsAllTargetPasswordIdsForTotpBinding() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()

        assertTrue(
            "Saving across targets must expose all saved target password ids, not only the first one.",
            source.contains("onCompleteWithIds")
        )
        assertTrue(
            "Saving across targets must collect the first password id from every target.",
            source.contains("savedTargetFirstIds += createdId")
        )
    }

    @Test
    fun boundTotpReusesTheSelectedAuthenticatorAcrossDatabases() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readText()
        val bindingBody = source
            .substringAfter("private suspend fun savePasswordBoundTotpInternal(")
            .substringBefore("/**\r\n     * 根据ID获取TOTP项目")
            .ifBlank {
                source
                    .substringAfter("private suspend fun savePasswordBoundTotpInternal(")
                    .substringBefore("/**\n     * 根据ID获取TOTP项目")
            }

        assertTrue(
            "Password replicas explicitly selected by the user may still receive their own binding.",
            source.contains("fun savePasswordBoundTotps(") &&
                source.contains("forEachIndexed")
        )
        assertTrue(
            "The selected existing authenticator must be reused for the first password instead of being copied into the password database.",
            bindingBody.contains("val preferredItem = selectedSourceItem") &&
                bindingBody.contains("followBoundPasswordStorage = !preserveSelectedSourceStorage")
        )
        assertFalse(
            "A selected authenticator from another database must not be rejected solely because its storage differs from the password.",
            bindingBody.contains("selectedSourceItem\n                ?.takeIf { it.isInBoundPasswordStorage() }")
        )
        assertTrue(
            "Duplicate cleanup must preserve the actual saved TOTP item, including newly copied items.",
            source.contains("keepItemId = savedItemId")
        )
        assertTrue(
            "TOTP save internals must return the saved item id so duplicate cleanup can be exact.",
            source.contains("private suspend fun saveTotpItemInternal") &&
                source.contains("): Long?")
        )
    }

    @Test
    fun authenticatorPickerMirrorsPasswordPickerSourceFiltering() {
        val passwordScreen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val passwordPicker = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/PasswordEntryPickerBottomSheet.kt"
        ).readText()

        assertTrue(
            "Password page authenticator picker must expose source filter chips.",
            passwordScreen.contains("PasswordTotpPickerSourceFilter") &&
                passwordScreen.contains("filter_keepass") &&
                passwordScreen.contains("filter_bitwarden") &&
                passwordScreen.contains("MDBX")
        )
        assertTrue(
            "Authenticator picker search field should hide the Material TextField underline.",
            passwordScreen.contains("focusedIndicatorColor = Color.Transparent") &&
                passwordScreen.contains("unfocusedIndicatorColor = Color.Transparent")
        )
        assertTrue(
            "Password binding picker should keep the same underline-free search styling.",
            passwordPicker.contains("focusedIndicatorColor = Color.Transparent") &&
                passwordPicker.contains("unfocusedIndicatorColor = Color.Transparent")
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
