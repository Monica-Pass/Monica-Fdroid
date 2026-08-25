package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCredentialPasswordCreationGuardTest {

    @Test
    fun newPasswordKeepsSingleEditorAndEnablesMenuBasedBatchMode() {
        val screen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()

        assertTrue(screen.contains("val isMultiCredentialMode = usesCredentialCards && credentialUsernames.size > 1"))
        assertTrue(screen.contains("multiCredentialEditorSectionName"))
        assertTrue(screen.contains("credentialMenuExpanded"))
        assertTrue(screen.contains("showCommonCredentialEditor"))
        assertTrue(screen.contains("showCredentialEditor(index)"))
        assertTrue(screen.contains("if (usesCredentialCards)"))
        assertTrue(screen.contains("R.string.add_credential"))
        assertTrue(screen.contains("viewModel.saveCredentialsAcrossTargets("))
        assertTrue(screen.contains("selectedAuthenticatorCredentialIndex"))
        assertTrue(screen.contains("credentialAttachmentDrafts"))
        assertFalse(screen.contains("selectedAttachmentCredentialIndex"))
        assertTrue(screen.contains("return \"replica:\$replicaGroupId|target:\${entry.toStorageTarget().stableKey}\""))
        assertTrue(viewModel.contains("fun saveCredentialsAcrossTargets("))
        assertTrue(viewModel.contains("SavedPasswordCredential"))
        assertFalse(screen.contains("passwords = credentialDrafts.map { it.password }"))
    }

    @Test
    fun editingKeepsLegacyGroupedPasswordEditor() {
        val screen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()

        assertTrue(screen.contains("if (isEditing)"))
        assertTrue(screen.contains("val canAddIndependentCredential"))
        assertTrue(screen.contains("originalIds.size == 1"))
        assertTrue(screen.contains("buildEditedPasswordCredentialSavePlan("))
        assertTrue(screen.contains("viewModel.savePasswordsAcrossTargets("))
        assertTrue(screen.contains("newCredentialIndex + 1"))
        assertTrue(screen.contains("(!isEditing || selectedCredentialEditorIndex > 0)"))
        assertTrue(screen.contains("val editsExistingCredential = isEditing && activeCredentialIndex == 0"))
        assertTrue(screen.contains("credentialOriginalAuthenticatorKeys"))
        assertTrue(screen.contains("R.string.add_password"))
    }

    @Test
    fun batchEditorKeepsOnlyCustomFieldsCommonAndScopesPersonalMetadataPerCredential() {
        val screen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readText()

        assertTrue(screen.contains("CredentialMetadataDraft"))
        assertTrue(screen.contains("credentialMetadataDrafts"))
        assertTrue(screen.contains("moveSingleEntryMetadataToFirstCredential()"))
        assertTrue(screen.contains("firstCredential.notes = notes"))
        assertTrue(screen.contains("showCredentialEditorContent && shouldShowCategoryAndNotes()"))
        assertTrue(screen.contains("showCredentialEditorContent && shouldShowPersonalInfo()"))
        assertTrue(screen.contains("showCredentialEditorContent && shouldShowAddressInfo()"))
        assertTrue(screen.contains("showCredentialEditorContent && shouldShowPaymentInfo()"))
        assertTrue(screen.contains("isMultiCredentialMode && showCredentialEditorContent"))
        assertTrue(screen.contains("credentialCustomFields"))
        assertTrue(screen.contains("mergePasswordCredentialCustomFields("))
        assertTrue(viewModel.contains("credentialFields = credentials[credentialIndex].customFields"))
        assertTrue(viewModel.contains("customFields = credentialCustomFields"))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath")
    }
}
