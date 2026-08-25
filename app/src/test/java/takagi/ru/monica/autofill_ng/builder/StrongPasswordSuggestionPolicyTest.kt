package takagi.ru.monica.autofill_ng.builder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

class StrongPasswordSuggestionPolicyTest {

    @Test
    fun suppressesLoginFormWithOneContradictoryNewPasswordField() {
        assertFalse(
            StrongPasswordSuggestionPolicy.shouldOffer(
                listOf(FieldHint.NEW_PASSWORD, FieldHint.PASSWORD)
            )
        )
    }

    @Test
    fun allowsRegistrationWithNewPasswordOnly() {
        assertTrue(
            StrongPasswordSuggestionPolicy.shouldOffer(
                listOf(FieldHint.USERNAME, FieldHint.NEW_PASSWORD)
            )
        )
    }

    @Test
    fun allowsChangePasswordWithNewAndConfirmationFields() {
        assertTrue(
            StrongPasswordSuggestionPolicy.shouldOffer(
                listOf(
                    FieldHint.PASSWORD,
                    FieldHint.NEW_PASSWORD,
                    FieldHint.NEW_PASSWORD,
                )
            )
        )
    }

    @Test
    fun rejectsFormsWithoutNewPasswordFields() {
        assertFalse(
            StrongPasswordSuggestionPolicy.shouldOffer(
                listOf(FieldHint.USERNAME, FieldHint.PASSWORD)
            )
        )
    }
}
