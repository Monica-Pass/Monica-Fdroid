package takagi.ru.monica.autofill_ng.builder

import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

/**
 * Applies a conservative form-level gate before offering a generated password.
 *
 * Some websites incorrectly mark a login identifier as `new-password`. When the
 * same form also contains one normal password field, treating that lone marker as
 * registration intent puts the generator on the identifier field. A genuine
 * change-password form normally exposes both new-password and confirmation fields.
 */
internal object StrongPasswordSuggestionPolicy {

    fun shouldOffer(fieldHints: List<FieldHint>): Boolean {
        val newPasswordCount = fieldHints.count { it == FieldHint.NEW_PASSWORD }
        if (newPasswordCount == 0) return false

        val hasCurrentPassword = fieldHints.any { it == FieldHint.PASSWORD }
        return !hasCurrentPassword || newPasswordCount >= 2
    }
}
