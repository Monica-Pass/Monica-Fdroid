package takagi.ru.monica.autofill_ng

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillRequestContextPolicyTest {
    @Test
    fun knownBrowserWithoutOriginCannotUsePackageFallback() {
        assertFalse(
            AutofillRequestContextPolicy.allowPackageMatching(
                packageName = "com.android.chrome",
                webDomain = null,
                isWebView = false,
            )
        )
    }

    @Test
    fun webViewWithoutOriginCannotUsePackageFallbackEvenForUnknownHostApp() {
        assertFalse(
            AutofillRequestContextPolicy.allowPackageMatching(
                packageName = "com.example.host",
                webDomain = null,
                isWebView = true,
            )
        )
    }

    @Test
    fun nativeAppKeepsExactPackageMatching() {
        assertTrue(
            AutofillRequestContextPolicy.allowPackageMatching(
                packageName = "com.example.nativeapp",
                webDomain = null,
                isWebView = false,
            )
        )
    }
}
