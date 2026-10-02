package takagi.ru.monica.autofill_ng

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutofillPickerArgsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun requestSurvivesSystemClassLoaderAndKeepsEveryTarget() {
        lateinit var field: EditText
        instrumentation.runOnMainSync { field = EditText(context) }
        val expected = AutofillPickerActivityV2.Args(
            applicationId = "org.example.form", webDomain = "example.org", webScheme = "https",
            interactionIdentifier = "interaction", interactionIdentifierAliases = arrayListOf("alias"),
            capturedUsername = "synthetic-user", capturedPassword = "synthetic-password",
            autofillIds = arrayListOf(field.autofillId), autofillHints = arrayListOf("creditCardNumber"),
            suggestedPasswordIds = longArrayOf(1, 2), isSaveMode = true, fieldSignatureKey = "signature",
            responseAuthMode = true, rememberLastFilled = false,
        )
        val transported = parcelRoundTrip(AutofillPickerActivityV2.getIntent(context, expected))
        // Simulate system_server eagerly unpacking every value, without the app's classes.
        transported.setExtrasClassLoader(Bundle::class.java.classLoader)
        val extras = requireNotNull(transported.extras)
        assertFalse(extras.containsKey("extra_args"))
        val bundle = requireNotNull(extras.getBundle("extra_args_bundle"))
        bundle.classLoader = Bundle::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.keySet().forEach { bundle.get(it) }
        val actual = requireNotNull(AutofillPickerActivityV2.readArgs(parcelRoundTrip(transported)))
        assertEquals(expected.applicationId, actual.applicationId)
        assertEquals(expected.webDomain, actual.webDomain)
        assertEquals(expected.webScheme, actual.webScheme)
        assertEquals(expected.interactionIdentifier, actual.interactionIdentifier)
        assertEquals(expected.interactionIdentifierAliases, actual.interactionIdentifierAliases)
        assertEquals(expected.capturedUsername, actual.capturedUsername)
        assertEquals(expected.capturedPassword, actual.capturedPassword)
        assertEquals(expected.autofillIds, actual.autofillIds)
        assertEquals(expected.autofillHints, actual.autofillHints)
        assertArrayEquals(expected.suggestedPasswordIds, actual.suggestedPasswordIds)
        assertEquals(expected.isSaveMode, actual.isSaveMode)
        assertEquals(expected.fieldSignatureKey, actual.fieldSignatureKey)
        assertEquals(expected.responseAuthMode, actual.responseAuthMode)
        assertEquals(expected.rememberLastFilled, actual.rememberLastFilled)
    }

    @Test fun defaultsAndPreviouslyIssuedAppIntentsRemainReadable() {
        assertNull(AutofillPickerActivityV2.readArgs(Intent()))
        val defaults = requireNotNull(AutofillPickerActivityV2.readArgs(parcelRoundTrip(
            AutofillPickerActivityV2.getIntent(context, AutofillPickerActivityV2.Args()))))
        assertTrue(defaults.rememberLastFilled)
        assertFalse(defaults.isSaveMode)
        assertFalse(defaults.responseAuthMode)
        assertNull(defaults.autofillIds)
        val old = AutofillPickerActivityV2.Args(applicationId = "org.example.legacy", autofillHints = arrayListOf("postalCode"))
        val restored = requireNotNull(AutofillPickerActivityV2.readArgs(parcelRoundTrip(Intent().putExtra("extra_args", old))))
        assertEquals(old.applicationId, restored.applicationId)
        assertEquals(old.autofillHints, restored.autofillHints)
    }

    private fun parcelRoundTrip(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
