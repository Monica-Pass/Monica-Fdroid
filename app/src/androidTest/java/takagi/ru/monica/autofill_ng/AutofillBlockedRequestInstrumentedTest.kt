package takagi.ru.monica.autofill_ng

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.autofill_ng.auth.AutofillGrantContext
import takagi.ru.monica.autofill_ng.auth.AutofillUnlockRequests
import takagi.ru.monica.autofill_ng.auth.PendingAutofillUnlockRequest
import takagi.ru.monica.autofill_ng.model.AutofillPartition
import takagi.ru.monica.autofill_ng.model.AutofillRequest
import takagi.ru.monica.autofill_ng.model.AutofillView

@RunWith(AndroidJUnit4::class)
class AutofillBlockedRequestInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = AutofillPreferences(context)

    @Test fun markingAFieldReturnsAReplacementInsteadOfCancelingAuthentication() {
        val signature = UUID.randomUUID().toString()
        val id = EditText(context).autofillId
        try {
            val intent = AutofillPickerActivityV2.getIntent(context, AutofillPickerActivityV2.Args(
                applicationId = "test.monica.blocked",
                fieldSignatureKey = signature,
                autofillIds = arrayListOf(id),
                autofillHints = arrayListOf("USERNAME"),
            ))
            ActivityScenario.launchActivityForResult<AutofillPickerActivityV2>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    AutofillPickerActivityV2::class.java.getDeclaredMethod("markCurrentFieldAsNonAutofill")
                        .apply { isAccessible = true }.invoke(activity)
                }
                val result = scenario.result
                assertTrue(runBlocking { preferences.isFieldSignatureBlocked(signature) })
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertNoSuggestions(result.resultData, listOf(id))
            }
        } finally {
            runBlocking { preferences.removeBlockedFieldSignature(signature) }
        }
    }

    @Test fun cachedUnlockIsRejectedBeforeVerification() {
        val signature = UUID.randomUUID().toString()
        val id = EditText(context).autofillId
        val view = AutofillView.Login.Username(AutofillView.Data(
            autofillId = id, autofillType = 1, isFocused = true,
            textValue = null, website = null,
            hint = EnhancedAutofillStructureParserV2.FieldHint.USERNAME,
        ))
        val request = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(), inlinePresentationSpecs = null,
            maxInlineSuggestionsCount = 0, isCompatMode = false,
            packageName = "test.monica.blocked", partition = AutofillPartition.Login(listOf(view)),
            uri = "androidapp://test.monica.blocked", fieldSignatureKey = signature,
        )
        val token = AutofillUnlockRequests.put(PendingAutofillUnlockRequest(
            request = request, passwordIds = emptyList(), passwordSuggestionEnabled = false,
            grantContext = AutofillGrantContext.fromRequestUri(request.packageName, request.uri, null, signature),
        ))
        try {
            // The mark happens after the response/token was issued to Android.
            runBlocking { preferences.markFieldSignatureBlocked(signature) }
            ActivityScenario.launchActivityForResult<AutofillUnlockActivity>(
                AutofillUnlockActivity.getIntent(context, token)
            ).use { scenario ->
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertNoSuggestions(result.resultData, listOf(id))
                assertNull(AutofillUnlockRequests.peek(token))
            }
        } finally {
            AutofillUnlockRequests.discard(token)
            runBlocking { preferences.removeBlockedFieldSignature(signature) }
        }
    }

    @Test fun cachedDatasetIsRejectedWithVerificationEnabledOrDisabled() {
        val signature = UUID.randomUUID().toString()
        val id = EditText(context).autofillId
        try {
            runBlocking { preferences.markFieldSignatureBlocked(signature) }
            listOf(true, false).forEach { requiresAuthentication ->
                val intent = AutofillCipherCallbackActivity.getIntent(context, AutofillCipherCallbackActivity.Args(
                    passwordId = Long.MAX_VALUE,
                    applicationId = "test.monica.blocked",
                    fieldSignatureKey = signature,
                    autofillIds = arrayListOf(id),
                    requireAuthentication = requiresAuthentication,
                ))
                ActivityScenario.launchActivityForResult<AutofillCipherCallbackActivity>(intent).use { scenario ->
                    val result = scenario.result
                    assertEquals(Activity.RESULT_OK, result.resultCode)
                    assertNoSuggestions(result.resultData, listOf(id))
                }
            }
        } finally {
            runBlocking { preferences.removeBlockedFieldSignature(signature) }
        }
    }

    @Test fun blockingOneSignatureDoesNotBlockAnotherField() = runBlocking {
        val signature = UUID.randomUUID().toString()
        try {
            preferences.markFieldSignatureBlocked(signature)
            assertTrue(isAutofillRequestBlocked(context, signature))
            assertFalse(isAutofillRequestBlocked(context, "$signature-other-field"))
            assertFalse(isAutofillRequestBlocked(context, null))
        } finally {
            preferences.removeBlockedFieldSignature(signature)
        }
    }

    @Suppress("DEPRECATION")
    private fun assertNoSuggestions(result: Intent?, ids: List<AutofillId>) {
        assertNotNull(result)
        val response = result?.getParcelableExtra<FillResponse>(AutofillManager.EXTRA_AUTHENTICATION_RESULT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            assertNull(response)
            return
        }
        assertNotNull(response)
        // FillResponse getters are hidden APIs on devices. Compare the public
        // Parcelable representation to an ignored-only response on this same
        // runtime: any dataset, authentication, save prompt or disable differs.
        val expected = FillResponse.Builder()
            .setIgnoredIds(*ids.distinct().toTypedArray())
            .setClientState(Bundle())
            .build()
        fun encode(value: FillResponse): ByteArray {
            val parcel = Parcel.obtain()
            return try {
                value.writeToParcel(parcel, 0)
                parcel.marshall()
            } finally {
                parcel.recycle()
            }
        }
        assertArrayEquals(encode(expected), encode(requireNotNull(response)))
    }
}
