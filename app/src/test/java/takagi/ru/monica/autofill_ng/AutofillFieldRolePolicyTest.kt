package takagi.ru.monica.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

class AutofillFieldRolePolicyTest {

    @Test
    fun explicitAccountEvidenceBeatsConflictingPasswordScore() {
        val selected = AutofillFieldRolePolicy.select(
            listOf(
                candidate(FieldHint.PASSWORD, score = 8f, accuracy = Accuracy.HIGH),
                candidate(FieldHint.USERNAME, score = 4f, accuracy = Accuracy.HIGH),
                candidate(FieldHint.PHONE_NUMBER, score = 1.5f, accuracy = Accuracy.MEDIUM),
            )
        )

        assertEquals(FieldHint.USERNAME, selected)
    }

    @Test
    fun genericAccountFallbackDoesNotOverrideExplicitPassword() {
        val selected = AutofillFieldRolePolicy.select(
            listOf(
                candidate(FieldHint.PASSWORD, score = 4f, accuracy = Accuracy.HIGH),
                candidate(FieldHint.USERNAME, score = 0.3f, accuracy = Accuracy.LOWEST),
            )
        )

        assertEquals(FieldHint.PASSWORD, selected)
    }

    @Test
    fun newPasswordKeepsPriorityOverGenericVisibleTextFallback() {
        val selected = AutofillFieldRolePolicy.select(
            listOf(
                candidate(FieldHint.NEW_PASSWORD, score = 4f, accuracy = Accuracy.HIGH),
                candidate(FieldHint.USERNAME, score = 0.3f, accuracy = Accuracy.LOWEST),
            )
        )

        assertEquals(FieldHint.NEW_PASSWORD, selected)
    }

    private fun candidate(
        hint: FieldHint,
        score: Float,
        accuracy: Accuracy,
    ) = AutofillFieldRoleCandidate(
        value = hint,
        hint = hint,
        score = score,
        strongestAccuracy = accuracy,
    )
}
