package takagi.ru.monica.autofill_ng

import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal data class AutofillFieldRoleCandidate<T>(
    val value: T,
    val hint: FieldHint,
    val score: Float,
    val strongestAccuracy: Accuracy,
)

internal data class AutofillFieldRoleSelection<T>(
    val value: T,
    val resolvedExplicitAccountPasswordConflict: Boolean,
)

internal object AutofillFieldRolePolicy {
    fun <T> select(candidates: List<AutofillFieldRoleCandidate<T>>): T? {
        return selectWithDiagnostics(candidates)?.value
    }

    fun <T> selectWithDiagnostics(
        candidates: List<AutofillFieldRoleCandidate<T>>
    ): AutofillFieldRoleSelection<T>? {
        if (candidates.isEmpty()) return null

        val hasPasswordCandidate = candidates.any { it.hint.isPasswordHint() }
        val explicitAccountCandidates = candidates.filter { candidate ->
            candidate.hint.isAccountHint() &&
                candidate.strongestAccuracy.score >= Accuracy.MEDIUM.score
        }
        val eligibleCandidates = if (
            hasPasswordCandidate && explicitAccountCandidates.isNotEmpty()
        ) {
            explicitAccountCandidates
        } else {
            candidates
        }

        val selected = eligibleCandidates
            .maxWithOrNull(
                compareBy<AutofillFieldRoleCandidate<T>> { it.score }
                    .thenBy { it.strongestAccuracy.score }
            )
            ?: return null
        return AutofillFieldRoleSelection(
            value = selected.value,
            resolvedExplicitAccountPasswordConflict =
                hasPasswordCandidate && explicitAccountCandidates.isNotEmpty(),
        )
    }

    private fun FieldHint.isAccountHint(): Boolean =
        this == FieldHint.USERNAME ||
            this == FieldHint.EMAIL_ADDRESS ||
            this == FieldHint.PHONE_NUMBER

    private fun FieldHint.isPasswordHint(): Boolean =
        this == FieldHint.PASSWORD || this == FieldHint.NEW_PASSWORD
}
