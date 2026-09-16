package takagi.ru.monica.autofill_ng

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager

internal suspend fun isAutofillRequestBlocked(context: Context, fieldSignatureKey: String?): Boolean =
    !fieldSignatureKey.isNullOrBlank() &&
        AutofillPreferences(context.applicationContext).isFieldSignatureBlocked(fieldSignatureKey)

internal fun blockedAutofillResponse(autofillIds: List<AutofillId>): FillResponse? {
    val ids = autofillIds.distinct()
    if (ids.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return FillResponse.Builder()
        .setIgnoredIds(*ids.toTypedArray())
        // A response with no datasets/authentication needs client state on API 28+.
        // No save prompt or app-wide disable is needed to suppress these fields.
        .setClientState(Bundle())
        .build()
}

internal fun Activity.finishBlockedAutofillRequest(autofillIds: List<AutofillId>) {
    val response = blockedAutofillResponse(autofillIds)
    val result = Intent().apply {
        response?.let { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, it) }
    }
    // RESULT_CANCELED keeps the old response and lets Android show it again.
    // API 26/27 cannot build an ignored-only response: a successful empty result
    // invalidates the old authentication response without filling any values.
    setResult(Activity.RESULT_OK, result)
    finish()
    overridePendingTransition(0, 0)
}
